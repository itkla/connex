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

DELIMITER $$

DROP PROCEDURE IF EXISTS migrate_deal_won$$
CREATE PROCEDURE migrate_deal_won()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'deal'
          AND column_name = 'closed_reason'
    ) THEN
        ALTER TABLE deal
            ADD COLUMN closed_reason VARCHAR(255) NULL
                COMMENT 'Reason the deal was closed (won/lost)'
                AFTER closed_at;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'deal'
          AND column_name = 'won'
    ) THEN
        ALTER TABLE deal
            ADD COLUMN won BOOLEAN NULL
                COMMENT 'Outcome when closed: TRUE = won, FALSE = lost, NULL = open. Set by the client and independent of stage; closed_at follows this.'
                AFTER closed_reason;
    END IF;

    UPDATE deal d
    JOIN stage s ON s.id = d.stage_id
    SET d.won = CASE
            WHEN s.is_success THEN TRUE
            WHEN s.is_failure THEN FALSE
            ELSE NULL
        END
    WHERE d.closed_at IS NOT NULL
      AND d.won IS NULL;

    UPDATE deal SET won = FALSE WHERE closed_at IS NOT NULL AND won IS NULL;
    UPDATE deal SET won = NULL WHERE closed_at IS NULL AND won IS NOT NULL;
    UPDATE deal SET closed_reason = NULL WHERE closed_at IS NULL AND closed_reason IS NOT NULL;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.table_constraints
        WHERE constraint_schema = DATABASE()
          AND table_name = 'deal'
          AND constraint_name = 'chk_deal_outcome_closed'
    ) THEN
        ALTER TABLE deal
            ADD CONSTRAINT chk_deal_outcome_closed
                CHECK ((won IS NULL) = (closed_at IS NULL));
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.table_constraints
        WHERE constraint_schema = DATABASE()
          AND table_name = 'deal'
          AND constraint_name = 'chk_deal_reason_requires_close'
    ) THEN
        ALTER TABLE deal
            ADD CONSTRAINT chk_deal_reason_requires_close
                CHECK (closed_reason IS NULL OR closed_at IS NOT NULL);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = 'deal'
          AND index_name = 'idx_deal_won'
    ) THEN
        ALTER TABLE deal ADD INDEX idx_deal_won (won);
    END IF;
END$$

CALL migrate_deal_won()$$
DROP PROCEDURE migrate_deal_won$$

DELIMITER ;
