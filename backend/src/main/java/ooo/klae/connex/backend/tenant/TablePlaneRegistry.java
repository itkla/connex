package ooo.klae.connex.backend.tenant;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Canonical table-level plane classification for the control-plane split
 * (#440 increment 3 / #313 Phase 3). Control-plane tables hold identity,
 * membership, session, org and instance configuration and never move into a
 * per-org catalog; org-data tables hold workspace-scoped tenant content and
 * are exactly what a Phase 4 per-org catalog will carry. The wall between the
 * planes is structural: no foreign key may cross it in either direction
 * ({@code TablePlaneArchTest} enforces both the partition's completeness
 * against the live schema and the absence of cross-plane foreign keys).
 *
 * <p>Placement decisions recorded on #440: {@code audit_log} (and its
 * integrity chain) is whole-table control-plane — its append-only trigger
 * means the {@code ON DELETE SET NULL} cascade is the only mechanism that can
 * ever null {@code actor_id}, so its foreign keys must stay; {@code
 * workspace_role} is control-plane so {@code workspace_member.role_id} keeps
 * its constraint; {@code appi_incident} and {@code data_subject_request} are
 * control-plane so they stay writable during an org-catalog outage they may be
 * describing.
 *
 * <p>This registry classifies TABLES for the physical split; it is orthogonal
 * to {@code TenantScopeInterceptor}'s mapper-namespace sets, which classify
 * STATEMENTS for scope enforcement. A mapper can stay workspace-scope-enforced
 * (e.g. {@code AuditLogMapper}, {@code RoleMapper}) while every table it
 * touches lives on the control plane — the enforcement backstop and the plane
 * wall protect different invariants.
 */
public final class TablePlaneRegistry {

    private TablePlaneRegistry() {
    }

    /** Identity, membership, session, org and instance configuration. */
    public static final Set<String> CONTROL_PLANE_STATE_TABLES = Set.of(
        "SPRING_SESSION",
        "SPRING_SESSION_ATTRIBUTES",
        "ai_provider_config",
        "organization_ai_budget",
        "organization_ai_budget_reservation",
        "organization_ai_budget_usage",
        "app_user",
        "appi_incident",
        "auth_logout_audit_claim",
        "audit_log",
        "audit_log_integrity_checkpoint",
        "audit_log_integrity_head",
        "data_subject_request",
        "email_change_token",
        "federated_identity",
        "notification_quiet_hours",
        "notification_preference",
        "notification_recipient_state",
        "provider_native_connect_session",
        "object_storage_backend_identity",
        "one_time_link_flow",
        "org_allowed_domain",
        "org_member",
        "org_placement",
        "organization",
        "organization_duplicate_decision_lock",
        "password_reset_token",
        "registration_verification_token",
        "provider_connection",
        "secret_value",
        "sso_connection",
        "sso_domain",
        "sso_domain_mutation_lock",
        "sso_link_challenge",
        "tenant_cleanup_tombstone",
        "tenant_export_admission_control",
        "tenant_export_download_grant",
        "tenant_operation_lease",
        "user_object_deletion_queue",
        "webauthn_credential",
        "webauthn_user_entity",
        "workspace",
        "workspace_allowed_domain",
        "workspace_invite",
        "workspace_invite_link",
        "workspace_invite_link_redemption",
        "workspace_mail_config",
        "workspace_member",
        "workspace_role",
        "workspace_role_permission");

    /** Workspace-owned holdings retained on the control plane by an explicit placement decision. */
    public static final Set<String> CONTROL_PLANE_WORKSPACE_DATA_TABLES = Set.of(
        "client_error");

    /** All physical control-plane tables. */
    public static final Set<String> CONTROL_PLANE_TABLES = controlPlaneTables();

    /** Direct workspace-keyed control state that is not tenant-export content. */
    public static final Set<String> CONTROL_PLANE_WORKSPACE_STATE_TABLES = Set.of(
        "audit_log",
        "secret_value",
        "tenant_cleanup_tombstone",
        "tenant_export_download_grant",
        "tenant_operation_lease",
        "workspace_allowed_domain",
        "workspace_invite",
        "workspace_invite_link",
        "workspace_mail_config",
        "workspace_member",
        "workspace_role");

    /** Workspace-scoped tenant content — the future per-org catalog. */
    public static final Set<String> ORG_DATA_TABLES = Set.of(
        "activity",
        "ai_chat_message",
        "ai_chat_session",
        "ai_chat_session_participant",
        "ai_chat_tool_call",
        "ai_chat_turn",
        "ai_output_cache",
        "ai_workspace_governance",
        "approval_policy",
        "approval_policy_step",
        "approval_policy_step_approver",
        "attachment",
        "attachment_tag",
        "business_card_import_request",
        "campaign",
        "campaign_audience",
        "campaign_audience_member",
        "campaign_audience_snapshot",
        "campaign_delivery",
        "campaign_delivery_event",
        "campaign_message",
        "campaign_message_revision",
        "campaign_send",
        "campaign_audience_export",
        "company",
        "company_identity",
        "company_share",
        "company_tag",
        "custom_field_definition",
        "custom_field_value",
        "contact_channel_consent",
        "contact_channel_consent_event",
        "deal",
        "deal_collaborator",
        "deal_document",
        "deal_duplicate_review_proof",
        "deal_line_item",
        "deal_person",
        "deal_stage_history",
        "deal_tag",
        "connector_config",
        "delivery_provider_config",
        "document_approval",
        "document_approval_decision",
        "document_approval_step",
        "document_approval_step_approver",
        "document_delivery",
        "document_delivery_artifact",
        "document_delivery_event",
        "document_delivery_recipient",
        "document_delivery_request",
        "document_template",
        "product",
        "entity_reference",
        "historical_notification_baseline",
        "identity_collision",
        "introduction",
        "job_run",
        "managed_object_usage",
        "note",
        "notification",
        "object_deletion_queue",
        "object_storage_quota",
        "person",
        "person_identity",
        "person_edge",
        "person_employment",
        "person_share",
        "person_tag",
        "pipeline",
        "pipeline_share",
        "provider_activity_projection",
        "provider_capture_sync_state",
        "provider_capture_user_policy",
        "provider_capture_workspace_policy",
        "provider_captured_interaction",
        "provider_captured_participant",
        "provider_participant_decision",
        "record_comment",
        "record_comment_reaction",
        "record_comment_thread",
        "report_definition",
        "report_goal",
        "report_schedule",
        "report_snapshot",
        "relationship_signal",
        "relationship_signal_family_state",
        "relationship_signal_state",
        "rule",
        "rule_execution",
        "saved_view",
        "saved_view_default",
        "saved_view_pin",
        "stage",
        "suppression_entry",
        "tag",
        "task",
        "task_board_lock",
        "user_dashboard",
        "warm_path_dismissal",
        "workflow",
        "workflow_intervention",
        "workflow_invocation",
        "workflow_invocation_record",
        "workflow_recipe_origin",
        "workflow_run",
        "workflow_runtime_workspace",
        "workflow_step_attempt",
        "workflow_step_run",
        "workflow_trigger_outbox",
        "workflow_version");

    private static Set<String> controlPlaneTables() {
        Set<String> tables = new HashSet<>(CONTROL_PLANE_STATE_TABLES);
        tables.addAll(CONTROL_PLANE_WORKSPACE_DATA_TABLES);
        return Collections.unmodifiableSet(tables);
    }
}
