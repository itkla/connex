ALTER TABLE provider_connection
    DROP CONSTRAINT chk_provider_connection_status,
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
