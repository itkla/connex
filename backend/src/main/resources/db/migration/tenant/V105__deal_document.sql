-- A generated commercial document on a deal (revenue-ops Phase 2, #558). The resolved content is a
-- frozen JSON snapshot (parties, resolved sections, line items, totals) written once at generation:
-- a document stays stable even if the deal, catalog, or template change later. Only status transitions.
CREATE TABLE deal_document (
    id            INT AUTO_INCREMENT PRIMARY KEY,
    workspace_id  INT NOT NULL COMMENT 'Owning workspace',
    deal_id       INT NOT NULL COMMENT 'Parent deal',
    template_id   INT NULL COMMENT 'Source template; NULL after template deletion (content is snapshotted)',
    type          VARCHAR(16) NOT NULL COMMENT 'quote | proposal | order_form | contract',
    locale        VARCHAR(8) NOT NULL DEFAULT 'en',
    status        VARCHAR(16) NOT NULL DEFAULT 'draft' COMMENT 'draft | final | superseded',
    version       INT NOT NULL COMMENT 'Monotonic per deal',
    title         VARCHAR(512) NULL COMMENT 'Resolved title snapshot',
    content       LONGTEXT NOT NULL COMMENT 'Immutable resolved JSON snapshot',
    currency      VARCHAR(8) NOT NULL,
    generated_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by    INT NULL COMMENT 'User who generated the document',
    created_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_deal_document_deal FOREIGN KEY (workspace_id, deal_id) REFERENCES deal(workspace_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_deal_document_template FOREIGN KEY (template_id) REFERENCES document_template(id) ON DELETE SET NULL,
    CONSTRAINT chk_deal_document_type CHECK (type IN ('quote', 'proposal', 'order_form', 'contract')),
    CONSTRAINT chk_deal_document_status CHECK (status IN ('draft', 'final', 'superseded')),
    INDEX idx_deal_document_deal (workspace_id, deal_id, version)
) DEFAULT CHARSET=utf8mb4 COMMENT='Generated, immutable, versioned commercial documents on a deal';
