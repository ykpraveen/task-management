#!/bin/sh
# Generates local demo secrets and Supabase ES256 signing keys into the ignored `.env`.
# Idempotent: existing values (and therefore existing credentials and volumes) are never
# overwritten; only missing or __GENERATE__ entries are filled. Secrets are never printed.
#
#   docker compose --profile tools run --rm setup
set -eu

cd "$(dirname "$0")/.."

if [ ! -f .env.example ]; then
  echo "setup: .env.example not found" >&2
  exit 1
fi

umask 077
node scripts/lib/generate-secrets.mjs .env.example .env
echo "setup: .env is ready (values not shown). Existing values were preserved."
