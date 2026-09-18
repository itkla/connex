# syntax=docker/dockerfile:1.24.0@sha256:87999aa3d42bdc6bea60565083ee17e86d1f3339802f543c0d03998580f9cb89

ARG BUILD_TIME
ARG SOURCE_DATE_EPOCH

# Same digest-pinned base as ocr/, deliberately: the supervisor and HTTP front are stdlib-only
# Python, so this image needs no pip dependencies and therefore no requirements lock at all.
# ClamAV itself comes from Debian's packages, matching ocr/'s unpinned-apt precedent.
FROM python:3.14-slim-bookworm@sha256:9ab8d9c8514b44f90cf0029dd42fdd7e9e211e639c8b995304cc04568dee900f

ARG VERSION=0.0.0-dev
LABEL org.opencontainers.image.title="connex-clamav" \
      org.opencontainers.image.source="https://github.com/itkla/connex" \
      org.opencontainers.image.version="${VERSION}"

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    HOME=/opt/connex-clamav \
    DEBIAN_FRONTEND=noninteractive

WORKDIR /opt/connex-clamav/app

# clamav supplies sigtool (used to verify each baked container's digital signature),
# clamav-daemon supplies clamd, clamav-freshclam supplies the build-time updater.
# The Debian postinst tries to start services that do not exist in a container; that is harmless
# under a non-systemd PID 1, but the update it schedules is replaced by our own pinned config.
RUN apt-get update \
    && apt-get install --no-install-recommends -y clamav clamav-daemon clamav-freshclam \
    && rm -rf /var/lib/apt/lists/* /etc/cron.d/clamav-freshclam \
    && rm -f /etc/clamav/clamd.conf /etc/clamav/freshclam.conf

COPY clamd.conf freshclam.conf /etc/clamav/
COPY clamav_service ./clamav_service

# Bake a verified baseline signature set. The build FAILS if any container is missing or fails
# its digital-signature check, so no image can ship without working signatures. Nothing at
# runtime re-downloads: the scanning network is internal-only. See docs/MALWARE_SCANNING.md for
# how operators update signatures after deployment, including the air-gapped procedure.
RUN python -m clamav_service.prefetch \
    && chmod -R a-w /var/lib/clamav

RUN useradd --system --uid 10002 --home-dir /opt/connex-clamav --shell /usr/sbin/nologin connex-clamav \
    && chown -R root:root /opt/connex-clamav /etc/clamav \
    && chmod -R a-w /opt/connex-clamav /etc/clamav

USER 10002

# Only the authenticated HTTP front is exposed. clamd itself listens on a unix socket inside the
# container's private tmpfs and has no TCP listener at all -- publishing 3310 would expose
# clamd's path-taking SCAN commands as an unauthenticated alternate surface.
EXPOSE 8091

HEALTHCHECK --interval=15s --timeout=5s --start-period=180s --retries=4 CMD ["python", "-c", "import json,urllib.request; data=json.load(urllib.request.urlopen('http://127.0.0.1:8091/health',timeout=3)); raise SystemExit(0 if data.get('ready') is True else 1)"]

ENTRYPOINT ["python", "-m", "clamav_service.supervisor"]
