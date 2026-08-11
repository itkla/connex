#!/usr/bin/env bash

set -euo pipefail

EXPECTED_SHA="${1:?expected release commit is required}"
TAG_NAME="${2:?release tag is required}"
RELEASE_MODE="${3:?release mode is required}"
: "${GH_REPO:?GH_REPO must identify the publication repository}"
: "${CONNEX_RELEASE_ADMIN_TOKEN:?CONNEX_RELEASE_ADMIN_TOKEN must provide repository administration read access}"

[[ "$EXPECTED_SHA" =~ ^[0-9a-f]{40}$ ]]
[[ "$RELEASE_MODE" = publish || "$RELEASE_MODE" = dry-run ]]
[[ "$TAG_NAME" =~ ^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-((0|[1-9][0-9]*|[0-9]*[A-Za-z-][0-9A-Za-z-]*)(\.(0|[1-9][0-9]*|[0-9]*[A-Za-z-][0-9A-Za-z-]*))*))?$ ]]
VERSION="${TAG_NAME#v}"
if (( ${#VERSION} > 128 )); then
  echo "::error::Release version must be 128 characters or fewer to form a valid container tag (received ${#VERSION})."
  exit 1
fi

if [ "$RELEASE_MODE" = publish ]; then
  REMOTE_REF="refs/connex-release/${GITHUB_RUN_ID:-local}-${GITHUB_RUN_ATTEMPT:-0}"
  git fetch --force --no-tags origin "+refs/tags/${TAG_NAME}:${REMOTE_REF}"
  test "$(git rev-parse "${REMOTE_REF}^{commit}")" = "$EXPECTED_SHA"
fi
GH_TOKEN="$CONNEX_RELEASE_ADMIN_TOKEN" gh api \
  -H 'Accept: application/vnd.github+json' \
  -H 'X-GitHub-Api-Version: 2026-03-10' \
  "repos/${GH_REPO}/immutable-releases" \
  | jq -e '.enabled == true' >/dev/null

if [ "$RELEASE_MODE" = dry-run ]; then
  if RELEASE_RESPONSE="$(GH_TOKEN="$CONNEX_RELEASE_ADMIN_TOKEN" gh api --include \
      -H 'Accept: application/vnd.github+json' \
      -H 'X-GitHub-Api-Version: 2026-03-10' \
      "repos/${GH_REPO}/releases/tags/${TAG_NAME}" 2>&1)"; then
    echo "::error::Release ${TAG_NAME} is already published."
    exit 1
  fi
  RELEASE_STATUS="$(awk 'toupper($1) ~ /^HTTP\/[0-9.]+$/ { status=$2 } END { print status }' <<<"$RELEASE_RESPONSE")"
  if [ "$RELEASE_STATUS" != 404 ]; then
    printf '%s\n' "$RELEASE_RESPONSE" >&2
    echo "::error::Could not confirm that release ${TAG_NAME} is available (HTTP status: ${RELEASE_STATUS:-unknown})."
    exit 1
  fi
fi
