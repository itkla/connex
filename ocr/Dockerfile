# syntax=docker/dockerfile:1.24.0@sha256:87999aa3d42bdc6bea60565083ee17e86d1f3339802f543c0d03998580f9cb89

ARG BUILD_TIME
ARG SOURCE_DATE_EPOCH

FROM python:3.14-slim-bookworm@sha256:86f975aca15cf04a40b399eebede9aea7c82eae084d1f1a0a6ef6bcaae871a30

ARG VERSION=0.0.0-dev
LABEL org.opencontainers.image.title="connex-ocr" \
      org.opencontainers.image.source="https://github.com/itkla/connex" \
      org.opencontainers.image.version="${VERSION}"

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    HOME=/opt/connex-ocr \
    PADDLE_PDX_CACHE_HOME=/tmp/paddlex \
    PADDLE_PDX_DISABLE_MODEL_SOURCE_CHECK=1

WORKDIR /opt/connex-ocr/app

RUN apt-get update \
    && apt-get install --no-install-recommends -y libgl1 libglib2.0-0 libgomp1 \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt requirements.lock ./
RUN pip install --no-cache-dir --require-hashes --requirement requirements.lock

COPY ocr_service ./ocr_service
RUN mkdir -p /opt/connex-ocr/.paddlex \
    && python -m ocr_service.prefetch \
    && chmod -R a-w /opt/connex-ocr/.paddlex

RUN useradd --system --uid 10001 --home-dir /opt/connex-ocr --shell /usr/sbin/nologin connex-ocr \
    && chown -R root:root /opt/connex-ocr \
    && chmod -R a-w /opt/connex-ocr

USER 10001
EXPOSE 8090

HEALTHCHECK --interval=15s --timeout=3s --start-period=180s --retries=4 CMD ["python", "-c", "import json,urllib.request; data=json.load(urllib.request.urlopen('http://127.0.0.1:8090/health',timeout=2)); raise SystemExit(0 if data.get('ready') is True else 1)"]

ENTRYPOINT ["python", "-m", "ocr_service.supervisor"]
