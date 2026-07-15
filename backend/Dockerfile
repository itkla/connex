# syntax=docker/dockerfile:1

FROM eclipse-temurin:26-jdk-noble AS build
WORKDIR /workspace
ARG VERSION=0.0.0-dev
COPY gradlew ./
COPY gradle ./gradle
COPY settings.gradle build.gradle ./
RUN chmod +x gradlew
COPY src ./src
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew bootWar -Pversion=${VERSION} --no-daemon --stacktrace \
    && find build/libs -name '*.war' ! -name '*-plain.war' -exec cp {} /workspace/app.war \;

FROM eclipse-temurin:26-jre-noble AS runtime
WORKDIR /app
ARG VERSION=0.0.0-dev
LABEL org.opencontainers.image.title="connex-backend" \
      org.opencontainers.image.source="https://github.com/itkla/connex" \
      org.opencontainers.image.version="${VERSION}"
ENV JAVA_OPTS="" \
    CONNEX_OBJECT_STORAGE_FILESYSTEM_ROOT="/var/lib/connex/objects"
RUN groupadd --system connex \
    && useradd --system --gid connex --home-dir /app connex \
    && install -d -o connex -g connex -m 0700 /var/lib/connex/objects
COPY --from=build /workspace/app.war /app/app.war
USER connex
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.war"]
