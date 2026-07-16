# syntax=docker/dockerfile:1.24.0@sha256:87999aa3d42bdc6bea60565083ee17e86d1f3339802f543c0d03998580f9cb89

ARG BUILD_TIME
ARG SOURCE_DATE_EPOCH

FROM eclipse-temurin:26-jdk-noble@sha256:892b3d1ed859f102db403e01415ecd05b1dd8ed6679857fe19044a49e45a911e AS build
WORKDIR /workspace
ARG VERSION=0.0.0-dev
ARG BUILD_TIME
COPY gradlew ./
COPY gradle ./gradle
COPY settings.gradle build.gradle ./
RUN chmod +x gradlew
COPY src ./src
RUN --mount=type=cache,target=/root/.gradle \
    if [ -n "$BUILD_TIME" ]; then set -- "-PbuildTime=$BUILD_TIME"; else set --; fi \
    && ./gradlew bootWar -Pversion=${VERSION} "$@" --no-daemon --stacktrace \
    && find build/libs -name '*.war' ! -name '*-plain.war' -exec cp {} /workspace/app.war \;

FROM eclipse-temurin:26-jre-noble@sha256:3bb9b9007f221fe09932f05a3362a340ecccaa6dbada7f1edf646f952ff597d8 AS runtime
WORKDIR /app
ARG VERSION=0.0.0-dev
LABEL org.opencontainers.image.title="connex-backend" \
      org.opencontainers.image.source="https://github.com/itkla/connex" \
      org.opencontainers.image.version="${VERSION}"
ENV JAVA_OPTS="" \
    CONNEX_OBJECT_STORAGE_FILESYSTEM_ROOT="/var/lib/connex/objects"
RUN groupadd --system --gid 10001 connex \
    && useradd --system --uid 10001 --gid connex --home-dir /app connex \
    && install -d -o connex -g connex -m 0700 /var/lib/connex/objects
COPY --from=build /workspace/app.war /app/app.war
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "rm -f /tmp/connex-ready && exec java $JAVA_OPTS -jar /app/app.war"]
