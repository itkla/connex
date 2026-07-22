-- ============================================================================
-- Open the campaign send path to the SMS channel and to opt-out consent.
--
-- 1. Reason vocabulary. Consent moves from default-deny (opt-in) to default-allow
--    (opt-out): a person is now excluded only by an explicit revocation, reported
--    as 'consent_revoked'. Both reason CHECKs gain that token. 'consent_missing'
--    stays admitted — historical rows carry it, and it is still what the opt-in
--    policy emits if the policy constant is flipped back.
--
-- 2. Per-channel message content. campaign_message_revision was written for email
--    only, so subject and body_html were NOT NULL. An SMS revision has neither —
--    it carries body_text alone — so both columns become nullable. Which fields a
--    revision must supply is channel-specific and cannot be expressed as a table
--    CHECK (the channel lives on campaign_message), so it is enforced at the write
--    choke point in CampaignSendService.addRevision. Forward-only; no existing row
--    changes and no column is renamed.
-- ============================================================================

ALTER TABLE campaign_delivery
    DROP CONSTRAINT chk_campaign_delivery_skip_reason,
    ADD CONSTRAINT chk_campaign_delivery_skip_reason CHECK (
        (status = 'skipped' AND skip_reason IN (
            'consent_missing', 'consent_revoked', 'suppressed', 'restricted',
            'frequency_capped', 'quiet_hours', 'no_address'))
        OR (status <> 'skipped' AND skip_reason IS NULL));

ALTER TABLE campaign_audience_member
    DROP CONSTRAINT chk_campaign_member_reason,
    ADD CONSTRAINT chk_campaign_member_reason CHECK (
        (status = 'included' AND exclusion_reason IS NULL)
        OR (status = 'excluded' AND exclusion_reason IN (
            'consent_missing', 'consent_revoked', 'suppressed', 'restricted')));

ALTER TABLE campaign_message_revision
    MODIFY COLUMN subject   VARCHAR(255) NULL,
    MODIFY COLUMN body_html MEDIUMTEXT NULL;
