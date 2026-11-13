-- Minimal stand-in for the Supabase-managed objects that bootstrap.sql and the identity
-- migrations depend on. Column names and types match the pinned supabase/gotrue auth.users.
create role anon nologin;
create role authenticated nologin;
create role service_role nologin;

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
alter table auth.users enable row level security;
create function auth.uid() returns uuid language sql stable as $$
    select nullif(current_setting('request.jwt.claim.sub', true), '')::uuid
$$;
