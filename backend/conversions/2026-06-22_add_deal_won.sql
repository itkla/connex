-- ============================================================================
-- Conversion: add deal.won (explicit won/lost outcome)
-- ----------------------------------------------------------------------------
-- One-off, manually-applied conversion for EXISTING databases. schema.sql already
-- includes `won` for fresh installs; this brings a live DB over without data loss.
-- NOT auto-run (lives outside src/main/resources so Spring never executes it).
--
-- Run once, in order:
--   mysql -u connexuser -p connexdb < backend/conversions/2026-06-22_add_deal_won.sql
--
-- Model: a deal's outcome is explicit. closed_at = when it closed (NULL = open);
-- won = outcome (TRUE won / FALSE lost / NULL open); won is set iff closed_at is set.
-- Historically closed deals lived on a terminal (success/failure) stage, so we backfill
-- `won` from the stage flags.
-- ============================================================================

USE connexdb;

-- ---------------------------------------------------------------------------
-- 0. PRE-CHECK (review before running). Closed deals NOT on a terminal stage have
--    no win/lose signal in the old data; step 3 will default them to lost (FALSE).
--    Inspect them first and fix by hand if any should be 'won':
--
--   SELECT d.id, d.name, d.stage_id, s.name AS stage, s.is_success, s.is_failure, d.closed_at
--   FROM deal d JOIN stage s ON s.id = d.stage_id
--   WHERE d.closed_at IS NOT NULL AND s.is_success = FALSE AND s.is_failure = FALSE;
-- ---------------------------------------------------------------------------

-- 1. Add the column (every existing row defaults to NULL = open; backfilled below).
ALTER TABLE deal
    ADD COLUMN won BOOLEAN NULL
        COMMENT 'Outcome when closed: TRUE = won, FALSE = lost, NULL = open. Set by the client and independent of stage; closed_at follows this.'
        AFTER closed_reason;

-- 2. Backfill won from the stage flags for deals that are already closed.
UPDATE deal d
JOIN stage s ON s.id = d.stage_id
SET d.won = CASE
        WHEN s.is_success THEN TRUE
        WHEN s.is_failure THEN FALSE
        ELSE NULL
    END
WHERE d.closed_at IS NOT NULL;

-- 3. Defensive: a closed deal that wasn't on a terminal stage (shouldn't exist under the
--    old logic, which only closed on terminal stages) still needs an outcome to satisfy
--    the invariant. Treat unknown closures as lost; the pre-check above lists them for review.
UPDATE deal SET won = FALSE WHERE closed_at IS NOT NULL AND won IS NULL;

-- 4. Clear any reason on a deal that isn't closed (keeps chk_deal_reason_requires_close happy).
UPDATE deal SET closed_reason = NULL WHERE closed_at IS NULL AND closed_reason IS NOT NULL;

-- 5. Enforce the invariants and index the outcome.
ALTER TABLE deal
    ADD CONSTRAINT chk_deal_outcome_closed CHECK ((won IS NULL) = (closed_at IS NULL)),
    ADD CONSTRAINT chk_deal_reason_requires_close CHECK (closed_reason IS NULL OR closed_at IS NOT NULL),
    ADD INDEX idx_deal_won (won);
