#!/bin/sh
# db-bootstrap one-shot job: cluster objects (psql), then identity and Supabase policy
# migrations (Flyway as administrator). Runs on every `docker compose up`, so migrations
# added later are applied to existing volumes. Credentials stay inside this container.
set -eu

: "${PGHOST:?}" "${PGPORT:?}" "${PGDATABASE:?}" "${PGUSER:?}" "${PGPASSWORD:?}"
: "${DEFAULT_TIME_ZONE:?}"

echo "db-bootstrap: applying roles, schemas and grants"
psql -X -q -v ON_ERROR_STOP=1 \
  -v identity_owner_password="${IDENTITY_OWNER_PASSWORD:?}" \
  -v task_owner_password="${TASK_OWNER_PASSWORD:?}" \
  -v task_app_password="${TASK_APP_PASSWORD:?}" \
  -v notification_owner_password="${NOTIFICATION_OWNER_PASSWORD:?}" \
  -v notification_app_password="${NOTIFICATION_APP_PASSWORD:?}" \
  -f /bootstrap/bootstrap.sql >/dev/null

echo "db-bootstrap: migrating identity and Supabase policy migrations"
flyway -q \
  -url="jdbc:postgresql://${PGHOST}:${PGPORT}/${PGDATABASE}" \
  -user="${PGUSER}" -password="${PGPASSWORD}" \
  -locations=filesystem:/migrations/identity,filesystem:/migrations/supabase \
  -schemas=identity -createSchemas=false \
  -table=bootstrap_schema_history \
  -outOfOrder=false -validateMigrationNaming=true \
  migrate

echo "db-bootstrap: applying directory settings"
psql -X -q -v ON_ERROR_STOP=1 -v zone="${DEFAULT_TIME_ZONE}" <<'SQL' >/dev/null
select exists (select 1 from pg_timezone_names where name = :'zone') as zone_ok \gset
\if :zone_ok
insert into identity.settings (singleton, default_time_zone) values (true, :'zone')
on conflict (singleton) do update set default_time_zone = excluded.default_time_zone;
\else
\warn 'db-bootstrap: DEFAULT_TIME_ZONE is not a PostgreSQL IANA zone name'
\quit 3
\endif
SQL

echo "db-bootstrap: done"
