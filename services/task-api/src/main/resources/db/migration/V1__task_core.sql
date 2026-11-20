-- Task-owned core schema (data-model.md: Task, DeadlineRequest, ActivityEvent, MutationReceipt).
-- Applied by the task-api-migrate job as task_owner (history task.flyway_schema_history).
-- Runtime role task_app receives table privileges through the bootstrap's default privileges;
-- this migration only narrows them. No foreign keys cross into identity: user references are
-- stable Auth UUIDs that survive deactivation and soft deletion.

-- ------------------------------------------------------------------ Task

create table task.task (
    id                  bigint generated always as identity (no cycle) primary key,
    -- immutable `creator_id` (enforced by task.task_guard_update)
    creator_id          uuid not null,
    assignee_id         uuid not null,
    -- `title` "trimmed 1..200 characters"
    title               text not null
        constraint task_title_trimmed_1_200
        check (char_length(title) between 1 and 200 and title !~ '^\s' and title !~ '\s$'),
    -- `description` "0..20,000 characters"
    description         text not null default ''
        constraint task_description_0_20000 check (char_length(description) <= 20000),
    -- `priority` "`low|normal|high`"
    priority            text not null default 'normal'
        constraint task_priority_values check (priority in ('low', 'normal', 'high')),
    -- `status` "`todo|in_progress|blocked|done|cancelled`"
    status              text not null default 'todo'
        constraint task_status_values check (status in ('todo', 'in_progress', 'blocked', 'done', 'cancelled')),
    due_at              timestamptz,
    due_time_zone       text
        constraint task_due_time_zone_length check (char_length(due_time_zone) between 1 and 64),
    -- `blocked_reason`/`cancellation_reason` "1..2,000 when applicable"
    blocked_reason      text
        constraint task_blocked_reason_1_2000 check (char_length(blocked_reason) between 1 and 2000),
    cancellation_reason text
        constraint task_cancellation_reason_1_2000 check (char_length(cancellation_reason) between 1 and 2000),
    created_at          timestamptz not null default now(),
    updated_at          timestamptz not null default now(),
    completed_at        timestamptz,
    -- a monotonically increasing `version` (enforced by task.task_guard_update)
    version             bigint not null default 1
        constraint task_version_positive check (version > 0),

    -- `due_at` and `due_time_zone` "both present or both absent"
    constraint task_deadline_pair check ((due_at is null) = (due_time_zone is null)),
    -- Only blocked has a current blocked reason; only cancelled has a cancellation reason;
    -- only done has a completion time.
    constraint task_blocked_reason_when_blocked check ((status = 'blocked') = (blocked_reason is not null)),
    constraint task_cancellation_reason_when_cancelled check ((status = 'cancelled') = (cancellation_reason is not null)),
    constraint task_completed_at_when_done check ((status = 'done') = (completed_at is not null))
);

-- Participant lists filter by user and status, order by deadline (nulls last in the query)
-- and break ties on the stable (created_at, id) key.
create index task_creator_list_idx on task.task (creator_id, status, due_at, created_at, id);
create index task_assignee_list_idx on task.task (assignee_id, status, due_at, created_at, id);
create index task_created_order_idx on task.task (created_at, id);

-- Rejects creator changes and any update that does not advance the version, so every
-- committed change is observable to If-Match/ETag checks.
create function task.task_guard_update()
    returns trigger
    language plpgsql
    set search_path = ''
as $$
begin
    if new.creator_id is distinct from old.creator_id then
        raise exception 'task creator is immutable' using errcode = 'P0001';
    end if;
    if new.version <= old.version then
        raise exception 'task version must increase' using errcode = 'P0001';
    end if;
    return new;
end
$$;

create trigger task_guard_update
    before update on task.task
    for each row execute function task.task_guard_update();

-- ------------------------------------------------------------------ DeadlineRequest

create table task.deadline_request (
    id                 bigint generated always as identity (no cycle) primary key,
    task_id            bigint not null references task.task (id),
    requester_id       uuid not null,
    proposed_due_at    timestamptz not null,
    proposed_time_zone text not null
        constraint deadline_request_zone_length check (char_length(proposed_time_zone) between 1 and 64),
    reason             text not null
        constraint deadline_request_reason_1_2000 check (char_length(reason) between 1 and 2000),
    -- states "`pending|approved|declined|withdrawn|superseded`"
    state              text not null default 'pending'
        constraint deadline_request_state_values
        check (state in ('pending', 'approved', 'declined', 'withdrawn', 'superseded')),
    created_at         timestamptz not null default now(),
    resolved_at        timestamptz,
    resolved_by        uuid,
    -- Why a request was superseded: a direct deadline change or removal, reassignment,
    -- completion or cancellation (FR-021, FR-025).
    resolution_cause   text
        constraint deadline_request_resolution_cause_values
        check (resolution_cause in ('deadline_changed', 'deadline_removed', 'reassigned', 'completed', 'cancelled')),
    version            bigint not null default 1
        constraint deadline_request_version_positive check (version > 0),

    constraint deadline_request_resolved_when_not_pending check ((state = 'pending') = (resolved_at is null)),
    constraint deadline_request_cause_only_when_superseded check (resolution_cause is null or state = 'superseded')
);

