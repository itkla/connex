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
    install_programs
    install_units
    install_render_dropins
    install_runtime_directories
    install_enable_timers
    printf 'Connex backup tooling installed. Configure %s and its mode-0600 MySQL defaults files, then rerun this installer.\n' "$CONFIG_ROOT/backup.env"
}

main "$@"
