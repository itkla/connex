-- Approval requests retain a stable terminal reason independently of their coarse status. Policy
-- tightening and loss of eligible approvers terminate the request without rewriting its frozen
-- chain, while legacy cancelled rows are backfilled before terminal consistency is enforced.
ALTER TABLE document_approval
    ADD COLUMN outcome_reason VARCHAR(32) NULL COMMENT 'Stable reason for a terminal approval outcome',
    ADD COLUMN outcome_detail VARCHAR(500) NULL COMMENT 'Bounded operator-facing detail for the terminal outcome';

UPDATE document_approval
SET outcome_reason = CASE status
        WHEN 'approved'  THEN 'quorum'
        WHEN 'rejected'  THEN 'rejected'
        WHEN 'cancelled' THEN 'cancelled_legacy'
    END
WHERE status <> 'pending';

ALTER TABLE document_approval
    DROP CONSTRAINT chk_document_approval_status,
    ADD CONSTRAINT chk_document_approval_status
        CHECK (status IN ('pending','approved','rejected','cancelled','invalidated','unsatisfiable')),
    ADD CONSTRAINT chk_document_approval_outcome_reason
        CHECK (outcome_reason IS NULL OR outcome_reason IN (
            'quorum','rejected','superseded','cancelled_by_requester','cancelled_by_admin',
            'policy_invalidated','unsatisfiable','cancelled_legacy')),
    ADD CONSTRAINT chk_document_approval_outcome_terminal
        CHECK ((status = 'pending') = (outcome_reason IS NULL));

ALTER TABLE document_approval_step
    DROP CONSTRAINT chk_document_approval_step_status,
    ADD CONSTRAINT chk_document_approval_step_status
        CHECK (status IN ('pending','active','approved','rejected','cancelled','unsatisfiable'));
