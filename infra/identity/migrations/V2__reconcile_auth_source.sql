-- Reconcile source for tools/identity. Supabase enables row-level security on auth.users
-- without policies, so a column grant alone returns no rows. This function is owned by the
-- migration administrator, returns only the server-controlled and profile fields the
-- projection uses, and is executable only by identity_owner.
create function identity.auth_users_source()
    returns table (
        id                 uuid,
        email              text,
        raw_user_meta_data jsonb,
        email_confirmed_at timestamptz,
        phone_confirmed_at timestamptz,
        banned_until       timestamptz,
        deleted_at         timestamptz
    )
    language sql
    stable
    security definer
    set search_path = ''
as $$
    select u.id, u.email::text, u.raw_user_meta_data, u.email_confirmed_at, u.phone_confirmed_at,
           u.banned_until, u.deleted_at
    from auth.users u
$$;
revoke all on function identity.auth_users_source() from public;
grant execute on function identity.auth_users_source() to identity_owner;
