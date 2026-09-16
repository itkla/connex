-- Rollout: quiesce older instances before applying this migration and resume AI admission
-- only after every instance is upgraded. Their cleanup still deletes dispatched reservations
-- without charging usage, and their reserved totals include retained settled rows.
-- Rollback requires quiescing AI work and reconciling dispatched/settled rows with the usage
-- ledger before restoring older code; reverting the application alone is not budget-safe.
ALTER TABLE organization_ai_budget_reservation
    ADD COLUMN state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL DEFAULT 'dispatched',
    ADD COLUMN consumed_tokens BIGINT UNSIGNED NULL;

-- Existing reservations have no reliable dispatch evidence, so treat all of them as dispatched.
UPDATE organization_ai_budget_reservation
SET state = 'dispatched';

ALTER TABLE organization_ai_budget_reservation
    MODIFY COLUMN state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'dispatched',
    ADD CONSTRAINT chk_organization_ai_budget_reservation_state
        CHECK (state IN ('reserved', 'dispatched', 'settled')),
    DROP INDEX idx_organization_ai_budget_reservation_expiry,
    ADD INDEX idx_organization_ai_budget_reservation_state_expiry (state, expires_at, reservation_id);
