-- #440 increment 3 (PR2): the control-plane wall. Drops every foreign key
-- from an org-data table onto a control-plane table (app_user, workspace,
-- workspace_member), so the org-data schema is identical to what a per-org
-- catalog will carry in Phase 4 (#313) — no cross-catalog references exist.
-- The app_user / workspace_member edge semantics live in the service layer
-- since PR1 (#463): UserOffboardingService (account deletion),
-- detachMemberContent (membership removal), prepareFreshMembership (rejoin
-- ghosts), and the FOR UPDATE authored-content guard (RESTRICT mirror).
-- The workspace_id edges have NO runtime replacement because no code path
-- deletes workspace rows today; workspace/org offboarding (including
-- ai_output_cache erasure, whose only lifecycle was the dropped CASCADE)
-- must ship as an explicit teardown before any workspace deletion feature —
-- recorded on #440.
--
-- Deliberately NOT dropped (intra-control-plane after the audit_log /
-- workspace_role / appi_incident placement decisions on #440):
--   audit_log.actor_id / workspace_id / org_id, audit_log_integrity_*,
--   workspace_member.role_id -> workspace_role, appi_incident.created_by /
--   updated_by, app_user.last_active_workspace_id, notification_preference,
--   and every identity/membership/session FK.
--
-- MySQL keeps each FK's implicitly-created index when the constraint is
-- dropped; OffboardingIndexArchTest pins every user-reference index and
-- TablePlaneArchTest asserts no cross-plane FK ever comes back.

-- One ALTER per table: each statement is a single metadata-only DDL, so a
-- mid-file failure (e.g. a metadata-lock timeout on the staging auto-deployer)
-- leaves whole tables either done or untouched. Recovery from a partial run:
-- `flyway repair`, delete the already-executed ALTERs from a copy of this
-- file, and hand-finish the remainder — MySQL has no DROP FOREIGN KEY IF
-- EXISTS.

ALTER TABLE activity
    DROP FOREIGN KEY fk_activity_created_by,
    DROP FOREIGN KEY fk_activity_workspace;

ALTER TABLE ai_output_cache
    DROP FOREIGN KEY fk_ai_output_cache_workspace;

ALTER TABLE attachment
    DROP FOREIGN KEY fk_attachment_uploaded_by,
    DROP FOREIGN KEY fk_attachment_workspace;

ALTER TABLE company
    DROP FOREIGN KEY fk_company_workspace;

ALTER TABLE company_share
    DROP FOREIGN KEY fk_company_share_granted_by,
    DROP FOREIGN KEY fk_company_share_workspace;

ALTER TABLE custom_field_definition
    DROP FOREIGN KEY fk_cfd_workspace;

ALTER TABLE custom_field_value
    DROP FOREIGN KEY fk_cfv_workspace;

ALTER TABLE deal
    DROP FOREIGN KEY fk_deal_owner,
    DROP FOREIGN KEY fk_deal_workspace;

ALTER TABLE deal_collaborator
    DROP FOREIGN KEY fk_deal_collaborator_member;

ALTER TABLE introduction
    DROP FOREIGN KEY fk_introduction_introducer,
    DROP FOREIGN KEY fk_introduction_workspace;

ALTER TABLE note
    DROP FOREIGN KEY fk_note_author,
    DROP FOREIGN KEY fk_note_workspace;

ALTER TABLE notification
    DROP FOREIGN KEY fk_notification_actor,
    DROP FOREIGN KEY fk_notification_recipient_member;

ALTER TABLE person
    DROP FOREIGN KEY fk_person_workspace;

ALTER TABLE person_edge
    DROP FOREIGN KEY fk_person_edge_workspace;

ALTER TABLE person_employment
    DROP FOREIGN KEY fk_person_employment_workspace;

ALTER TABLE person_share
    DROP FOREIGN KEY fk_person_share_granted_by,
    DROP FOREIGN KEY fk_person_share_workspace;

ALTER TABLE pipeline
    DROP FOREIGN KEY fk_pipeline_workspace;

ALTER TABLE pipeline_share
    DROP FOREIGN KEY fk_pipeline_share_granted_by,
    DROP FOREIGN KEY fk_pipeline_share_workspace;

ALTER TABLE rule
    DROP FOREIGN KEY fk_rule_created_by,
    DROP FOREIGN KEY fk_rule_run_as_user,
    DROP FOREIGN KEY fk_rule_workspace;

ALTER TABLE rule_execution
    DROP FOREIGN KEY fk_rule_execution_workspace;

ALTER TABLE saved_view
    DROP FOREIGN KEY fk_saved_view_user,
    DROP FOREIGN KEY fk_saved_view_workspace;

ALTER TABLE stage
    DROP FOREIGN KEY fk_stage_workspace;

ALTER TABLE tag
    DROP FOREIGN KEY fk_tag_workspace;

ALTER TABLE task
    DROP FOREIGN KEY fk_task_assigned_to,
    DROP FOREIGN KEY fk_task_workspace;

ALTER TABLE user_dashboard
    DROP FOREIGN KEY fk_user_dashboard_user,
    DROP FOREIGN KEY fk_user_dashboard_workspace;
