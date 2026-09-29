-- A still-owned claim whose send stops being dispatchable must terminate without provider egress.
-- Rollback: keep this widened constraint. Older backend readers carry skip_reason as a String/map
-- key, and frontend CampaignRecipient.skipReason is a string; CampaignEngagement falls back to
-- displaying an unknown token. No exhaustive enum/switch rejects existing not_dispatchable rows.
-- Restoring the older constraint requires first translating or removing rows with the new reason.
ALTER TABLE campaign_delivery
    DROP CONSTRAINT chk_campaign_delivery_skip_reason,
    ADD CONSTRAINT chk_campaign_delivery_skip_reason CHECK (
        (status = 'skipped' AND skip_reason IN (
            'consent_missing', 'consent_revoked', 'suppressed', 'restricted',
            'frequency_capped', 'quiet_hours', 'no_address', 'not_dispatchable'))
        OR (status <> 'skipped' AND skip_reason IS NULL));
