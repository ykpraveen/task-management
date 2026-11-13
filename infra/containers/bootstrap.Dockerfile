# Privileged one-shot database bootstrap: psql for cluster objects plus Flyway for
# identity and Supabase policy migrations. Never used by application containers.
FROM flyway/flyway:13.10.3-alpine@sha256:f6358224a41866888c3341b9c1e286fa2be8e1ccc18270b219dc0e8ea26e789f
USER root
RUN apk add --no-cache postgresql17-client
WORKDIR /bootstrap
COPY infra/database/bootstrap/ /bootstrap/
COPY infra/identity/migrations/ /migrations/identity/
COPY infra/supabase/migrations/ /migrations/supabase/
RUN adduser -D -H -u 10001 bootstrap
USER 10001
ENTRYPOINT ["/bin/sh", "/bootstrap/run.sh"]
