-- Audience delivery attempts carry their own lease end time (#1773): the age anchor a recovery sweep
-- needs for an attempt abandoned before its frequency reservation, or with no recipient person.
-- It is a separate column, not an owner-less dispatch_lease_until, for two reasons:
--   * The dispatch lease columns keep meaning a triggered claim, so the previous release, which never
--     reads this column, behaves the same whether or not it is set, before and after a rollback.
--   * chk_campaign_delivery_dispatch_lease stays untouched. Re-adding that CHECK in any form would
--     need ALGORITHM=COPY on MySQL 8.4, rebuilding the table and blocking delivery writes meanwhile.
-- Adding a nullable column is a metadata change. ALGORITHM=INSTANT makes this migration fail rather
-- than fall back to a table copy if that ever stops holding.
ALTER TABLE campaign_delivery
    ADD COLUMN audience_lease_until DATETIME(6) NULL,
    ALGORITHM=INSTANT;
