-- Workspace-scoped product/service catalog (revenue-ops Phase 1, #558). Deal line items
-- snapshot catalog values at creation, so this table only holds the current definition.
CREATE TABLE product (
    id                INT AUTO_INCREMENT PRIMARY KEY,
    workspace_id      INT NOT NULL COMMENT 'Owning workspace',
    sku               VARCHAR(64) NULL COMMENT 'Stock-keeping unit; unique per workspace when present',
    name              VARCHAR(255) NOT NULL COMMENT 'Product or service name',
    description       VARCHAR(1024) NULL,
    active            BOOLEAN NOT NULL DEFAULT TRUE COMMENT 'Whether the item is offered on new line items',
    unit              VARCHAR(32) NULL COMMENT 'Default unit of measure, e.g. seat, hour, license',
    unit_price        DECIMAL(15, 2) NOT NULL DEFAULT 0 COMMENT 'Catalog unit price in currency',
    currency          VARCHAR(8) NOT NULL DEFAULT 'USD',
    tax_rate          DECIMAL(6, 3) NULL COMMENT 'Default tax rate percent, e.g. 10.000',
    billing_frequency VARCHAR(16) NOT NULL DEFAULT 'one_time' COMMENT 'one_time | recurring',
    effective_start   DATE NULL,
    effective_end     DATE NULL,
    created_at        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT chk_product_billing_frequency CHECK (billing_frequency IN ('one_time', 'recurring')),
    UNIQUE KEY uq_product_workspace_sku (workspace_id, sku),
    INDEX idx_product_workspace (workspace_id)
) DEFAULT CHARSET=utf8mb4 COMMENT='Workspace-scoped catalog of products and services';
