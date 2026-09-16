-- Additive columns remain readable by the previous application during rollback.
-- Backfill cost: both UPDATEs are unbatched; each can lock/write up to D rows in a single
-- transaction, where D is this catalog's campaign_delivery row count. The first also scans and
-- groups dispatched events among E campaign_delivery_event rows. Expect zero updates on a fresh
-- catalog; on an existing catalog, expect submitted/evidenced rows in the first UPDATE and those
-- rows plus unresolved attempts in the second (each <= D). Production D/E are deployment-specific
-- and must be measured before cutover; budget undo/redo space and a quiesced maintenance window.
-- SQL migrations cannot loop over primary-key ranges without a procedure; no procedure is installed.
-- Rollback/roll-forward gap: an older binary can write dispatched rows with submitted_at NULL.
-- Flyway will not rerun this backfill on roll-forward. Quiesce old writers, then repair those rows
-- using the same event/creation-time derivation before resuming sends; otherwise their cap is absent.
ALTER TABLE campaign_delivery
    ADD COLUMN submitted_at DATETIME(6) NULL,
    ADD COLUMN frequency_reserved_at DATETIME(6) NULL;

-- Receipt time is not submission time. Legacy rows without dispatch evidence use their
-- immutable creation time; no future receipt or reconciliation may move this anchor.
UPDATE campaign_delivery d
LEFT JOIN (
    SELECT workspace_id, delivery_id, MIN(created_at) AS first_submitted_at
    FROM campaign_delivery_event
    WHERE event_type = 'dispatched'
    GROUP BY workspace_id, delivery_id
) e ON e.workspace_id = d.workspace_id AND e.delivery_id = d.id
SET d.submitted_at = COALESCE(e.first_submitted_at, d.created_at),
    d.updated_at = d.updated_at
WHERE e.first_submitted_at IS NOT NULL
   OR d.status IN ('dispatched', 'delivered', 'bounced', 'complained')
   OR d.provider_message_id IS NOT NULL
   OR d.reconciliation_outcome = 'operator_delivered';

-- Preserve uncertain attempts across the cutover without turning receipts into new submissions.
UPDATE campaign_delivery
SET frequency_reserved_at = COALESCE(submitted_at, created_at),
    updated_at = updated_at
WHERE submitted_at IS NOT NULL
   OR status = 'dispatching'
   OR reconciliation_required_at IS NOT NULL
   OR (status = 'pending' AND attempt_target_fingerprint IS NOT NULL);
