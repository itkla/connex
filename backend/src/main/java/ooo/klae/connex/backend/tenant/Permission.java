package ooo.klae.connex.backend.tenant;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;

/**
 * The fixed catalog of workspace permissions. Built-in roles map to preset
 * bundles of these; owner-defined custom roles select any subset. Most reads
 * are governed by workspace membership; explicitly permissioned shared
 * surfaces such as reports and audit history also appear here.
 */
public enum Permission {
    COMPANY_CREATE,
    COMPANY_UPDATE,
    COMPANY_DELETE,
    PERSON_CREATE,
    PERSON_UPDATE,
    PERSON_DELETE,
    DEAL_CREATE,
    DEAL_UPDATE,
    DEAL_DELETE,
    ACTIVITY_CREATE,
    ACTIVITY_UPDATE,
    ACTIVITY_DELETE,
    NOTE_CREATE,
    NOTE_UPDATE,
    NOTE_DELETE,
    TASK_CREATE,
    TASK_UPDATE,
    TASK_DELETE,
    ATTACHMENT_CREATE,
    ATTACHMENT_DELETE,
    REPORT_READ,
    REPORT_CREATE,
    REPORT_UPDATE,
    REPORT_DELETE,
    GOAL_READ,
    GOAL_MANAGE,
    PIPELINE_MANAGE,
    TAG_MANAGE,
    PRODUCT_MANAGE,
    DOCUMENT_MANAGE,
    /**
     * Permission to approve or reject generated deal documents that an approval
     * policy has routed for review. Requesting approval only needs
     * {@link #DEAL_UPDATE}; policy configuration stays {@link #DOCUMENT_MANAGE}.
     */
    DOCUMENT_APPROVE,
    CUSTOM_FIELD_MANAGE,
    SHARE_MANAGE,
    MEMBER_MANAGE,
    ROLE_MANAGE,
    AUDIT_READ,
    WORKSPACE_SETTINGS,
    RULE_MANAGE,
    CAMPAIGN_VIEW,
    CAMPAIGN_MANAGE,
    CAMPAIGN_SEND,
    CONSENT_MANAGE,
    /**
     * Permission to invoke AI-powered features (e.g. account/meeting briefs).
     * Grantable; the instance flag and per-org BYOP provider config additionally
     * gate actual invocation via {@code AiFeatureGate}.
     */
    AI_USE,
    /**
     * Permission to make an owned assistant session shared and invite workspace members into it.
     * Participants still need {@link #AI_USE} to join, read, or contribute to the session.
     */
    AI_SESSION_SHARE,
    /**
     * Permission to read assistant sessions retained after their creator is no longer an active
     * workspace member. This permission never authorizes session mutation or turn execution.
     */
    AI_SESSION_ADMIN,
    /**
     * Inert. SSO is org-scoped configuration, authorized against org membership
     * (see {@code OrgMemberService.requireOrgAdmin}), not this workspace-level
     * permission. Retained only so stored custom-role rows referencing it still
     * parse; it is excluded from the grantable catalog and must never gate an
     * endpoint again — doing so re-opens the #316 escalation.
     */
    SSO_MANAGE,
    /**
     * Inert. There is no workspace-delete endpoint, so this permission must not
     * be granted, displayed, or used as an authorization gate.
     */
    WORKSPACE_DELETE;

    private static final EnumSet<Permission> INERT = EnumSet.of(SSO_MANAGE, WORKSPACE_DELETE);
    private static final EnumSet<Permission> GRANTABLE = EnumSet.complementOf(INERT);

    public static boolean isGrantable(Permission permission) {
        return permission != null && !INERT.contains(permission);
    }

    public static EnumSet<Permission> grantableSet() {
        return EnumSet.copyOf(GRANTABLE);
    }

    public static List<String> grantableNames() {
        return Arrays.stream(values())
            .filter(Permission::isGrantable)
            .map(Enum::name)
            .toList();
    }
}
