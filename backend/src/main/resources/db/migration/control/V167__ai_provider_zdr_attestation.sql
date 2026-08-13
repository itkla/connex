ALTER TABLE ai_provider_config
    ADD COLUMN zero_data_retention_attested BOOLEAN NOT NULL DEFAULT FALSE
        AFTER no_training_attested,
    ADD COLUMN zdr_attested_by_user_id INT NULL
        AFTER zero_data_retention_attested,
    ADD COLUMN zdr_attested_at DATETIME(6) NULL
        AFTER zdr_attested_by_user_id,
    ADD COLUMN zdr_attestation_version INT NULL
        AFTER zdr_attested_at,
    ADD CONSTRAINT fk_ai_provider_config_zdr_attested_by
        FOREIGN KEY (zdr_attested_by_user_id) REFERENCES app_user(id) ON DELETE SET NULL,
    ADD CONSTRAINT chk_ai_provider_config_zdr_attestation_version
        CHECK (zdr_attestation_version IS NULL OR zdr_attestation_version > 0),
    ADD CONSTRAINT chk_ai_provider_config_zdr_attestation_state
        CHECK ((zero_data_retention_attested = FALSE
                AND zdr_attested_at IS NULL
                AND zdr_attestation_version IS NULL)
            OR (zero_data_retention_attested = TRUE
                AND zdr_attested_at IS NOT NULL
                AND zdr_attestation_version IS NOT NULL)),
    ADD INDEX idx_ai_provider_config_zdr_attested_by (zdr_attested_by_user_id);
