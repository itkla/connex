package ooo.klae.connex.backend.tenant;

/**
 * The fixed catalog of workspace permissions. Built-in roles map to preset
 * bundles of these; owner-defined custom roles select any subset. Reads are
 * governed by workspace membership, so only state-changing and management
 * actions appear here.
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
    PIPELINE_MANAGE,
    TAG_MANAGE,
    CUSTOM_FIELD_MANAGE,
    SHARE_MANAGE,
    MEMBER_MANAGE,
    ROLE_MANAGE,
    AUDIT_READ,
    WORKSPACE_SETTINGS,
    RULE_MANAGE,
    WORKSPACE_DELETE
}
