-- Browser-facing one-time links exchange the original bearer for a short-lived, purpose-bound
-- server flow. The exchanged marker rejects raw-token replay before the final operation.
ALTER TABLE password_reset_token
    ADD COLUMN exchanged_at DATETIME NULL COMMENT 'First browser exchange timestamp (UTC)' AFTER expires_at,
    ADD COLUMN exchange_owner_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL
        COMMENT 'SHA-256 owner of the browser and server-session exchange' AFTER exchanged_at;

ALTER TABLE registration_verification_token
    ADD COLUMN exchanged_at DATETIME NULL COMMENT 'First browser exchange timestamp (UTC)' AFTER expires_at,
    ADD COLUMN exchange_owner_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL
        COMMENT 'SHA-256 owner of the browser and server-session exchange' AFTER exchanged_at;

ALTER TABLE email_change_token
    ADD COLUMN exchanged_at DATETIME NULL COMMENT 'First browser exchange timestamp (UTC)' AFTER expires_at,
    ADD COLUMN exchange_owner_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL
        COMMENT 'SHA-256 owner of the browser and server-session exchange' AFTER exchanged_at;

-- Existing invite secret records are migrated in place to SHA-256 before the raw column is removed.
ALTER TABLE workspace_invite
    ADD COLUMN token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER role,
    ADD COLUMN exchanged_at DATETIME NULL COMMENT 'First browser exchange timestamp (UTC)' AFTER expires_at,
    ADD COLUMN exchange_owner_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL
        COMMENT 'SHA-256 owner of the browser and server-session exchange' AFTER exchanged_at;

UPDATE workspace_invite SET token_hash = LOWER(SHA2(token, 256));

ALTER TABLE workspace_invite
    DROP INDEX uq_workspace_invite_token,
    MODIFY COLUMN token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ADD UNIQUE KEY uq_workspace_invite_token_hash (token_hash),
    DROP COLUMN token;

-- Shareable links intentionally remain multi-redemption, but their bearer is still stored hashed.
ALTER TABLE workspace_invite_link
    ADD COLUMN token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER workspace_id;

UPDATE workspace_invite_link SET token_hash = LOWER(SHA2(token, 256));

ALTER TABLE workspace_invite_link
    DROP INDEX uq_workspace_invite_link_token,
    MODIFY COLUMN token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ADD UNIQUE KEY uq_workspace_invite_link_token_hash (token_hash),
    DROP COLUMN token;

-- The CSRF bootstrap binds a private browser cookie to a server-session lineage. Durable flow rows
-- avoid request-local Spring Session write races and serialize redemption across backend replicas.
CREATE TABLE one_time_link_flow (
    grant_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    exchange_owner_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    purpose VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    expires_at DATETIME NOT NULL,
    consume_claim_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    consume_claimed_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (grant_hash),
    KEY ix_one_time_link_flow_expiry (expires_at),
    KEY ix_one_time_link_flow_owner (exchange_owner_hash, purpose)
) ENGINE=InnoDB;
