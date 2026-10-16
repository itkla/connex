#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../../.." && pwd)"
MISSION_TEST_DIR="$(mktemp -d)"
trap 'rm -rf -- "$MISSION_TEST_DIR"' EXIT
STAGING_DIR="$MISSION_TEST_DIR"
RELEASES_DIR="$MISSION_TEST_DIR/releases"
DEPLOY_TARGET=compatible

# Load only the production archive/floor functions; never execute the deployment entry point.
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
assert floor == ["tenant/V231__ai_agent_runs.sql"]
for name, migration in (("compatible", floor[0]), ("pre-contract", "tenant/V228__drop_campaign_delivery_unsubscribe_tokens.sql")):
    directory = releases / name
    directory.mkdir(parents=True)
    with zipfile.ZipFile(directory / "backend.jar", "w") as archive:
        archive.writestr("BOOT-INF/classes/db/migration/" + migration, "SELECT 1;")
PY

if rollback_floor_allows_release pre-contract; then
    printf '%s\n' 'FAIL: pre-contract binary passed the V231 rollback floor' >&2
    exit 1
fi
rollback_floor_allows_release compatible
printf '%s\n' 'PASS: sliceOneRollbackFloorRejectsPreContractBinary'
