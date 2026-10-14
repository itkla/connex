-- Departure guards discover candidate step ids before loading complete routing facts. User-leading
-- covering indexes keep both workspace departure and catalog erasure proportional to references
-- mentioning the departing user, including either end of a delegation. Existing step-leading
-- indexes still serve the complete routing-fact load and approval writers.
CREATE INDEX idx_approval_policy_step_approver_departure
    ON approval_policy_step_approver (user_id, workspace_id, step_id);

CREATE INDEX idx_document_approval_step_approver_departure
    ON document_approval_step_approver (user_id, workspace_id, step_id);

CREATE INDEX idx_document_approval_step_assignment_departure_user
    ON document_approval_step_assignment (user_id, workspace_id, step_id);

CREATE INDEX idx_document_approval_step_assignment_departure_delegator
    ON document_approval_step_assignment (delegated_by_user_id, workspace_id, step_id);
