-- Retire pre-baseline notification invalidation hooks. Application writers own invalidation.
-- Each statement is safe after a partially committed run and on catalogs without these hooks.
DROP TRIGGER IF EXISTS trg_notification_state_insert;
DROP TRIGGER IF EXISTS trg_notification_state_update;
DROP TRIGGER IF EXISTS trg_notification_state_delete;
