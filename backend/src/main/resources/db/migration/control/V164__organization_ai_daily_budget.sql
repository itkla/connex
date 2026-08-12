CREATE TABLE organization_ai_budget (
    org_id             INT NOT NULL PRIMARY KEY,
    daily_token_limit  BIGINT UNSIGNED NOT NULL DEFAULT 0,
    updated_at         DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                           ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT fk_organization_ai_budget_org
        FOREIGN KEY (org_id) REFERENCES organization(id) ON DELETE CASCADE,
    CONSTRAINT chk_organization_ai_budget_limit
        CHECK (daily_token_limit <= 1000000000000)
) DEFAULT CHARSET=utf8mb4;

CREATE TABLE organization_ai_budget_usage (
    org_id            INT NOT NULL,
    usage_day         DATE NOT NULL,
    consumed_tokens   BIGINT UNSIGNED NOT NULL DEFAULT 0,
    updated_at        DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                          ON UPDATE CURRENT_TIMESTAMP(6),

    PRIMARY KEY (org_id, usage_day),
    CONSTRAINT fk_organization_ai_budget_usage_org
        FOREIGN KEY (org_id) REFERENCES organization(id) ON DELETE CASCADE
) DEFAULT CHARSET=utf8mb4;

CREATE TABLE organization_ai_budget_reservation (
    reservation_id   CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    org_id           INT NOT NULL,
    usage_day        DATE NOT NULL,
    reserved_tokens  BIGINT UNSIGNED NOT NULL,
    expires_at       DATETIME(6) NOT NULL,
    created_at       DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    CONSTRAINT fk_organization_ai_budget_reservation_org
        FOREIGN KEY (org_id) REFERENCES organization(id) ON DELETE CASCADE,
    CONSTRAINT chk_organization_ai_budget_reservation_tokens
        CHECK (reserved_tokens > 0),
    INDEX idx_organization_ai_budget_reservation_org_day
        (org_id, usage_day, expires_at),
    INDEX idx_organization_ai_budget_reservation_expiry
        (expires_at, reservation_id)
) DEFAULT CHARSET=utf8mb4;
