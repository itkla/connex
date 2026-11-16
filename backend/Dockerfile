# syntax=docker/dockerfile:1.24.0@sha256:87999aa3d42bdc6bea60565083ee17e86d1f3339802f543c0d03998580f9cb89

ARG BUILD_TIME
ARG SOURCE_DATE_EPOCH

FROM eclipse-temurin:26-jdk-noble@sha256:9c0e8eddecccc0d5679b317b0c90fa7103ad25cae33df1c00280675eb681c7f8 AS build
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

FROM eclipse-temurin:26-jre-noble@sha256:adfd44c114916dc79fa9a24087fbac34252096edb9892d1c2d9d82db3f8947b3 AS runtime
WORKDIR /app
ARG VERSION=0.0.0-dev
LABEL org.opencontainers.image.title="connex-backend" \
      org.opencontainers.image.source="https://github.com/itkla/connex" \
      org.opencontainers.image.version="${VERSION}"
ENV JAVA_OPTS="" \
    CONNEX_OBJECT_STORAGE_FILESYSTEM_ROOT="/var/lib/connex/objects"
# Pick up OS security fixes published after the base image; the release scan fails on any fixable High.
RUN apt-get update \
    && apt-get -y --no-install-recommends upgrade \
    && rm -rf /var/lib/apt/lists/*
RUN groupadd --system --gid 10001 connex \
    && useradd --system --uid 10001 --gid connex --home-dir /app connex \
    && install -d -o connex -g connex -m 0700 /var/lib/connex/objects
COPY --from=build /workspace/app.war /app/app.war
COPY connex.java.security /app/connex.java.security
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "rm -f /tmp/connex-ready && exec java $JAVA_OPTS -Djava.security.properties=/app/connex.java.security -jar /app/app.war"]
