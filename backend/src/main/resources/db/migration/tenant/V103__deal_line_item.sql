-- Line items on a deal (revenue-ops Phase 1, #558). Catalog values are snapshotted at creation
-- so later product edits never mutate an existing line; line_* money columns are server-computed
-- (BigDecimal) and persisted for deterministic history. One deal never mixes currencies.
CREATE TABLE deal_line_item (
    id                    INT AUTO_INCREMENT PRIMARY KEY,
    workspace_id          INT NOT NULL COMMENT 'Owning workspace',
    deal_id               INT NOT NULL COMMENT 'Parent deal',
    product_id            INT NULL COMMENT 'Source catalog product; NULL for ad-hoc or after product deletion',
    name                  VARCHAR(255) NOT NULL COMMENT 'Snapshot of product name (or ad-hoc name)',
    sku                   VARCHAR(64) NULL COMMENT 'Snapshot of product SKU',
    unit                  VARCHAR(32) NULL,
    unit_price            DECIMAL(15, 2) NOT NULL DEFAULT 0,
    quantity              DECIMAL(15, 3) NOT NULL DEFAULT 1,
    discount_type         VARCHAR(8) NULL COMMENT 'amount | percent',
    discount_value        DECIMAL(15, 2) NULL,
    tax_rate              DECIMAL(6, 3) NULL COMMENT 'Tax rate percent applied to the discounted subtotal',
    billing_frequency     VARCHAR(16) NOT NULL DEFAULT 'one_time' COMMENT 'one_time | recurring',
    description           VARCHAR(1024) NULL,
    service_period_start  DATE NULL,
    service_period_end    DATE NULL,
    position              INT NOT NULL DEFAULT 0 COMMENT 'Manual sort order within the deal',
    currency              VARCHAR(8) NOT NULL COMMENT 'Always the parent deal currency',
    line_subtotal         DECIMAL(15, 2) NOT NULL DEFAULT 0 COMMENT 'Server-computed: unit_price*quantity - discount',
    line_tax              DECIMAL(15, 2) NOT NULL DEFAULT 0 COMMENT 'Server-computed: subtotal * tax_rate',
    line_total            DECIMAL(15, 2) NOT NULL DEFAULT 0 COMMENT 'Server-computed: subtotal + tax',
    created_at            DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_dli_deal FOREIGN KEY (workspace_id, deal_id) REFERENCES deal(workspace_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_dli_product FOREIGN KEY (product_id) REFERENCES product(id) ON DELETE SET NULL,
    CONSTRAINT chk_dli_billing_frequency CHECK (billing_frequency IN ('one_time', 'recurring')),
    CONSTRAINT chk_dli_discount_type CHECK (discount_type IS NULL OR discount_type IN ('amount', 'percent')),
    INDEX idx_dli_deal (workspace_id, deal_id, position, id)
) DEFAULT CHARSET=utf8mb4 COMMENT='Line items on a deal with snapshotted catalog values and computed totals';
