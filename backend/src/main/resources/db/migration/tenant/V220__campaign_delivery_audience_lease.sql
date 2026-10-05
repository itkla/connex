-- Audience delivery attempts carry a lease end time with no owner (#1773). An audience row is never
-- replayed, so no second worker can hold one and an owner would add no fence. The end time is the
-- age anchor a recovery sweep needs for attempts abandoned before a frequency reservation, or with
-- no recipient person. A lease owner still requires an end time.
ALTER TABLE campaign_delivery
    DROP CHECK chk_campaign_delivery_dispatch_lease,
    ADD CONSTRAINT chk_campaign_delivery_dispatch_lease CHECK (
        dispatch_lease_owner IS NULL OR dispatch_lease_until IS NOT NULL);
