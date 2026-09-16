#!/bin/bash
#
# Idempotently installs the Connex on-prem database backup package, renders
# systemd path and calendar drop-ins from backup.env, and enables all backup
# timers. Docker images remain operator-managed release prerequisites.
# shellcheck source=deploy/backup/connex-backup-lib.sh

set -euo pipefail

umask 077

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/connex-backup-lib.sh"

INSTALL_ROOT=/usr/local/lib/connex-backup
CONFIG_ROOT=/etc/connex-backup
SYSTEMD_ROOT=/etc/systemd/system

install_require_root() {
    if [ "$(id -u)" -ne 0 ]; then
        printf 'install.sh must run as root\n' >&2
        return "$EXIT_CONFIG"
    fi
}

install_migrate_database_network() {
    local config_file="$CONFIG_ROOT/backup.env"
    local temporary_file
    if ! grep -Eq '^CONNEX_BACKUP_DOCKER_NETWORK=[a-z0-9][a-z0-9_-]*_default$' "$config_file"; then
        return 0
    fi
    temporary_file="$(mktemp "$CONFIG_ROOT/.backup.env.XXXXXX")"
    if ! sed -E \
        's/^CONNEX_BACKUP_DOCKER_NETWORK=[a-z0-9][a-z0-9_-]*_default$/CONNEX_BACKUP_DOCKER_NETWORK=auto/' \
        "$config_file" > "$temporary_file"; then
        rm -f "$temporary_file"
        return "$EXIT_CONFIG"
    fi
    chmod 0600 "$temporary_file"
    mv "$temporary_file" "$config_file"
    printf 'Migrated legacy backup Docker network to automatic Compose db-network discovery.\n'
}

install_configuration() {
    install -d -m 0700 "$CONFIG_ROOT"
    if [ ! -e "$CONFIG_ROOT/backup.env" ]; then
        install -m 0600 "$SCRIPT_DIR/backup.env.example" "$CONFIG_ROOT/backup.env"
    else
        chmod 0600 "$CONFIG_ROOT/backup.env"
        install_migrate_database_network
    fi
    CONNEX_BACKUP_ENV_FILE="$CONFIG_ROOT/backup.env"
    export CONNEX_BACKUP_ENV_FILE
    backup_load_environment
    backup_set_defaults
    backup_validate_absolute_path CONNEX_BACKUP_ROOT "$CONNEX_BACKUP_ROOT"
    backup_validate_absolute_path CONNEX_BACKUP_LOCK_DIR "$CONNEX_BACKUP_LOCK_DIR"
    backup_validate_integer CONNEX_BACKUP_RETENTION_DAYS "$CONNEX_BACKUP_RETENTION_DAYS" 1 30
    install_resolve_lock_directory
}

