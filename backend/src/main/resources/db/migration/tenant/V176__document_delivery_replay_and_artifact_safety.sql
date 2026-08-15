-- Replay-stable send/resend claims stay tenant-scoped and are retained with the immutable document.
CREATE TABLE document_delivery_request (
    workspace_id        INT NOT NULL COMMENT 'Owning workspace',
    idempotency_key     CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    operation           VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_fingerprint BINARY(32) NOT NULL,
    document_id         INT NOT NULL,
    delivery_id         INT NULL,
    recipient_id        INT NULL,
    created_by_user_id  INT NOT NULL COMMENT 'Control-plane user id; validated in the service layer',
    created_at          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at        DATETIME NULL,
    PRIMARY KEY (workspace_id, idempotency_key),
    CONSTRAINT fk_document_delivery_request_document FOREIGN KEY (workspace_id, document_id)
        REFERENCES deal_document(workspace_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_document_delivery_request_delivery FOREIGN KEY (workspace_id, delivery_id)
        REFERENCES document_delivery(workspace_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_document_delivery_request_recipient FOREIGN KEY (
        workspace_id, delivery_id, recipient_id)
        REFERENCES document_delivery_recipient(workspace_id, delivery_id, id) ON DELETE CASCADE,
    CONSTRAINT chk_document_delivery_request_operation CHECK (operation IN ('send', 'resend')),
    INDEX idx_document_delivery_request_document (workspace_id, document_id)
) DEFAULT CHARSET=utf8mb4 COMMENT='Caller-retained idempotency claims and replay results';

-- Provider recipient identifiers are routing keys and therefore unique within one envelope.
ALTER TABLE document_delivery_recipient
    ADD UNIQUE KEY uq_document_delivery_recipient_provider (
        workspace_id, delivery_id, provider_recipient_id);

-- The live policy FK is nullable on policy deletion; retain the originally applied identifier.
ALTER TABLE document_approval
    ADD COLUMN policy_id_snapshot INT NULL AFTER policy_id,
    ADD COLUMN policy_binding VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin
        NOT NULL DEFAULT 'unknown_legacy' AFTER policy_id_snapshot;

UPDATE document_approval SET policy_id_snapshot = policy_id;

UPDATE document_approval
SET policy_binding = 'applied'
WHERE policy_id_snapshot IS NOT NULL;

ALTER TABLE document_approval
    ADD CONSTRAINT chk_document_approval_policy_binding CHECK (
        (policy_binding = 'applied' AND policy_id_snapshot IS NOT NULL)
        OR (policy_binding IN ('none', 'unknown_legacy') AND policy_id_snapshot IS NULL));
