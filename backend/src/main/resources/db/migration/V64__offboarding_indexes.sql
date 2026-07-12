-- #440 increment 3 (PR1): recipient- and user-leading indexes for the
-- service-layer offboarding statements. Every other user-reference column the
-- offboarding fan-out touches already has a leading index (explicit or
-- FK-created); these two tables only had composite indexes leading with
-- workspace_id, so the cross-workspace erasures would scan and next-key-lock
-- the whole table. The follow-up FK-drop migration must keep every
-- user-reference index alive (OffboardingIndexArchTest pins them).

CREATE INDEX idx_notification_recipient ON notification (recipient_id);
CREATE INDEX idx_deal_collaborator_user_only ON deal_collaborator (user_id);
