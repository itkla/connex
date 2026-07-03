-- ============================================================================
-- Widen deal.closed_reason for @/# mentions (#274). The close reason now supports
-- inline mention tokens ([Label](type:id)), whose stored markup is longer than the
-- rendered text — a couple of member mentions can blow past the old 255-char cap.
-- Raise it to 1000 (matching task.description) so a normal reason with mentions fits.
-- Widening a VARCHAR is an online metadata change; the CHECK constraint is unaffected.
-- ============================================================================

ALTER TABLE deal
    MODIFY closed_reason VARCHAR(1000)
    COMMENT 'Reason the deal was closed (won/lost); may contain @/# mention tokens';
