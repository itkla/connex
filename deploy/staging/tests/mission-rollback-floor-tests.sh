#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../../.." && pwd)"
MISSION_TEST_DIR="$(mktemp -d)"
trap 'rm -rf -- "$MISSION_TEST_DIR"' EXIT
export STAGING_DIR="$MISSION_TEST_DIR"
RELEASES_DIR="$MISSION_TEST_DIR/releases"
export DEPLOY_TARGET=compatible

# Load only the production archive/floor functions; never execute the deployment entry point.
# shellcheck source=/dev/null
source <(awk '
  /^(archive_reader_available|read_archive_entry|jar_contains_migration|rollback_floor_allows_release)\(\) \{/ { emit=1 }
  emit { print }
  emit && /^}/ { emit=0 }
' "$REPO_ROOT/deploy/staging/connex-staging-deploy.sh")

# Pin the working-tree declaration without creating commits or contacting a repository remote.
git() {
    case "${3:-}" in
        ls-tree) printf '100644 blob fixture\tdeploy/staging/rollback-floor\n' ;;
        show) cat "$REPO_ROOT/deploy/staging/rollback-floor" ;;
        *) return 1 ;;
    esac
}

python3 - "$REPO_ROOT" "$RELEASES_DIR" <<'PY'
import pathlib
import sys
import zipfile

root, releases = map(pathlib.Path, sys.argv[1:])
floor = [line.strip() for line in (root / "deploy/staging/rollback-floor").read_text().splitlines()
         if line.strip() and not line.lstrip().startswith("#")]
assert floor == ["tenant/V238__ai_agent_singleton_proposals.sql"]
for name, migrations in (
    ("compatible", ["tenant/V232__ai_agent_runs.sql", "tenant/V233__ai_assistant_proposal_set_storage.sql", "tenant/V234__deal_document_recency_index.sql", "tenant/V235__ai_agent_continuation.sql", "control/V236__ai_assistant_privacy_coordination.sql", "tenant/V237__ai_assistant_privacy_cutover.sql", floor[0]]),
    ("expiry-only", ["tenant/V231__ai_proposal_review_expiry_cutover.sql"]),
    ("privacy-control-v236", ["tenant/V235__ai_agent_continuation.sql", "control/V236__ai_assistant_privacy_coordination.sql"]),
    ("privacy-cutover-v237", ["tenant/V235__ai_agent_continuation.sql", "control/V236__ai_assistant_privacy_coordination.sql", "tenant/V237__ai_assistant_privacy_cutover.sql"]),
    ("slice-two-v235", ["tenant/V232__ai_agent_runs.sql", "tenant/V235__ai_agent_continuation.sql"]),
    ("slice-one", ["tenant/V232__ai_agent_runs.sql"]),
    ("slice-one-v233", ["tenant/V232__ai_agent_runs.sql", "tenant/V233__ai_assistant_proposal_set_storage.sql"]),
    ("slice-one-v234", ["tenant/V232__ai_agent_runs.sql", "tenant/V233__ai_assistant_proposal_set_storage.sql", "tenant/V234__deal_document_recency_index.sql"]),
):
    directory = releases / name
    directory.mkdir(parents=True)
    with zipfile.ZipFile(directory / "backend.jar", "w") as archive:
        for migration in migrations:
            archive.writestr("BOOT-INF/classes/db/migration/" + migration, "SELECT 1;")
PY

for predecessor in expiry-only slice-one slice-one-v233 slice-one-v234 slice-two-v235 privacy-control-v236 privacy-cutover-v237; do
    if rollback_floor_allows_release "$predecessor"; then
        printf '%s\n' "FAIL: $predecessor binary passed the V238 rollback floor" >&2
        exit 1
    fi
done
rollback_floor_allows_release compatible
printf '%s\n' 'PASS: singletonProposalRollbackFloorRejectsOlderBinaries'
