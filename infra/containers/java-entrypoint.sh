#!/bin/sh
# serve   - run the service (default)
# migrate - run this service's Flyway migrations with its owner role, then exit
# other   - passed to the application as CLI arguments (tools/identity commands)
set -eu
mode=${1:-serve}
case "$mode" in
  serve)
    exec java -jar /app/app.jar
    ;;
  migrate)
    exec java -jar /app/app.jar --spring.profiles.active=migrate
    ;;
  *)
    exec java -jar /app/app.jar "$@"
    ;;
esac