-- "unique partial index on task where state=pending"
create unique index deadline_request_one_pending_idx on task.deadline_request (task_id) where state = 'pending';
create index deadline_request_task_order_idx on task.deadline_request (task_id, created_at, id);

create function task.deadline_request_guard_update()
    returns trigger
    language plpgsql
    set search_path = ''
as $$
begin
    if new.task_id is distinct from old.task_id
            or new.requester_id is distinct from old.requester_id
            or new.proposed_due_at is distinct from old.proposed_due_at
            or new.proposed_time_zone is distinct from old.proposed_time_zone
            or new.reason is distinct from old.reason
            or new.created_at is distinct from old.created_at then
        raise exception 'deadline request proposal is immutable' using errcode = 'P0001';
    end if;
    if old.state <> 'pending' then
        raise exception 'resolved deadline request is immutable' using errcode = 'P0001';
    end if;
    if new.version <= old.version then
        raise exception 'deadline request version must increase' using errcode = 'P0001';
    end if;
    return new;
end
$$;

create trigger deadline_request_guard_update
    before update on task.deadline_request
    for each row execute function task.deadline_request_guard_update();

-- ------------------------------------------------------------------ ActivityEvent

-- "immutable, with allowlisted `changes`": a JSON object whose keys are known task or
-- request fields, each holding only "previous" and/or "new" values.
create function task.activity_changes_allowlisted(changes jsonb)
    returns boolean
    language sql
    immutable
    set search_path = ''
as $$
    select jsonb_typeof(changes) = 'object'
       and not exists (
           select 1
           from pg_catalog.jsonb_each(changes) as field(key, value)
           where field.key not in ('title', 'description', 'priority', 'assignee_id', 'due_at',
                                   'due_time_zone', 'status', 'blocked_reason', 'cancellation_reason',
                                   'completed_at', 'proposed_due_at', 'proposed_time_zone', 'reason',
                                   'state', 'resolution_cause')
              or jsonb_typeof(field.value) <> 'object'
              or exists (select 1 from pg_catalog.jsonb_object_keys(field.value) as k
                         where k not in ('previous', 'new')))
$$;

create table task.activity_event (
    id                  bigint generated always as identity (no cycle) primary key,
    task_id             bigint not null references task.task (id),
    -- A user actor, or the explicit system actor for operational events.
    actor_type          text not null default 'user'
        constraint activity_event_actor_type_values check (actor_type in ('user', 'system')),
    actor_id            uuid,
    type                text not null
        constraint activity_event_type_values check (type in (
            'task_created', 'task_edited', 'task_reassigned', 'status_changed', 'task_reopened',
            'task_cancelled', 'deadline_request_submitted', 'deadline_request_approved',
            'deadline_request_declined', 'deadline_request_withdrawn', 'deadline_request_superseded')),
    occurred_at         timestamptz not null default now(),
    changes             jsonb not null default '{}'::jsonb
        constraint activity_event_changes_allowlisted check (task.activity_changes_allowlisted(changes)),
    -- Comment and attachment references gain foreign keys when those tables are created.
    comment_id          bigint,
    attachment_id       bigint,
    deadline_request_id bigint references task.deadline_request (id),

    constraint activity_event_actor check ((actor_type = 'user') = (actor_id is not null))
);

create index activity_event_task_order_idx on task.activity_event (task_id, occurred_at, id);

-- Append-only for every role; the runtime role is additionally denied the privileges.
create function task.activity_event_immutable()
    returns trigger
    language plpgsql
    set search_path = ''
as $$
begin
    raise exception 'activity events are immutable' using errcode = 'P0001';
end
$$;

create trigger activity_event_immutable
    before update or delete on task.activity_event
    for each row execute function task.activity_event_immutable();

revoke update, delete, truncate on task.activity_event from task_app;

-- ------------------------------------------------------------------ MutationReceipt

create table task.mutation_receipt (
    actor_id           uuid not null,
    operation          text not null
        constraint mutation_receipt_operation_format check (operation ~ '^[a-z][a-z_]{0,63}$'),
    idempotency_key    uuid not null,
    request_hash       text not null
        constraint mutation_receipt_request_hash_length check (char_length(request_hash) between 1 and 200),
    response_status    smallint not null
        constraint mutation_receipt_response_status check (response_status between 200 and 599),
    response_body      jsonb,
    -- The affected resource (for example the created task ID) so a replay rechecks access.
    response_reference text,
    created_at         timestamptz not null default now(),
    expires_at         timestamptz not null,

    -- "unique `(actor_id,operation,idempotency_key)`"
    constraint mutation_receipt_pkey primary key (actor_id, operation, idempotency_key),
    -- retained "at least seven days"
    constraint mutation_receipt_retained_seven_days check (expires_at >= created_at + interval '7 days')
);

create index mutation_receipt_expiry_idx on task.mutation_receipt (expires_at);
