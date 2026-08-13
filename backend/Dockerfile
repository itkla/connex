# syntax=docker/dockerfile:1.24.0@sha256:87999aa3d42bdc6bea60565083ee17e86d1f3339802f543c0d03998580f9cb89

ARG BUILD_TIME
ARG SOURCE_DATE_EPOCH

FROM eclipse-temurin:26-jdk-noble@sha256:e198c796c8b49353f02deb9d7ca82952abc634395c575ca03385c467d223f6e3 AS build
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

FROM eclipse-temurin:26-jre-noble@sha256:d095323f078a68e0663f7bb7ff103f6275227a929c3ecc6f6fb626ebe6e4d51a AS runtime
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
