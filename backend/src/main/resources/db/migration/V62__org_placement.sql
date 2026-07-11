-- ============================================================================
-- Per-organization deployment placement registry (#313 Phase 3). One row per
-- organization records where that org's data lives and its encryption/key
-- posture. This increment is read-only and carries no routing: an org with no
-- row resolves to `shared` in the service layer, so existing orgs are unchanged.
-- placement_mode tiers follow the corrected deployment ladder (pooled `shared`,
-- `dedicated_database`, `connex_operated_silo`); see ENCRYPTION_GUARANTEE_MATRIX
-- and DEDICATED_SAAS_CMK_FEASIBILITY for the field semantics.
-- ============================================================================

CREATE TABLE org_placement (
    org_id                   INT NOT NULL PRIMARY KEY COMMENT 'Owning organization (1:1)',
    placement_mode           VARCHAR(32) NOT NULL DEFAULT 'shared'
        COMMENT 'shared | dedicated_database | connex_operated_silo',
    database_handle          VARCHAR(64) NULL COMMENT 'Random non-customer-derived handle e.g. cnx_<random>',
    storage_encryption_mode  VARCHAR(32) NOT NULL DEFAULT 'provider_managed'
        COMMENT 'provider_managed | connex_managed_cmk | customer_managed_cmk | customer_operated',
    key_controller           VARCHAR(32) NOT NULL DEFAULT 'connex_cloud_provider'
        COMMENT 'connex | connex_cloud_provider | customer | customer_operated',
    kms_provider             VARCHAR(32) NULL COMMENT 'aws_kms | external_kms | none',
    kms_key_ref              VARCHAR(256) NULL COMMENT 'Non-secret key ARN/alias/id reference',
    kms_key_region           VARCHAR(64) NULL COMMENT 'Region/account boundary for provider-managed keys',
    revocation_supported     BOOLEAN NOT NULL DEFAULT FALSE
        COMMENT 'Whether customer action can make the environment unavailable',
    revocation_effect        VARCHAR(256) NULL COMMENT 'Expected lockout and recovery behavior',
    backup_encryption_mode   VARCHAR(32) NULL COMMENT 'Backup/snapshot key handling',
    snapshot_copy_policy     VARCHAR(32) NULL COMMENT 'retain_key | change_key',
    restore_validation_state VARCHAR(32) NULL COMMENT 'Last restore/blue-green/repointing validation result',
    evidence_checked_at      DATETIME NULL COMMENT 'Last verification timestamp for the stored posture',
    created_at               DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Creation timestamp',
    updated_at               DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Last update timestamp',
    CONSTRAINT fk_org_placement_org FOREIGN KEY (org_id) REFERENCES organization(id) ON DELETE CASCADE,
    CONSTRAINT uq_org_placement_database_handle UNIQUE (database_handle),
    CONSTRAINT ck_org_placement_mode CHECK (placement_mode IN ('shared','dedicated_database','connex_operated_silo')),
    CONSTRAINT ck_org_placement_handle_required CHECK (placement_mode = 'shared' OR database_handle IS NOT NULL),
    CONSTRAINT ck_org_placement_storage_mode CHECK (storage_encryption_mode IN ('provider_managed','connex_managed_cmk','customer_managed_cmk','customer_operated')),
    CONSTRAINT ck_org_placement_key_controller CHECK (key_controller IN ('connex','connex_cloud_provider','customer','customer_operated'))
) DEFAULT CHARSET=utf8mb4 COMMENT='Per-organization deployment placement registry';
