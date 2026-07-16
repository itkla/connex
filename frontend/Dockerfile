# syntax=docker/dockerfile:1

ARG BUILD_TIME
ARG SOURCE_DATE_EPOCH

FROM node:22-alpine@sha256:16e22a550f3863206a3f701448c45f7912c6896a62de43add43bb9c86130c3e2 AS base
ENV PNPM_HOME="/pnpm"
ENV PATH="$PNPM_HOME:$PATH"
RUN corepack enable
WORKDIR /app

FROM base AS deps
COPY package.json pnpm-lock.yaml .pnpmfile.cjs ./
RUN --mount=type=cache,target=/pnpm/store \
    pnpm install --frozen-lockfile --ignore-scripts

FROM base AS build
ARG VERSION=0.0.0-dev
ARG BACKEND_URL=http://backend:8080
ENV NEXT_TELEMETRY_DISABLED=1
ENV NEXT_PUBLIC_APP_VERSION=${VERSION}
ENV BACKEND_URL=${BACKEND_URL}
COPY --from=deps /app/node_modules ./node_modules
COPY . .
RUN pnpm build

FROM node:22-alpine@sha256:16e22a550f3863206a3f701448c45f7912c6896a62de43add43bb9c86130c3e2 AS runtime
WORKDIR /app
ARG VERSION=0.0.0-dev
LABEL org.opencontainers.image.title="connex-frontend" \
      org.opencontainers.image.source="https://github.com/itkla/connex" \
      org.opencontainers.image.version="${VERSION}"
ENV NODE_ENV=production
ENV NEXT_TELEMETRY_DISABLED=1
ENV PORT=3000
ENV HOSTNAME=0.0.0.0
ENV API_URL=http://backend:8080
RUN addgroup --system --gid 1001 nodejs && adduser --system --uid 1001 nextjs
COPY --from=build /app/public ./public
COPY --from=build --chown=nextjs:nodejs /app/.next/standalone ./
COPY --from=build --chown=nextjs:nodejs /app/.next/static ./.next/static
USER nextjs
EXPOSE 3000
CMD ["node", "server.js"]
