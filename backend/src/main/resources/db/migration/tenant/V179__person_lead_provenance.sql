-- Record-level lead-source provenance for contacts (#559, increment 3 of docs/LEAD_LIFECYCLE.md).
--
-- All three columns are nullable: existing contacts predate provenance capture and must not be
-- backfilled with an invented source. The referrer is meaningful only for referral- or
-- partner-sourced contacts, and free-text detail is meaningful only when a source exists; both
-- pairings are enforced here so the columns cannot drift even if a future write path forgets.
-- referrer_person_id deliberately has no foreign key: a composite self-FK with RESTRICT would make
-- tenant teardown's single-statement person delete order-dependent, and SET NULL on a composite key
-- would null workspace_id too. The service validates the referrer is an owned workspace contact,
-- and contacts are archived rather than deleted, so dangling referrers cannot arise outside
-- whole-workspace teardown, which removes both rows anyway.
ALTER TABLE person
    ADD COLUMN lead_source VARCHAR(32)
        CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci
        NULL COMMENT 'How the contact originally entered; NULL when provenance was never captured',
    ADD COLUMN lead_source_detail VARCHAR(255) NULL
        COMMENT 'Free-text source detail such as the event or website name',
    ADD COLUMN referrer_person_id INT NULL
        COMMENT 'Contact who referred this person, for referral or partner sources',
    ADD CONSTRAINT chk_person_lead_source CHECK (
        lead_source IS NULL OR lead_source IN (
            'REFERRAL', 'EVENT', 'WEB', 'OUTBOUND', 'BUSINESS_CARD', 'IMPORT', 'PARTNER', 'OTHER')),
    ADD CONSTRAINT chk_person_lead_source_detail CHECK (
        lead_source_detail IS NULL OR lead_source IS NOT NULL),
    ADD CONSTRAINT chk_person_referrer_source CHECK (
        referrer_person_id IS NULL OR lead_source IN ('REFERRAL', 'PARTNER')),
    ADD INDEX idx_person_workspace_lead_source (workspace_id, lead_source);
