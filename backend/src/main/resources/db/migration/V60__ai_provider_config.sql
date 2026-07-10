-- ============================================================================
-- Per-organization BYOP AI provider configuration (#381/#382). One row per
-- organization; provider credentials are stored only in secret_value through the
-- central SecretStore and this table keeps only the returned reference plus
-- masked metadata. v1 supports Amazon Bedrock for Anthropic Claude only.
-- ============================================================================

CREATE TABLE ai_provider_config (
    org_id               INT NOT NULL PRIMARY KEY COMMENT 'Owning organization',
    provider             VARCHAR(32) NOT NULL COMMENT 'bedrock',
    region               VARCHAR(64) NOT NULL COMMENT 'Validated concrete AWS Bedrock region',
    model_id             VARCHAR(128) NOT NULL COMMENT 'Bedrock Claude model or inference profile id',
    credential_ref       VARCHAR(128) NULL COMMENT 'SecretStore reference for the provider credential bundle',
    credential_last4     VARCHAR(8) NULL COMMENT 'Last four characters of the secret access key',
    no_training_attested BOOLEAN NOT NULL DEFAULT FALSE COMMENT 'Customer attested no-training/no-retention terms',
    attested_at          DATETIME NULL COMMENT 'Timestamp when no-training/no-retention was attested',
    enabled              BOOLEAN NOT NULL DEFAULT FALSE COMMENT 'Whether this provider config may serve AI features',
    created_at           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Creation timestamp',
    updated_at           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Last update timestamp',
    CONSTRAINT fk_ai_provider_config_org FOREIGN KEY (org_id) REFERENCES organization(id) ON DELETE CASCADE,
    CONSTRAINT ck_ai_provider_config_provider CHECK (provider IN ('bedrock')),
    CONSTRAINT ck_ai_provider_config_concrete_region CHECK (region NOT LIKE '%.%')
) DEFAULT CHARSET=utf8mb4 COMMENT='Per-organization BYOP AI provider configuration';
