-- ============================================================================
-- Admit GRANDFATHERED as a privileged MFA coverage source (#1534, slice 4a-2c).
--
-- The cutover migration (V225) records, per organization, every passkey of the
-- accounts privileged when it runs, with source GRANDFATHERED. This only widens
-- the source check so those rows are allowed; no data changes here.
--
-- MySQL applies the drop and the re-add as one atomic DDL statement, and running
-- it again on a migrated schema changes nothing. A crash before Flyway records
-- this version therefore needs no repair: the next start runs it again.
-- ============================================================================
ALTER TABLE privileged_credential_attestation
    DROP CHECK chk_privileged_credential_attestation_source,
    ADD CONSTRAINT chk_privileged_credential_attestation_source
        CHECK (source IN ('FOUNDER', 'INHERITED', 'GRANTOR', 'GRANDFATHERED')),
    MODIFY COLUMN source VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
        COMMENT 'FOUNDER, INHERITED, GRANTOR or GRANDFATHERED';
