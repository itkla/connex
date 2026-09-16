#!/bin/bash
# Runs the real decoder launcher against a disposable, locally cached image.
# The Docker adapter substitutes a hostile shell probe for mysqlbinlog while
# preserving the launcher's image, network, filesystem, and mount arguments.
# CONNEX_BACKUP_ISOLATION_IMAGE must be a trusted digest-pinned image with sh.

set -euo pipefail
umask 077

TESTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKUP_DIR="$(cd "$TESTS_DIR/.." && pwd)"
IMAGE="${CONNEX_BACKUP_ISOLATION_IMAGE:?Set a locally cached IMAGE@sha256:digest containing sh}"
[[ "$IMAGE" =~ ^[a-zA-Z0-9][a-zA-Z0-9./:_-]*@sha256:[a-f0-9]{64}$ ]] || exit 64
export ISOLATION_DOCKER
ISOLATION_DOCKER="$(command -v docker)"
"$ISOLATION_DOCKER" image inspect "$IMAGE" >/dev/null
TEST_PARENT="${CONNEX_BACKUP_TEST_ROOT:-$PWD/.lane-tests/isolation}"
mkdir -p "$TEST_PARENT"
SANDBOX="$(mktemp -d "$TEST_PARENT/run.XXXXXX")"
trap 'rm -rf "$SANDBOX"' EXIT
mkdir -p "$SANDBOX/backups" "$SANDBOX/credentials"
printf '\xfebinfixture\n' > "$SANDBOX/backups/mysql-bin.000001"
printf 'retained\n' > "$SANDBOX/backups/sentinel"
printf '[client]\npassword=synthetic-secret\n' > "$SANDBOX/credentials/source.cnf"
export ISOLATION_INPUT="$SANDBOX/backups/mysql-bin.000001"
export ISOLATION_SENTINEL="$SANDBOX/backups/sentinel"
export ISOLATION_CREDENTIALS="$SANDBOX/credentials/source.cnf"
INPUT_HASH="$(sha256sum "$ISOLATION_INPUT")"

cat > "$SANDBOX/docker-probe" <<'EOF'
#!/bin/bash
set -euo pipefail
args=()
while [ "$#" -gt 0 ] && [ "$1" != --entrypoint ]; do
    args+=("$1")
    shift
done
[ "${1:-}" = --entrypoint ] && [ "${2:-}" = mysqlbinlog ] || exit 1
image="$3"
exec "$ISOLATION_DOCKER" "${args[@]}" --pull=never --entrypoint sh "$image" -c '
    set -eu
    test -r "$1"
    test ! -e "$2"
    test ! -e "$3"
    if cat "$2" >/dev/null 2>&1; then exit 1; fi
    if (printf overwritten > "$1") 2>/dev/null; then exit 1; fi
    if (printf overwritten > "$3") 2>/dev/null; then exit 1; fi
' sh "$ISOLATION_INPUT" "$ISOLATION_CREDENTIALS" "$ISOLATION_SENTINEL"
EOF
chmod 0700 "$SANDBOX/docker-probe"
cat > "$SANDBOX/backup.env" <<EOF
CONNEX_BACKUP_DOCKER_BINLOG_IMAGE=$IMAGE
CONNEX_BACKUP_DOCKER_BIN=$SANDBOX/docker-probe
CONNEX_BACKUP_ROOT=$SANDBOX/backups
CONNEX_BACKUP_DEFAULTS_DIR=$SANDBOX/credentials
CONNEX_BACKUP_DOCKER_MOUNTS=$SANDBOX/credentials:/credentials
EOF
CONNEX_BACKUP_ENV_FILE="$SANDBOX/backup.env" \
    bash "$BACKUP_DIR/shims/mysqlbinlog" --verify-binlog-checksum "$ISOLATION_INPUT"
[ "$INPUT_HASH" = "$(sha256sum "$ISOLATION_INPUT")" ]
[ "$(cat "$ISOLATION_SENTINEL")" = retained ]
printf 'ok   disposable_binlog_container_cannot_read_credentials_or_modify_backups\n'
