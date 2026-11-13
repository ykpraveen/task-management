-- Identity-owned directory projection of Supabase Auth users (data-model.md, DirectoryUser).
-- Applied by the privileged db-bootstrap job (Flyway, history identity.bootstrap_schema_history).
-- Objects are owned by identity_owner; runtime roles get read-only access to the view only.

create table identity.settings (
    singleton boolean primary key default true check (singleton),
    default_time_zone text not null
);
alter table identity.settings owner to identity_owner;

create table identity.directory_user (
    user_id      uuid primary key,
    display_name text not null check (char_length(display_name) between 1 and 200),
    email        text,
    -- Base activation from server-controlled Auth fields (confirmed and not soft-deleted).
    -- A ban is stored separately so that an expiring ban takes effect without a new write.
    active       boolean not null,
    banned_until timestamptz,
    time_zone    text not null,
    deleted      boolean not null default false,
    updated_at   timestamptz not null default now()
);
alter table identity.directory_user owner to identity_owner;

create index directory_user_display_name_idx on identity.directory_user (lower(display_name));
create index directory_user_email_idx on identity.directory_user (lower(email));

-- Effective activity: base active, not deleted and no current ban.
create view identity.directory as
select user_id,
       display_name,
       email,
       (active and not deleted and (banned_until is null or banned_until <= now())) as active,
       time_zone,
       deleted,
       updated_at
from identity.directory_user;
alter view identity.directory owner to identity_owner;

create function identity.default_time_zone()
    returns text
    language sql
    stable
    security definer
    set search_path = ''
as $$
    select coalesce((select default_time_zone from identity.settings where singleton), 'UTC')
$$;
alter function identity.default_time_zone() owner to identity_owner;

create function identity.is_valid_time_zone(zone text)
    returns boolean
    language sql
    stable
    set search_path = ''
as $$
    select zone is not null and exists (select 1 from pg_catalog.pg_timezone_names where name = zone)
$$;
alter function identity.is_valid_time_zone(text) owner to identity_owner;

-- Computes the projection row for one auth.users row. Activation never reads user metadata.
create function identity.project_auth_user(
    p_user_id uuid,
    p_email text,
    p_meta jsonb,
    p_email_confirmed_at timestamptz,
    p_phone_confirmed_at timestamptz,
    p_banned_until timestamptz,
    p_deleted_at timestamptz
)
    returns void
    language plpgsql
    security definer
    set search_path = ''
as $$
declare
    v_previous identity.directory_user;
    v_name text := btrim(coalesce(p_meta ->> 'display_name', ''));
    v_zone text := p_meta ->> 'time_zone';
    v_default_name text := left(coalesce(nullif(split_part(coalesce(p_email, ''), '@', 1), ''), 'User'), 200);
begin
    select * into v_previous from identity.directory_user where user_id = p_user_id;

    if char_length(v_name) not between 1 and 200 then
        v_name := coalesce(v_previous.display_name, v_default_name);
    end if;

    if not identity.is_valid_time_zone(v_zone) then
        -- An invalid zone keeps the previous value (or the configured default).
        v_zone := coalesce(v_previous.time_zone, identity.default_time_zone());
    end if;

    insert into identity.directory_user as d
        (user_id, display_name, email, active, banned_until, time_zone, deleted, updated_at)
    values (p_user_id,
            v_name,
            p_email,
            (p_email_confirmed_at is not null or p_phone_confirmed_at is not null) and p_deleted_at is null,
            p_banned_until,
            v_zone,
            p_deleted_at is not null,
            now())
    on conflict (user_id) do update
        set display_name = excluded.display_name,
            email        = excluded.email,
            active       = excluded.active,
            banned_until = excluded.banned_until,
            time_zone    = excluded.time_zone,
            deleted      = excluded.deleted,
            updated_at   = excluded.updated_at;
end
$$;
alter function identity.project_auth_user(uuid, text, jsonb, timestamptz, timestamptz, timestamptz, timestamptz)
    owner to identity_owner;
revoke all on function identity.project_auth_user(uuid, text, jsonb, timestamptz, timestamptz, timestamptz, timestamptz)
    from public;

create function identity.sync_auth_user()
    returns trigger
    language plpgsql
    security definer
    set search_path = ''
as $$
begin
    if tg_op = 'DELETE' then
        -- Soft delete: history keeps referencing the stable UUID and display name.
        update identity.directory_user
           set active = false, deleted = true, updated_at = now()
         where user_id = old.id;
        return old;
    end if;

    perform identity.project_auth_user(new.id, new.email, new.raw_user_meta_data,
                                       new.email_confirmed_at, new.phone_confirmed_at,
                                       new.banned_until, new.deleted_at);
    return new;
end
$$;
alter function identity.sync_auth_user() owner to identity_owner;
revoke all on function identity.sync_auth_user() from public;

-- Same transaction as the Auth write.
create trigger identity_directory_sync
    after insert or update or delete on auth.users
    for each row execute function identity.sync_auth_user();

-- Backfill users that existed before the trigger was installed.
select identity.project_auth_user(id, email, raw_user_meta_data, email_confirmed_at,
                                  phone_confirmed_at, banned_until, deleted_at)
from auth.users;

-- Realtime authorization helper: whether the verified current subject is active.
-- Fixed subject (auth.uid()), no arguments, returns no rows or directory fields.
create function identity.current_user_is_active()
    returns boolean
    language sql
    stable
    security definer
    set search_path = ''
as $$
    select coalesce((select d.active from identity.directory d where d.user_id = auth.uid()), false)
$$;
alter function identity.current_user_is_active() owner to identity_owner;
revoke all on function identity.current_user_is_active() from public;
grant usage on schema identity to authenticated;
grant execute on function identity.current_user_is_active() to authenticated;

-- Read-only directory contract for the task and notification runtime roles.
grant usage on schema identity to task_app, notification_app;
grant select on identity.directory to task_app, notification_app;