install_defaults_have_ca() {
    awk '
        function trim(value) {
            gsub(/^[[:space:]]+|[[:space:]]+$/, "", value)
            return value
        }
        {
            line = trim($0)
            if (line ~ /^\[/) {
                client = (line ~ /^\[client\]([[:space:]]*[#;].*)?$/)
                next
            }
            if (!client || line !~ /^ssl[-_](ca|capath)[[:space:]]*=/) next
            key = line
            sub(/[[:space:]]*=.*/, "", key)
            gsub(/_/, "-", key)
            value = substr(line, index(line, "=") + 1)
            sub(/[[:space:]]+#.*/, "", value)
            value = trim(value)
            if (value ~ /^".*"$/ || value ~ /^\047.*\047$/) value = substr(value, 2, length(value) - 2)
            if (key == "ssl-ca") ca = (value ~ /^\/[^[:space:]]/)
            if (key == "ssl-capath") capath = (value ~ /^\/[^[:space:]]/)
        }
        END { exit !(ca || capath) }
    ' "$1"
}

install_tls_migration_message() {
    local profile="$1" defaults_file="$2"
    printf 'Backup TLS configuration required for the %s profile before enabling timers.\n' "$profile" >&2
    printf 'Configure a trusted absolute ssl-ca or ssl-capath in [client] of %s (mode 0600), with a server certificate matching the configured host.\n' "$defaults_file" >&2
    printf 'Only for localhost, 127.0.0.1 or ::1, explicitly set CONNEX_BACKUP_%s_ALLOW_LOOPBACK_PLAINTEXT=true in %s; remote hosts require this setting to be false and CA-backed TLS.\n' "${profile^^}" "$CONNEX_BACKUP_ENV_FILE" >&2
    printf 'CA paths must exist inside the DB container for exec mode or be mounted read-only for run mode. Correct this profile and rerun install.sh.\n' >&2
}

install_validate_upgrade_configuration() {
    local profile host port user defaults_file ssl_mode
    local image="$CONNEX_BACKUP_DOCKER_BINLOG_IMAGE"
    if [ -n "$image" ] && [[ ! "$image" =~ ^[a-zA-Z0-9][a-zA-Z0-9./:_-]*@sha256:[a-f0-9]{64}$ ]]; then
        printf 'Backup PITR image migration required before enabling timers: replace CONNEX_BACKUP_DOCKER_BINLOG_IMAGE in %s with an independently approved immutable digest.\n' "$CONNEX_BACKUP_ENV_FILE" >&2
        printf 'Use the format documented in docs/BACKUP_RESTORE.md: CONNEX_BACKUP_DOCKER_BINLOG_IMAGE=percona/percona-server@sha256:<64 lowercase hex digits>.\n' >&2
        printf 'Substitute the release-owner-approved digest, stage that image, then rerun install.sh. Mutable tags such as percona/percona-server:8.4 remain refused at runtime.\n' >&2
        return "$EXIT_CONFIG"
    fi
    for profile in source verify restore; do
        IFS=$'\t' read -r host port user defaults_file < <(backup_profile_values "$profile")
        if ! backup_validate_defaults_file "${profile}_defaults_file" "$defaults_file" >&2 ||
            ! ssl_mode="$(backup_tls_mode "$profile" "$host")"; then
            install_tls_migration_message "$profile" "$defaults_file"
            return "$EXIT_CONFIG"
        fi
        if [ "$ssl_mode" != DISABLED ] && ! install_defaults_have_ca "$defaults_file"; then
            install_tls_migration_message "$profile" "$defaults_file"
            return "$EXIT_CONFIG"
        fi
    done
}

# A lock directory under the volatile /run must be declared as a systemd
# RuntimeDirectory: ProtectSystem=strict resolves ReadWritePaths before
# ExecStart, so a path that does not survive reboot fails namespace setup
# before any script can create it. Directories elsewhere are persistent and are
# created here instead.
install_resolve_lock_directory() {
    case "$CONNEX_BACKUP_LOCK_DIR" in
        /run/*)
            INSTALL_RUNTIME_DIRECTORY="${CONNEX_BACKUP_LOCK_DIR#/run/}"
            INSTALL_LOCK_READ_WRITE_PATH=""
            ;;
        /run)
            printf 'CONNEX_BACKUP_LOCK_DIR must be a directory under /run, not /run itself\n' >&2
            return "$EXIT_CONFIG"
            ;;
        *)
            INSTALL_RUNTIME_DIRECTORY=""
            INSTALL_LOCK_READ_WRITE_PATH=" $CONNEX_BACKUP_LOCK_DIR"
            install -d -m 0700 "$CONNEX_BACKUP_LOCK_DIR"
            ;;
    esac
}

install_programs() {
    install -d -m 0755 "$INSTALL_ROOT" "$INSTALL_ROOT/shims"
    install -m 0644 "$SCRIPT_DIR/connex-backup-lib.sh" "$INSTALL_ROOT/connex-backup-lib.sh"
    install -m 0755 \
        "$SCRIPT_DIR/connex-backup-full.sh" \
        "$SCRIPT_DIR/connex-binlog-archive.sh" \
        "$SCRIPT_DIR/connex-backup-prune.sh" \
        "$SCRIPT_DIR/connex-restore-full.sh" \
        "$SCRIPT_DIR/connex-restore-pitr.sh" \
        "$INSTALL_ROOT/"
    install -m 0644 "$SCRIPT_DIR/shims/docker-client-lib.sh" "$INSTALL_ROOT/shims/docker-client-lib.sh"
    install -m 0755 \
        "$SCRIPT_DIR/shims/mysql" \
        "$SCRIPT_DIR/shims/mysqldump" \
        "$SCRIPT_DIR/shims/mysqlbinlog" \
        "$INSTALL_ROOT/shims/"
}

install_units() {
    local unit
    for unit in \
        connex-backup.service \
        connex-backup.timer \
        connex-binlog-archive.service \
        connex-binlog-archive.timer \
        connex-backup-prune.service \
        connex-backup-prune.timer; do
        install -m 0644 "$SCRIPT_DIR/systemd/$unit" "$SYSTEMD_ROOT/$unit"
    done
}

install_render_service_dropin() {
    local service="$1"
    local directory="$SYSTEMD_ROOT/$service.d"
    local source_defaults_dir verify_defaults_dir restore_defaults_dir
    source_defaults_dir="$(dirname "$CONNEX_BACKUP_SOURCE_DEFAULTS_FILE")"
    verify_defaults_dir="$(dirname "$CONNEX_BACKUP_VERIFY_DEFAULTS_FILE")"
    restore_defaults_dir="$(dirname "$CONNEX_BACKUP_RESTORE_DEFAULTS_FILE")"
    backup_validate_absolute_path source_defaults_dir "$source_defaults_dir"
    backup_validate_absolute_path verify_defaults_dir "$verify_defaults_dir"
    backup_validate_absolute_path restore_defaults_dir "$restore_defaults_dir"
    install -d -m 0755 "$directory"
    {
        printf '[Service]\n'
        printf 'RuntimeDirectory=\n'
        if [ -n "$INSTALL_RUNTIME_DIRECTORY" ]; then
            printf 'RuntimeDirectory=%s\n' "$INSTALL_RUNTIME_DIRECTORY"
            printf 'RuntimeDirectoryMode=0700\n'
            printf 'RuntimeDirectoryPreserve=yes\n'
        fi
        printf 'ReadWritePaths=\n'
        printf 'ReadWritePaths=%s%s /var/run/docker.sock\n' \
            "$CONNEX_BACKUP_ROOT" "$INSTALL_LOCK_READ_WRITE_PATH"
        printf 'ReadOnlyPaths=\n'
        printf 'ReadOnlyPaths=%s %s %s\n' "$source_defaults_dir" "$verify_defaults_dir" "$restore_defaults_dir"
    } > "$directory/50-connex-backup-paths.conf"
    chmod 0644 "$directory/50-connex-backup-paths.conf"
}

install_render_timer_dropin() {
    local timer="$1"
    local calendar="$2"
    local directory="$SYSTEMD_ROOT/$timer.d"
    if [ -z "$calendar" ] || [[ "$calendar" == *$'\n'* ]]; then
        return "$EXIT_CONFIG"
    fi
    install -d -m 0755 "$directory"
    {
        printf '[Timer]\n'
        printf 'OnCalendar=\n'
        printf 'OnCalendar=%s\n' "$calendar"
    } > "$directory/50-connex-backup-calendar.conf"
    chmod 0644 "$directory/50-connex-backup-calendar.conf"
}

install_render_dropins() {
    install_render_service_dropin connex-backup.service
    install_render_service_dropin connex-binlog-archive.service
    install_render_service_dropin connex-backup-prune.service
    install_render_timer_dropin connex-backup.timer "$CONNEX_BACKUP_FULL_CALENDAR"
    install_render_timer_dropin connex-binlog-archive.timer "$CONNEX_BACKUP_BINLOG_CALENDAR"
    install_render_timer_dropin connex-backup-prune.timer "$CONNEX_BACKUP_PRUNE_CALENDAR"
}

install_runtime_directories() {
    install -d -m 0700 "$CONNEX_BACKUP_ROOT"
    install -d -m 0700 "$CONNEX_BACKUP_ROOT/full" "$CONNEX_BACKUP_ROOT/binlog"
}

install_enable_timers() {
    systemctl daemon-reload
    systemctl enable --now connex-backup.timer connex-binlog-archive.timer connex-backup-prune.timer
}

main() {
    install_require_root
    install_configuration
    install_validate_upgrade_configuration
    install_programs
    install_units
    install_render_dropins
    install_runtime_directories
    install_enable_timers
    printf 'Connex backup tooling installed using %s. Verify a full backup, binlog archive, and PITR drill.\n' "$CONFIG_ROOT/backup.env"
}

main "$@"
