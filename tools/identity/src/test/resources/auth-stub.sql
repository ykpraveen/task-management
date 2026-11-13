-- Minimal stand-in for the Supabase-managed objects the identity migration depends on.
-- Column names and types match the pinned supabase/gotrue auth.users table.
create role anon nologin;
create role authenticated nologin;
create role service_role nologin;
create role identity_owner login password 'identity_owner';
create role task_app login password 'task_app';
create role notification_app login password 'notification_app';

create schema auth;
create table auth.users (
    id                 uuid primary key,
    email              text,
    raw_user_meta_data jsonb,
    email_confirmed_at timestamptz,
    phone_confirmed_at timestamptz,
    banned_until       timestamptz,
    deleted_at         timestamptz
);
-- Supabase enables RLS on auth.users without policies.
alter table auth.users enable row level security;
create function auth.uid() returns uuid language sql stable as $$
    select nullif(current_setting('request.jwt.claim.sub', true), '')::uuid
$$;

-- Equivalent of the relevant bootstrap.sql statements.
create schema identity authorization identity_owner;
grant usage on schema auth to identity_owner;
grant execute on function auth.uid() to identity_owner;
