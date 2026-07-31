ALTER TABLE app_user
    ADD COLUMN account_deletion_reserved_at DATETIME(6) NULL AFTER last_login_at,
    ADD COLUMN account_deletion_reservation_owner CHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NULL AFTER account_deletion_reserved_at,
    ADD COLUMN account_deletion_reservation_until DATETIME(6) NULL
        AFTER account_deletion_reservation_owner,
    ADD CONSTRAINT chk_app_user_account_deletion_reservation CHECK (
        (
            account_deletion_reserved_at IS NULL
            AND account_deletion_reservation_owner IS NULL
            AND account_deletion_reservation_until IS NULL
        )
        OR (
            account_deletion_reserved_at IS NOT NULL
            AND account_deletion_reservation_owner IS NOT NULL
            AND account_deletion_reservation_until IS NOT NULL
        )
    ),
    ADD KEY idx_app_user_account_deletion_reserved (
        account_deletion_reservation_until, id
    );

ALTER TABLE provider_connection
    DROP CONSTRAINT chk_provider_connection_status,
    ADD COLUMN provider_account_id VARCHAR(255)
        CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL AFTER provider_account_email,
    ADD COLUMN credential_generation BIGINT NOT NULL DEFAULT 1 AFTER credential_ref,
    ADD COLUMN access_token_expires_at DATETIME(6) NULL AFTER credential_generation,
    ADD COLUMN refresh_lease_owner CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL
        AFTER access_token_expires_at,
    ADD COLUMN refresh_lease_until DATETIME(6) NULL AFTER refresh_lease_owner,
    ADD COLUMN disconnecting_at DATETIME(6) NULL AFTER refresh_lease_until,
    ADD COLUMN disconnect_attempt_at DATETIME(6) NULL AFTER disconnecting_at,
    ADD COLUMN capture_reconcile_required BOOLEAN NOT NULL DEFAULT FALSE
        AFTER disconnect_attempt_at,
    ADD COLUMN capture_reconcile_after_workspace_id INT NOT NULL DEFAULT 0
        AFTER capture_reconcile_required,
    ADD COLUMN capture_reconcile_lease_owner CHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NULL
        AFTER capture_reconcile_after_workspace_id,
    ADD COLUMN capture_reconcile_lease_until DATETIME(6) NULL
        AFTER capture_reconcile_lease_owner,
    ADD COLUMN capture_reconcile_next_attempt_at DATETIME(6) NULL
        AFTER capture_reconcile_lease_until,
    ADD COLUMN capture_reconcile_failures SMALLINT UNSIGNED NOT NULL DEFAULT 0
        AFTER capture_reconcile_next_attempt_at,
    ADD CONSTRAINT chk_provider_connection_status CHECK (
        status IN ('connected', 'paused', 'error', 'revoked', 'disconnecting', 'purge_failed')
    ),
    ADD CONSTRAINT chk_provider_connection_generation CHECK (credential_generation > 0),
    ADD CONSTRAINT chk_provider_connection_refresh_lease CHECK (
        (refresh_lease_owner IS NULL AND refresh_lease_until IS NULL)
        OR (refresh_lease_owner IS NOT NULL AND refresh_lease_until IS NOT NULL)
    ),
    ADD CONSTRAINT chk_provider_connection_capture_reconcile_cursor CHECK (
        capture_reconcile_after_workspace_id >= 0
    ),
    ADD CONSTRAINT chk_provider_connection_capture_reconcile_lease CHECK (
        (
            capture_reconcile_lease_owner IS NULL
            AND capture_reconcile_lease_until IS NULL
        )
        OR (
            capture_reconcile_lease_owner IS NOT NULL
            AND capture_reconcile_lease_until IS NOT NULL
        )
    ),
    ADD KEY idx_provider_connection_capture_work (
        status, disconnecting_at, refresh_lease_until, id
    ),
    ADD KEY idx_provider_connection_capture_reconcile (
        capture_reconcile_required, capture_reconcile_next_attempt_at,
        capture_reconcile_lease_until, id
    );

UPDATE provider_connection
SET status = 'revoked',
    error_code = 'account_identity_unverified'
WHERE provider_account_id IS NULL;
