ALTER TABLE notification
    ADD COLUMN snooze_timezone VARCHAR(64) NULL AFTER snoozed_until;

UPDATE notification
SET snooze_timezone = 'UTC'
WHERE snoozed_until IS NOT NULL;

ALTER TABLE notification
    ADD CONSTRAINT chk_notification_snooze_pair
        CHECK (
            (snoozed_until IS NULL AND snooze_timezone IS NULL)
            OR (snoozed_until IS NOT NULL AND snooze_timezone IS NOT NULL)
        ),
    ADD INDEX idx_notification_due_snooze
        (workspace_id, snoozed_until, recipient_id, id),
    ADD INDEX idx_notification_recipient_state
        (recipient_id, dismissed_at, resolved_at, read_at, snoozed_until, triggered_at, id);
