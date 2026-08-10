#!/bin/bash
#
# Thin root-installed entry point for the staging auto-deploy
# (systemd: connex-staging-deploy.timer -> connex-staging-deploy.service, User=dev).
#
# Its only jobs are to serialize runs, fetch the candidate script without changing the
# live checkout, and hand off to that reviewed script. The deploy script resets the
# checkout only after complete target artifacts exist and the frontend is quiesced.
#
# Install (as root):
#   install -m 0755 /opt/connex-staging/deploy/staging/connex-staging-deploy-wrapper.sh \
#       /usr/local/bin/connex-staging-deploy

set -euo pipefail

STAGING_DIR=/opt/connex-staging
LOG_TAG="connex-staging-deploy"

exec 9>/tmp/connex-staging-deploy.lock
flock -n 9 || { echo "[$LOG_TAG] Deploy already in progress, skipping"; exit 0; }

cd "$STAGING_DIR"
git fetch origin main --quiet

DEPLOY_SCRIPT="$(mktemp /tmp/connex-staging-deploy.XXXXXX)"
trap 'rm -f "$DEPLOY_SCRIPT"' EXIT
CANDIDATE_SHA="$(git rev-parse origin/main)"
git show "$CANDIDATE_SHA:deploy/staging/connex-staging-deploy.sh" > "$DEPLOY_SCRIPT"

export CONNEX_DEPLOY_LOCK_HELD=1
export CONNEX_DEPLOY_TARGET="$CANDIDATE_SHA"
bash "$DEPLOY_SCRIPT"
