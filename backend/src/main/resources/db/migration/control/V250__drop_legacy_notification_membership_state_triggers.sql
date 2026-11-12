-- Retire pre-baseline membership invalidation hooks. Application writers own invalidation.
-- Each statement is safe after a partially committed run and on catalogs without these hooks.
DROP TRIGGER IF EXISTS trg_notification_membership_state_insert;
DROP TRIGGER IF EXISTS trg_notification_membership_state_update;
DROP TRIGGER IF EXISTS trg_notification_membership_state_delete;
