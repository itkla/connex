-- Provider-neutral delivery envelopes for immutable commercial-document versions (#904).
-- Every row is workspace-keyed so the complete aggregate remains tenant-routable and can be
-- exported or torn down without crossing the control/tenant plane wall.
CREATE TABLE document_delivery (
    id                   INT AUTO_INCREMENT PRIMARY KEY,
    workspace_id         INT NOT NULL COMMENT 'Owning workspace',
    deal_id              INT NOT NULL COMMENT 'Parent deal',
    document_id          INT NOT NULL COMMENT 'Immutable document version being delivered',
    provider             VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL
        COMMENT 'Case-sensitive signature provider key; in_app is built in',
    provider_envelope_id VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL
        COMMENT 'Case-sensitive provider-assigned envelope identifier',
    status               VARCHAR(16) NOT NULL DEFAULT 'sent',
    message              VARCHAR(2000) NULL,
    expires_at           DATETIME NULL,
    sent_by              INT NULL COMMENT 'Sending user; validated against workspace membership in the service layer',
    sent_at              DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at         DATETIME NULL,
    terminated_at        DATETIME NULL,
    termination_reason   VARCHAR(500) NULL,
    active_key           INT GENERATED ALWAYS AS (
        CASE WHEN status IN ('sent', 'viewed') THEN document_id ELSE NULL END
    ) STORED COMMENT 'Non-NULL only while the envelope is live, enabling partial uniqueness',
    created_at           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_document_delivery_deal FOREIGN KEY (workspace_id, deal_id)
        REFERENCES deal(workspace_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_document_delivery_document FOREIGN KEY (workspace_id, document_id)
        REFERENCES deal_document(workspace_id, id) ON DELETE CASCADE,
    CONSTRAINT chk_document_delivery_status CHECK (
        status IN ('sent', 'viewed', 'completed', 'declined', 'expired', 'voided')),
    UNIQUE KEY uq_document_delivery_workspace_id (workspace_id, id),
    UNIQUE KEY uq_document_delivery_provider_envelope (
        workspace_id, provider, provider_envelope_id),
    UNIQUE KEY uq_document_delivery_active (workspace_id, active_key),
    INDEX idx_document_delivery_document (workspace_id, document_id, id),
    INDEX idx_document_delivery_expiry (workspace_id, status, expires_at)
) DEFAULT CHARSET=utf8mb4 COMMENT='One provider envelope for one immutable commercial-document version';

-- person_id is deliberately a label hint only: recipients retain the identity captured at send
-- time, delivery SQL never joins person, and APPI restriction handling stays on the person boundary.
-- There is no sent-user foreign key on the parent because app_user is control-plane data.
CREATE TABLE document_delivery_recipient (
    id                    INT AUTO_INCREMENT PRIMARY KEY,
    workspace_id          INT NOT NULL COMMENT 'Owning workspace',
    delivery_id           INT NOT NULL,
    person_id             INT NULL COMMENT 'Optional tenant person association captured at send time',
    name                  VARCHAR(255) NOT NULL,
    email                 VARCHAR(320) NOT NULL,
    role                  VARCHAR(16) NOT NULL,
    recipient_order       INT NOT NULL DEFAULT 1,
    status                VARCHAR(16) NOT NULL DEFAULT 'pending',
    token_hash            CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL
        COMMENT 'SHA-256 of the 256-bit bearer token; raw tokens are never stored',
    token_expires_at      DATETIME NULL,
    provider_recipient_id VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    first_viewed_at       DATETIME NULL,
    decided_at            DATETIME NULL,
    typed_name            VARCHAR(255) NULL,
    decline_reason        VARCHAR(1000) NULL,
    evidence_ip_hash      CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    evidence_agent_hash   CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at            DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_document_delivery_recipient_delivery FOREIGN KEY (workspace_id, delivery_id)
        REFERENCES document_delivery(workspace_id, id) ON DELETE CASCADE,
    CONSTRAINT chk_document_delivery_recipient_role CHECK (role IN ('signer', 'viewer')),
    CONSTRAINT chk_document_delivery_recipient_order CHECK (recipient_order >= 1),
    CONSTRAINT chk_document_delivery_recipient_status CHECK (
        status IN ('pending', 'viewed', 'completed', 'declined', 'expired', 'voided')),
    UNIQUE KEY uq_document_delivery_recipient_workspace_id (workspace_id, id),
    UNIQUE KEY uq_document_delivery_recipient_owner (workspace_id, delivery_id, id),
    UNIQUE KEY uq_document_delivery_recipient_token (workspace_id, token_hash),
    INDEX idx_document_delivery_recipient_delivery (workspace_id, delivery_id, id)
) DEFAULT CHARSET=utf8mb4 COMMENT='Frozen external recipient identity and decision evidence';

-- Append-only provider/actor/recipient/system event ledger. Application code never updates rows;
-- the external-event key makes callback replay idempotent within the resolved envelope.
CREATE TABLE document_delivery_event (
    id                INT AUTO_INCREMENT PRIMARY KEY,
    workspace_id      INT NOT NULL COMMENT 'Owning workspace',
    delivery_id       INT NOT NULL,
    recipient_id      INT NULL,
    event_type        VARCHAR(32) NOT NULL,
    source            VARCHAR(16) NOT NULL,
    external_event_id VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    detail            VARCHAR(500) NULL,
    occurred_at       DATETIME NOT NULL,
    created_at        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_document_delivery_event_delivery FOREIGN KEY (workspace_id, delivery_id)
        REFERENCES document_delivery(workspace_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_document_delivery_event_recipient FOREIGN KEY (
        workspace_id, delivery_id, recipient_id)
        REFERENCES document_delivery_recipient(workspace_id, delivery_id, id) ON DELETE CASCADE,
    CONSTRAINT chk_document_delivery_event_source CHECK (
        source IN ('actor', 'recipient', 'system', 'provider')),
    UNIQUE KEY uq_document_delivery_event_workspace_id (workspace_id, id),
    UNIQUE KEY uq_document_delivery_event_external (
        workspace_id, delivery_id, external_event_id),
    INDEX idx_document_delivery_event_delivery (workspace_id, delivery_id, id)
) DEFAULT CHARSET=utf8mb4 COMMENT='Append-only idempotency and evidence ledger for document delivery';

-- Immutable artifact metadata. Object bytes live in managed private storage and the object key is
-- enrolled in the tenant quota/deletion lifecycle; application/pdf is reserved for future adapters.
CREATE TABLE document_delivery_artifact (
    id           INT AUTO_INCREMENT PRIMARY KEY,
    workspace_id INT NOT NULL COMMENT 'Owning workspace',
    delivery_id  INT NOT NULL,
    kind         VARCHAR(32) NOT NULL,
    object_key   VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    content_type VARCHAR(128) NOT NULL,
    byte_length  BIGINT NOT NULL,
    sha256       CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_document_delivery_artifact_delivery FOREIGN KEY (workspace_id, delivery_id)
        REFERENCES document_delivery(workspace_id, id) ON DELETE CASCADE,
    CONSTRAINT chk_document_delivery_artifact_kind CHECK (
        kind IN ('signed_document', 'certificate')),
    CONSTRAINT chk_document_delivery_artifact_length CHECK (byte_length > 0),
    UNIQUE KEY uq_document_delivery_artifact_workspace_id (workspace_id, id),
    UNIQUE KEY uq_document_delivery_artifact_kind (workspace_id, delivery_id, kind),
    INDEX idx_document_delivery_artifact_delivery (workspace_id, delivery_id, id)
) DEFAULT CHARSET=utf8mb4 COMMENT='Managed immutable completion artifacts for a document envelope';

-- Forward-only: sent and signed are owned by the delivery lifecycle; the existing approval
-- vocabulary remains unchanged and the document service continues to own all other transitions.
ALTER TABLE deal_document
    DROP CONSTRAINT chk_deal_document_status,
    ADD CONSTRAINT chk_deal_document_status
        CHECK (status IN ('draft', 'pending_approval', 'approved', 'sent', 'signed', 'final', 'superseded'));
