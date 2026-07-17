-- Workspace-scoped, admin-managed commercial-document templates (revenue-ops Phase 2, #558).
-- Sections carry {{merge tokens}} resolved server-side at document generation time.
CREATE TABLE document_template (
    id           INT AUTO_INCREMENT PRIMARY KEY,
    workspace_id INT NOT NULL COMMENT 'Owning workspace',
    name         VARCHAR(255) NOT NULL,
    type         VARCHAR(16) NOT NULL COMMENT 'quote | proposal | order_form | contract',
    locale       VARCHAR(8) NOT NULL DEFAULT 'en',
    title        VARCHAR(512) NULL COMMENT 'Document title line (may contain merge tokens)',
    intro        TEXT NULL,
    terms        TEXT NULL,
    footer       TEXT NULL,
    active       BOOLEAN NOT NULL DEFAULT TRUE,
    created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT chk_document_template_type CHECK (type IN ('quote', 'proposal', 'order_form', 'contract')),
    UNIQUE KEY uq_document_template_workspace_id (workspace_id, id),
    INDEX idx_document_template_workspace (workspace_id)
) DEFAULT CHARSET=utf8mb4 COMMENT='Admin-managed commercial-document templates';
