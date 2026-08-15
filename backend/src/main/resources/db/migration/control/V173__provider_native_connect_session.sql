-- Single-use native (loopback + PKCE) authorization sessions for the Connex-managed connected-account
-- flow (#60). A row is created by an authenticated user in the browser, claimed exactly once by the
-- local helper process, and consumed exactly once when the helper hands the authorization code back.
-- The PKCE verifier never leaves the backend, so a local process that races the loopback port and
-- steals the code still cannot exchange it.
CREATE TABLE provider_native_connect_session (
    id                   INT AUTO_INCREMENT PRIMARY KEY,
    user_id              INT NOT NULL COMMENT 'Owning user; sessions are self-scoped and never shared',
    provider             VARCHAR(16) NOT NULL,
    status               VARCHAR(16) NOT NULL DEFAULT 'pending',
    pairing_code_hash    BINARY(32) NOT NULL COMMENT 'SHA-256 of the single-use pairing code shown in the browser',
    handoff_ticket_hash  BINARY(32) NULL COMMENT 'SHA-256 of the single-use ticket issued to the helper at prepare',
    state_hash           BINARY(32) NULL COMMENT 'SHA-256 of the OAuth state echoed back through the loopback callback',
    verifier_ref         VARCHAR(255) NULL COMMENT 'Opaque secret-store reference to the PKCE verifier; never plaintext',
    redirect_uri         VARCHAR(255) NULL COMMENT 'Loopback callback the helper bound; validated to 127.0.0.1/[::1] only',
    error_code           VARCHAR(64) NULL,
    created_at           DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at           DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    expires_at           DATETIME(6) NOT NULL,
    CONSTRAINT fk_provider_native_session_user FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE,
    CONSTRAINT chk_provider_native_session_provider CHECK (provider IN ('google', 'microsoft')),
    CONSTRAINT chk_provider_native_session_status
        CHECK (status IN ('pending', 'prepared', 'exchanging', 'completed', 'failed')),
    UNIQUE KEY uq_provider_native_session_pairing (pairing_code_hash),
    UNIQUE KEY uq_provider_native_session_ticket (handoff_ticket_hash),
    KEY idx_provider_native_session_user_provider (user_id, provider),
    KEY idx_provider_native_session_expiry (expires_at)
) DEFAULT CHARSET=utf8mb4 COMMENT='Single-use native/PKCE authorization sessions for Connex-managed connected accounts';
