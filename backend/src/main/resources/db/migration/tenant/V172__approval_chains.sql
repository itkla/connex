-- Approver chains for generated deal documents (revenue-ops #558/#1297).
-- A policy declares ordered steps, each with a quorum (required_count) and a set of approvers that
-- are either one workspace member or "any member holding DOCUMENT_APPROVE". At request time the
-- chain is snapshotted onto the approval so later policy edits cannot rewrite an in-flight request.
-- user_id columns intentionally carry no foreign key: app_user lives in the control plane and the
-- plane wall forbids the reference, so membership is validated in the service layer (this matches
-- document_approval.requested_by).
ALTER TABLE approval_policy
    ADD COLUMN mode VARCHAR(16) NOT NULL DEFAULT 'sequential' COMMENT 'sequential = one step at a time; parallel = every step active at once',
    ADD COLUMN separation_of_duties VARCHAR(16) NOT NULL DEFAULT 'strict' COMMENT 'strict = neither requester nor document author may decide; requester = only the requester is blocked; off = no constraint',
    ADD CONSTRAINT chk_approval_policy_mode CHECK (mode IN ('sequential', 'parallel')),
    ADD CONSTRAINT chk_approval_policy_sod CHECK (separation_of_duties IN ('strict', 'requester', 'off')),
    ADD UNIQUE KEY uq_approval_policy_workspace_id (workspace_id, id);

CREATE TABLE approval_policy_step (
    id             INT AUTO_INCREMENT PRIMARY KEY,
    workspace_id   INT NOT NULL COMMENT 'Owning workspace',
    policy_id      INT NOT NULL,
    step_order     INT NOT NULL COMMENT 'Position in the chain, ascending from 1',
    name           VARCHAR(255) NULL COMMENT 'Operator label for the step, e.g. Sales manager',
    required_count INT NOT NULL DEFAULT 1 COMMENT 'Distinct approvals needed before the step passes',
    created_at     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_approval_policy_step_policy FOREIGN KEY (workspace_id, policy_id)
        REFERENCES approval_policy(workspace_id, id) ON DELETE CASCADE,
    CONSTRAINT chk_approval_policy_step_required CHECK (required_count >= 1),
    CONSTRAINT chk_approval_policy_step_order CHECK (step_order >= 1),
    UNIQUE KEY uq_approval_policy_step_order (workspace_id, policy_id, step_order),
    UNIQUE KEY uq_approval_policy_step_workspace_id (workspace_id, id)
) DEFAULT CHARSET=utf8mb4 COMMENT='One step of an approval policy chain';

CREATE TABLE approval_policy_step_approver (
    id            INT AUTO_INCREMENT PRIMARY KEY,
    workspace_id  INT NOT NULL COMMENT 'Owning workspace',
    step_id       INT NOT NULL,
    approver_kind VARCHAR(16) NOT NULL COMMENT 'user = the named member; any_approver = any member holding DOCUMENT_APPROVE',
    user_id       INT NULL COMMENT 'Named approver; validated against workspace membership in the service layer',
    created_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_approval_policy_step_approver_step FOREIGN KEY (workspace_id, step_id)
        REFERENCES approval_policy_step(workspace_id, id) ON DELETE CASCADE,
    CONSTRAINT chk_approval_policy_step_approver_kind CHECK (approver_kind IN ('user', 'any_approver')),
    CONSTRAINT chk_approval_policy_step_approver_user CHECK (
        (approver_kind = 'user' AND user_id IS NOT NULL)
        OR (approver_kind = 'any_approver' AND user_id IS NULL)),
    UNIQUE KEY uq_approval_policy_step_approver (workspace_id, step_id, approver_kind, user_id)
) DEFAULT CHARSET=utf8mb4 COMMENT='Who may decide one approval-policy step';

ALTER TABLE document_approval
    ADD COLUMN mode VARCHAR(16) NOT NULL DEFAULT 'sequential' COMMENT 'Chain mode copied off the matching policy when approval was requested',
    ADD COLUMN separation_of_duties VARCHAR(16) NOT NULL DEFAULT 'strict' COMMENT 'Separation-of-duties rule copied off the matching policy when approval was requested',
    ADD CONSTRAINT chk_document_approval_mode CHECK (mode IN ('sequential', 'parallel')),
    ADD CONSTRAINT chk_document_approval_sod CHECK (separation_of_duties IN ('strict', 'requester', 'off')),
    ADD UNIQUE KEY uq_document_approval_workspace_id (workspace_id, id);

CREATE TABLE document_approval_step (
    id             INT AUTO_INCREMENT PRIMARY KEY,
    workspace_id   INT NOT NULL COMMENT 'Owning workspace',
    approval_id    INT NOT NULL,
    step_order     INT NOT NULL,
    name           VARCHAR(255) NULL,
    required_count INT NOT NULL DEFAULT 1,
    status         VARCHAR(16) NOT NULL DEFAULT 'pending',
    decided_at     DATETIME NULL COMMENT 'When the step reached quorum or was rejected',
    created_at     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_document_approval_step_approval FOREIGN KEY (workspace_id, approval_id)
        REFERENCES document_approval(workspace_id, id) ON DELETE CASCADE,
    CONSTRAINT chk_document_approval_step_status CHECK (status IN ('pending', 'active', 'approved', 'rejected', 'cancelled')),
    CONSTRAINT chk_document_approval_step_required CHECK (required_count >= 1),
    UNIQUE KEY uq_document_approval_step_order (workspace_id, approval_id, step_order),
    UNIQUE KEY uq_document_approval_step_workspace_id (workspace_id, id),
    UNIQUE KEY uq_document_approval_step_owner (workspace_id, approval_id, id)
) DEFAULT CHARSET=utf8mb4 COMMENT='Frozen chain step of one approval request';

CREATE TABLE document_approval_step_approver (
    id            INT AUTO_INCREMENT PRIMARY KEY,
    workspace_id  INT NOT NULL COMMENT 'Owning workspace',
    step_id       INT NOT NULL,
    approver_kind VARCHAR(16) NOT NULL,
    user_id       INT NULL COMMENT 'Named approver; validated against workspace membership in the service layer',
    created_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_document_approval_step_approver_step FOREIGN KEY (workspace_id, step_id)
        REFERENCES document_approval_step(workspace_id, id) ON DELETE CASCADE,
    CONSTRAINT chk_document_approval_step_approver_kind CHECK (approver_kind IN ('user', 'any_approver')),
    CONSTRAINT chk_document_approval_step_approver_user CHECK (
        (approver_kind = 'user' AND user_id IS NOT NULL)
        OR (approver_kind = 'any_approver' AND user_id IS NULL)),
    UNIQUE KEY uq_document_approval_step_approver (workspace_id, step_id, approver_kind, user_id)
) DEFAULT CHARSET=utf8mb4 COMMENT='Frozen approver assignment for one approval step';

CREATE TABLE document_approval_decision (
    id           INT AUTO_INCREMENT PRIMARY KEY,
    workspace_id INT NOT NULL COMMENT 'Owning workspace',
    approval_id  INT NOT NULL,
    step_id      INT NOT NULL,
    decision     VARCHAR(16) NOT NULL,
    decided_by   INT NOT NULL COMMENT 'Deciding user; validated against workspace membership in the service layer',
    comment      VARCHAR(1000) NULL,
    decided_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_document_approval_decision_step FOREIGN KEY (workspace_id, approval_id, step_id)
        REFERENCES document_approval_step(workspace_id, approval_id, id) ON DELETE CASCADE,
    CONSTRAINT chk_document_approval_decision CHECK (decision IN ('approved', 'rejected')),
    UNIQUE KEY uq_document_approval_decision_approver (workspace_id, step_id, decided_by),
    INDEX idx_document_approval_decision_approval (workspace_id, approval_id, id)
) DEFAULT CHARSET=utf8mb4 COMMENT='Immutable per-approver decision history within an approval chain';

-- Give every already-persisted approval the one-step chain it implicitly had, so reads never need a
-- legacy branch. Pending rows keep an active step; terminal rows mirror their outcome.
INSERT INTO document_approval_step (workspace_id, approval_id, step_order, required_count, status, decided_at, created_at)
SELECT workspace_id, id, 1, 1,
       CASE status
           WHEN 'pending' THEN 'active'
           WHEN 'approved' THEN 'approved'
           WHEN 'rejected' THEN 'rejected'
           ELSE 'cancelled'
       END,
       decided_at, created_at
FROM document_approval;

INSERT INTO document_approval_step_approver (workspace_id, step_id, approver_kind, user_id)
SELECT workspace_id, id, 'any_approver', NULL FROM document_approval_step;

INSERT INTO document_approval_decision (workspace_id, approval_id, step_id, decision, decided_by, comment, decided_at)
SELECT a.workspace_id, a.id, s.id, a.status, a.decided_by, a.decision_comment, COALESCE(a.decided_at, a.updated_at)
FROM document_approval a
JOIN document_approval_step s ON s.workspace_id = a.workspace_id AND s.approval_id = a.id
WHERE a.status IN ('approved', 'rejected') AND a.decided_by IS NOT NULL;

-- Database fence for the rolling-deployment window: a binary that predates this migration decides an
-- approval by updating document_approval alone, which would bypass named approvers, quorum, and step
-- order on a chained request. Refuse that transition in the database, including the empty-chain case:
-- an approval frozen by such a binary carries no steps at all, and approving it would otherwise pass
-- vacuously. The chained runtime always freezes at least one step and marks the final step approved
-- before it approves the request, so this never fires on a legitimate write.
-- Rejection and cancellation stay unfenced: both are terminal outcomes that need no chain agreement.
DELIMITER //
CREATE TRIGGER trg_document_approval_chain_fence
BEFORE UPDATE ON document_approval
FOR EACH ROW
BEGIN
    IF NEW.status = 'approved' AND OLD.status <> 'approved'
            AND (NOT EXISTS (
                    SELECT 1 FROM document_approval_step s
                    WHERE s.workspace_id = NEW.workspace_id
                      AND s.approval_id = NEW.id)
                OR EXISTS (
                    SELECT 1 FROM document_approval_step s
                    WHERE s.workspace_id = NEW.workspace_id
                      AND s.approval_id = NEW.id
                      AND s.status <> 'approved')) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'document_approval cannot be approved while chain steps are unapproved';
    END IF;
END//
DELIMITER ;
