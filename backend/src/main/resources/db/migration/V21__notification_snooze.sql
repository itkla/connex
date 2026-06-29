-- Snooze: hide an active notification from the inbox until snoozed_until passes, then it reappears.
-- Reconciliation re-dispatch (upsert) preserves this unless the notification's severity changes.
ALTER TABLE notification
    ADD COLUMN snoozed_until DATETIME NULL COMMENT 'When a snoozed notification reappears in the active inbox' AFTER resolved_at;
