-- Internal approval workflow for generated deal documents (revenue-ops WS4 Phase 3, #558/#662).
-- approval_policy declares when a document needs approval before it can go final; document_approval
-- holds the request/decision history for a document. Approval binds to a specific immutable
-- deal_document row, so regenerating (a new version) always requires a fresh approval.
CREATE TABLE approval_policy (
    id                   INT AUTO_INCREMENT PRIMARY KEY,
    workspace_id         INT NOT NULL COMMENT 'Owning workspace',
    name                 VARCHAR(255) NOT NULL,
    active               BOOLEAN NOT NULL DEFAULT TRUE,
    document_type        VARCHAR(16) NULL COMMENT 'Restrict to one document type; NULL applies to all types',
    currency             VARCHAR(8) NULL COMMENT 'Currency min_total is expressed in; required when min_total is set',
    min_total            DECIMAL(15, 2) NULL COMMENT 'Approval required when the document grand total reaches this, in the policy currency',
    min_discount_percent DECIMAL(6, 3) NULL COMMENT 'Approval required when the effective line-item discount percent reaches this',
    created_at           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT chk_approval_policy_type CHECK (document_type IS NULL OR document_type IN ('quote', 'proposal', 'order_form', 'contract')),
    CONSTRAINT chk_approval_policy_total_currency CHECK (min_total IS NULL OR currency IS NOT NULL),
    CONSTRAINT chk_approval_policy_discount CHECK (min_discount_percent IS NULL OR (min_discount_percent >= 0 AND min_discount_percent <= 100)),
    INDEX idx_approval_policy_workspace (workspace_id)
) DEFAULT CHARSET=utf8mb4 COMMENT='When a generated deal document requires internal approval before finalization';

ALTER TABLE deal_document
    ADD UNIQUE KEY uq_deal_document_workspace_id (workspace_id, id);

CREATE TABLE document_approval (
    id               INT AUTO_INCREMENT PRIMARY KEY,
    workspace_id     INT NOT NULL COMMENT 'Owning workspace',
    deal_id          INT NOT NULL COMMENT 'Parent deal',
    document_id      INT NOT NULL COMMENT 'The immutable document version this approval covers',
    policy_id        INT NULL COMMENT 'Policy that triggered the request; NULL for voluntary requests or after policy deletion',
    status           VARCHAR(16) NOT NULL DEFAULT 'pending',
    requested_by     INT NULL COMMENT 'Requesting user; validated against workspace membership in the service layer',
    request_comment  VARCHAR(1000) NULL,
    decided_by       INT NULL COMMENT 'Deciding user; validated against workspace membership in the service layer',
    decision_comment VARCHAR(1000) NULL,
    decided_at       DATETIME NULL,
    created_at       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_document_approval_deal FOREIGN KEY (workspace_id, deal_id) REFERENCES deal(workspace_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_document_approval_document FOREIGN KEY (workspace_id, document_id) REFERENCES deal_document(workspace_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_document_approval_policy FOREIGN KEY (policy_id) REFERENCES approval_policy(id) ON DELETE SET NULL,
    CONSTRAINT chk_document_approval_status CHECK (status IN ('pending', 'approved', 'rejected', 'cancelled')),
    INDEX idx_document_approval_document (workspace_id, document_id, id),
    INDEX idx_document_approval_deal (workspace_id, deal_id),
    INDEX idx_document_approval_status (workspace_id, status)
) DEFAULT CHARSET=utf8mb4 COMMENT='Approval request and decision history for generated deal documents';

-- Forward-only: widen the document status vocabulary with the two approval states. The service
-- layer owns the transition rules; pending_approval/approved are only ever set by the approval flow.
ALTER TABLE deal_document
    DROP CONSTRAINT chk_deal_document_status,
    ADD CONSTRAINT chk_deal_document_status CHECK (status IN ('draft', 'pending_approval', 'approved', 'final', 'superseded'));
