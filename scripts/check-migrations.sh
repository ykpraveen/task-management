#!/bin/sh
# Fails on duplicate or gapped Flyway versions in any migration location set, and on changes
# to migrations that already exist on the base branch (applied migrations are immutable).
#
#   docker compose --profile tools run --rm check-migrations
#   MIGRATION_BASE_REF=origin/main sh scripts/check-migrations.sh
set -eu

cd "$(dirname "$0")/.."
base_ref=${MIGRATION_BASE_REF:-main}
status=0

fail() {
  echo "check-migrations: $*" >&2
  status=1
}

# One Flyway history per set; the bootstrap job applies identity and Supabase together.
check_set() {
  name=$1
  shift
  before=$status
  versions=$(for dir in "$@"; do
      [ -d "$dir" ] || continue
      for f in "$dir"/*.sql; do
        [ -e "$f" ] || continue
        base=$(basename "$f")
        case "$base" in
          (V[0-9]*__*.sql) echo "$base" | sed -E 's/^V([0-9]+)__.*/\1/' ;;
          (*) fail "$name: $f does not follow V<integer>__<purpose>.sql" ;;
        esac
      done
    done | sort -n)
  [ -n "$versions" ] || { echo "check-migrations: $name: no migrations"; return 0; }
  dups=$(echo "$versions" | uniq -d)
  [ -z "$dups" ] || fail "$name: duplicate versions: $(echo $dups)"
  expected=1
  for v in $(echo "$versions" | uniq); do
    if [ "$v" -ne "$expected" ]; then
      fail "$name: version gap, expected V$expected but found V$v"
      expected=$v
    fi
    expected=$((expected + 1))
  done
  [ "$status" -ne "$before" ] || echo "check-migrations: $name: versions $(echo $versions | tr ' ' ',') ok"
}

check_set task-api services/task-api/src/main/resources/db/migration
check_set notifications services/notifications/src/main/resources/db/migration
check_set bootstrap infra/identity/migrations infra/supabase/migrations

# Immutability: migrations present on the base branch must be unchanged in the working tree.
if command -v git >/dev/null 2>&1 && git rev-parse --verify --quiet "$base_ref" >/dev/null; then
  for f in $(git ls-tree -r --name-only "$base_ref" -- services/task-api/src/main/resources/db/migration \
        services/notifications/src/main/resources/db/migration infra/identity/migrations infra/supabase/migrations); do
    case "$f" in *.sql) ;; *) continue ;; esac
    if [ ! -e "$f" ]; then
      fail "applied migration removed: $f"
    elif ! git diff --quiet "$base_ref" -- "$f"; then
      fail "applied migration modified: $f"
    fi
  done
  echo "check-migrations: immutability checked against $base_ref"
else
  fail "git or base ref '$base_ref' unavailable; immutability not checked"
fi

exit $status
