ALTER TABLE provider_connection
    DROP CONSTRAINT chk_provider_connection_status,
    DROP COLUMN last_sync_at,
    ADD CONSTRAINT chk_provider_connection_status CHECK (
        status IN (
            'connected', 'paused', 'error', 'revoked', 'revoking', 'disconnected',
            'disconnecting', 'purge_failed'
        )
    ),
    ADD CONSTRAINT chk_provider_connection_disconnected_credential CHECK (
        status <> 'disconnected'
        OR (
            credential_ref IS NULL
            AND access_token_expires_at IS NULL
            AND refresh_lease_owner IS NULL
            AND refresh_lease_until IS NULL
        )
    );

ALTER TABLE provider_native_connect_session
    ADD COLUMN expected_connection_id INT NULL AFTER redirect_uri,
    ADD COLUMN expected_credential_generation BIGINT NULL AFTER expected_connection_id,
    ADD CONSTRAINT chk_provider_native_session_connection_expectation CHECK (
        (
            expected_connection_id IS NULL
            AND expected_credential_generation IS NULL
        )
        OR (
            expected_connection_id IS NOT NULL
            AND expected_credential_generation IS NOT NULL
            AND expected_connection_id > 0
            AND expected_credential_generation > 0
        )
    );

UPDATE provider_native_connect_session
SET status = 'failed',
    error_code = 'upgrade_invalidated'
WHERE status IN ('pending', 'prepared', 'exchanging');
