#!/bin/bash
#
# Thin root-installed entry point for the staging auto-deploy
# (systemd: connex-staging-deploy.timer -> connex-staging-deploy.service, User=dev).
#
# Its only jobs are to serialize runs, fast-forward the checkout, and hand off to the
# reviewed, in-repo deploy script — so the deploy logic itself is versioned and the
# freshly-reset script file is exec'd from its start rather than mutated mid-run.
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
git reset --hard origin/main --quiet

exec bash "$STAGING_DIR/deploy/staging/connex-staging-deploy.sh"
