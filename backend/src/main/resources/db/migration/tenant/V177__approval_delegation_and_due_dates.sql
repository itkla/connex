-- Approval steps gain a deadline and an explicit expiry behaviour, plus one appended-facts table
-- recording delegation, escalation, and reassignment without ever rewriting the frozen chain
-- (revenue-ops #558/#1302). user_id columns intentionally carry no foreign key: app_user lives in
-- the control plane and the plane wall forbids that edge, so membership is validated in the service
-- layer, matching document_approval.requested_by and document_approval_step_approver.
-- Every new column is nullable or defaulted, so a chain frozen by an older binary simply carries no
-- deadline: it never expires and is never reminded. That is also the product rule for a policy step
-- with no declared interval, so no backfill is needed or wanted.

ALTER TABLE approval_policy_step
    ADD COLUMN due_interval_hours INT NULL COMMENT 'Hours a step may stay active before it is due; NULL means the step never expires and is never reminded',
    ADD COLUMN on_expiry VARCHAR(16) NOT NULL DEFAULT 'expire' COMMENT 'expire = terminate the request; escalate = widen the step to every approver, once',
    ADD CONSTRAINT chk_approval_policy_step_due_interval
        CHECK (due_interval_hours IS NULL OR (due_interval_hours >= 1 AND due_interval_hours <= 8760)),
    ADD CONSTRAINT chk_approval_policy_step_on_expiry
        CHECK (on_expiry IN ('expire', 'escalate')),
    ADD CONSTRAINT chk_approval_policy_step_expiry_needs_due
        CHECK (due_interval_hours IS NOT NULL OR on_expiry = 'expire');

ALTER TABLE document_approval_step
    ADD COLUMN due_interval_hours INT NULL COMMENT 'Deadline interval copied off the policy step when the chain was frozen',
    ADD COLUMN on_expiry VARCHAR(16) NOT NULL DEFAULT 'expire' COMMENT 'Expiry behaviour copied off the policy step when the chain was frozen',
    ADD COLUMN activated_at DATETIME NULL COMMENT 'When the step opened for decisions',
    ADD COLUMN due_at DATETIME NULL COMMENT 'Absolute deadline stamped when the step opened; cleared once the step escalates',
    ADD COLUMN escalated_at DATETIME NULL COMMENT 'When the step widened after passing its deadline; a step escalates at most once',
    ADD COLUMN reminded_round TINYINT NOT NULL DEFAULT 0 COMMENT 'Highest reminder round already emitted for this step',
    ADD CONSTRAINT chk_document_approval_step_due_interval
        CHECK (due_interval_hours IS NULL OR (due_interval_hours >= 1 AND due_interval_hours <= 8760)),
    ADD CONSTRAINT chk_document_approval_step_on_expiry
        CHECK (on_expiry IN ('expire', 'escalate')),
    ADD CONSTRAINT chk_document_approval_step_expiry_needs_due
        CHECK (due_interval_hours IS NOT NULL OR on_expiry = 'expire'),
    ADD CONSTRAINT chk_document_approval_step_due_activated
        CHECK (due_at IS NULL OR activated_at IS NOT NULL),
    ADD CONSTRAINT chk_document_approval_step_escalated_clears_due
        CHECK (escalated_at IS NULL OR due_at IS NULL),
    ADD CONSTRAINT chk_document_approval_step_reminded_round
        CHECK (reminded_round >= 0 AND reminded_round <= 3),
    ADD INDEX idx_document_approval_step_due (workspace_id, status, due_at);

-- Widen the two vocabularies V174 established, by their V174 constraint names.
ALTER TABLE document_approval_step
    DROP CONSTRAINT chk_document_approval_step_status,
    ADD CONSTRAINT chk_document_approval_step_status
        CHECK (status IN ('pending','active','approved','rejected','cancelled','unsatisfiable','expired'));

ALTER TABLE document_approval
    DROP CONSTRAINT chk_document_approval_status,
    ADD CONSTRAINT chk_document_approval_status
        CHECK (status IN ('pending','approved','rejected','cancelled','invalidated','unsatisfiable','expired')),
    DROP CONSTRAINT chk_document_approval_outcome_reason,
    ADD CONSTRAINT chk_document_approval_outcome_reason
        CHECK (outcome_reason IS NULL OR outcome_reason IN (
            'quorum','rejected','superseded','cancelled_by_requester','cancelled_by_admin',
            'policy_invalidated','unsatisfiable','cancelled_legacy','expired'));

-- Appended approver facts. A delegation moves one approver's seat; an escalation widens the current
-- set; a reassignment opens a new round whose rows replace the frozen set entirely. Nothing here
-- ever updates or deletes a row of the frozen chain.
CREATE TABLE document_approval_step_assignment (
    id                   INT AUTO_INCREMENT PRIMARY KEY,
    workspace_id         INT NOT NULL COMMENT 'Owning workspace',
    approval_id          INT NOT NULL,
    step_id              INT NOT NULL,
    assignment_kind      VARCHAR(16) NOT NULL COMMENT 'delegation = one approver hands their seat over; escalation = the set is widened; reassignment = the set is replaced',
    assignment_round     INT NOT NULL DEFAULT 0 COMMENT 'Reassignment generation this row belongs to; delegation rows are always 0',
    approver_kind        VARCHAR(16) NOT NULL COMMENT 'user = the named member; any_approver = any member holding DOCUMENT_APPROVE',
    user_id              INT NULL COMMENT 'Named approver; validated against workspace membership in the service layer',
    delegated_by_user_id INT NULL COMMENT 'Approver who handed their seat over; set only on delegation rows',
    created_by_user_id   INT NULL COMMENT 'Actor who appended this fact; NULL when the scheduled sweep escalated the step',
    comment              VARCHAR(500) NULL,
    created_at           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_document_approval_step_assignment_step FOREIGN KEY (workspace_id, approval_id, step_id)
        REFERENCES document_approval_step(workspace_id, approval_id, id) ON DELETE CASCADE,
    CONSTRAINT chk_document_approval_step_assignment_kind
        CHECK (assignment_kind IN ('delegation', 'escalation', 'reassignment')),
    CONSTRAINT chk_document_approval_step_assignment_approver
        CHECK (approver_kind IN ('user', 'any_approver')),
    CONSTRAINT chk_document_approval_step_assignment_user CHECK (
        (approver_kind = 'user' AND user_id IS NOT NULL)
        OR (approver_kind = 'any_approver' AND user_id IS NULL)),
    CONSTRAINT chk_document_approval_step_assignment_delegation CHECK (
        (assignment_kind = 'delegation' AND delegated_by_user_id IS NOT NULL
            AND approver_kind = 'user' AND assignment_round = 0)
        OR (assignment_kind <> 'delegation' AND delegated_by_user_id IS NULL)),
    CONSTRAINT chk_document_approval_step_assignment_round CHECK (assignment_round >= 0),
    UNIQUE KEY uq_document_approval_step_assignment_delegator (workspace_id, step_id, delegated_by_user_id),
    INDEX idx_document_approval_step_assignment_step (workspace_id, step_id, id)
) DEFAULT CHARSET=utf8mb4 COMMENT='Appended approver facts layered over one frozen approval step';
