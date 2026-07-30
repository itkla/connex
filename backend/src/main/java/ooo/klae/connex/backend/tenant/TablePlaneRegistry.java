package ooo.klae.connex.backend.tenant;

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
    public static final Set<String> CONTROL_PLANE_TABLES = Set.of(
        "SPRING_SESSION",
        "SPRING_SESSION_ATTRIBUTES",
        "ai_provider_config",
        "app_user",
        "appi_incident",
        "audit_log",
        "audit_log_integrity_checkpoint",
        "audit_log_integrity_head",
        "data_subject_request",
        "email_change_token",
        "federated_identity",
        "notification_quiet_hours",
        "notification_preference",
        "notification_recipient_state",
        "object_storage_backend_identity",
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

    /** Workspace-scoped tenant content — the future per-org catalog. */
    public static final Set<String> ORG_DATA_TABLES = Set.of(
        "activity",
        "ai_output_cache",
        "approval_policy",
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
        "deal_line_item",
        "deal_person",
        "deal_stage_history",
        "deal_tag",
        "connector_config",
        "delivery_provider_config",
        "document_approval",
        "document_template",
        "product",
        "entity_reference",
        "identity_collision",
        "introduction",
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
        "report_definition",
        "report_goal",
        "report_schedule",
        "report_snapshot",
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
        "workflow_version");
}
