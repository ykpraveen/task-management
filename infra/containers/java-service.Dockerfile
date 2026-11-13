# Multi-arch (linux/amd64, linux/arm64) build for every Java module in the Maven reactor.
#   docker build -f infra/containers/java-service.Dockerfile --build-arg MODULE=services/task-api .
ARG MODULE

FROM eclipse-temurin:25-jdk@sha256:3ab7fc8d0b4ade8fef96903fb36767c1dbb4fd1535dcfad85b59c17afbbb5857 AS build
ARG MODULE
WORKDIR /src
COPY mvnw pom.xml ./
COPY .mvn .mvn
COPY services/gateway/pom.xml services/gateway/pom.xml
COPY services/task-api/pom.xml services/task-api/pom.xml
COPY services/notifications/pom.xml services/notifications/pom.xml
COPY tools/identity/pom.xml tools/identity/pom.xml
COPY services services
COPY tools tools
RUN test -n "$MODULE" \
 && ./mvnw -B -q -pl "$MODULE" -am -DskipTests -Dmaven.test.skip=true package \
 && cp "$MODULE/target/app.jar" /app.jar

FROM eclipse-temurin:25-jre-alpine@sha256:b7528962e65d43064d8a8b67ba175367896bc948363ea2d3fcd7f10d34e1f048
RUN addgroup -S -g 10001 app && adduser -S -D -H -u 10001 -G app app
WORKDIR /app
COPY --from=build /app.jar /app/app.jar
COPY infra/containers/java-entrypoint.sh /app/entrypoint.sh
USER 10001:10001
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["/bin/sh", "/app/entrypoint.sh"]
CMD ["serve"]
