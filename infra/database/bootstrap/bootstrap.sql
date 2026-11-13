-- Privileged, idempotent cluster bootstrap (data-model.md "Schema and migration ownership").
-- Run by run.sh as the Supabase administrator. Safe to re-run on every `docker compose up`.
-- psql variables: identity_owner_password, task_owner_password, task_app_password,
--                 notification_owner_password, notification_app_password
\set ON_ERROR_STOP on
set client_min_messages = warning;

-- Roles: owners run migrations; runtime roles are non-owners with no DDL.
select format('create role %I login nosuperuser nocreatedb nocreaterole noinherit noreplication nobypassrls', r)
from (values ('identity_owner'), ('task_owner'), ('notification_owner'), ('task_app'), ('notification_app')) v(r)
where not exists (select 1 from pg_roles where rolname = r)
\gexec

-- Passwords are (re)applied from runtime secrets; never printed.
select format('alter role identity_owner password %L', :'identity_owner_password') \gexec
select format('alter role task_owner password %L', :'task_owner_password') \gexec
select format('alter role task_app password %L', :'task_app_password') \gexec
select format('alter role notification_owner password %L', :'notification_owner_password') \gexec
select format('alter role notification_app password %L', :'notification_app_password') \gexec

alter role task_app set search_path = task;
alter role notification_app set search_path = notification;
alter role task_owner set search_path = task;
alter role notification_owner set search_path = notification;
alter role task_app set statement_timeout = '15s';
alter role notification_app set statement_timeout = '15s';

-- Schemas with separate ownership.
create schema if not exists identity authorization identity_owner;
create schema if not exists task authorization task_owner;
create schema if not exists notification authorization notification_owner;
alter schema identity owner to identity_owner;
alter schema task owner to task_owner;
alter schema notification owner to notification_owner;

-- No cross-service or public access to application schemas.
revoke all on schema task from public;
revoke all on schema notification from public;
revoke all on schema identity from public;
revoke create on schema public from task_app, notification_app, task_owner, notification_owner;

grant usage on schema task to task_app;
grant usage on schema notification to notification_app;

-- Runtime roles get DML (never DDL) on objects their owner creates.
alter default privileges for role task_owner in schema task
    grant select, insert, update, delete on tables to task_app;
alter default privileges for role task_owner in schema task
    grant usage, select on sequences to task_app;
alter default privileges for role notification_owner in schema notification
    grant select, insert, update, delete on tables to notification_app;
alter default privileges for role notification_owner in schema notification
    grant usage, select on sequences to notification_app;

-- Identity helpers call auth.uid(); the directory owner needs only that function.
grant usage on schema auth to identity_owner;
grant execute on function auth.uid() to identity_owner;

-- Application schemas are never exposed to Supabase API roles.
revoke all on schema task, notification from anon, authenticated, service_role;

-- identity_owner reads Auth users only through identity.auth_users_source() (migration V2).
revoke all on auth.users from identity_owner;
