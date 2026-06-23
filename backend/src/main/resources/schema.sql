-- ============================================================================
-- Connex CRM : MySQL Schema
-- This file is the source of truth, and should be defined first before any Java entity classes or MyBatis mappers. DO NOT FORGET TO CHANGE THIS BAD BOY
-- ============================================================================


SET FOREIGN_KEY_CHECKS = 0;

-- DROP DATABASE IF EXISTS connexdb; is set due to testing. I will remove it later because application.yml specifies recreating the database on every run
DROP DATABASE IF EXISTS connexdb;
CREATE DATABASE connexdb CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE connexdb;

DROP TABLE IF EXISTS audit_log;
DROP TABLE IF EXISTS notification_preference;
DROP TABLE IF EXISTS notification;
DROP TABLE IF EXISTS deal_collaborator;
DROP TABLE IF EXISTS attachment_tag;
DROP TABLE IF EXISTS attachment;
DROP TABLE IF EXISTS deal_tag;
DROP TABLE IF EXISTS company_tag;
DROP TABLE IF EXISTS person_tag;
DROP TABLE IF EXISTS deal_person;
DROP TABLE IF EXISTS note;
DROP TABLE IF EXISTS task;
DROP TABLE IF EXISTS activity;
DROP TABLE IF EXISTS deal;
DROP TABLE IF EXISTS stage;
DROP TABLE IF EXISTS pipeline;
DROP TABLE IF EXISTS person;
DROP TABLE IF EXISTS tag;
DROP TABLE IF EXISTS company;
DROP TABLE IF EXISTS workspace_member;
DROP TABLE IF EXISTS workspace;
DROP TABLE IF EXISTS app_user;

SET FOREIGN_KEY_CHECKS = 1;


-- ----------------------------------------------------------------------------
-- app_user :  Connex account holder. Mapped to User bean. NOT Person (contacts)
-- ----------------------------------------------------------------------------
CREATE TABLE app_user (
    id              INT AUTO_INCREMENT PRIMARY KEY COMMENT 'User ID',
    username        VARCHAR(64) NOT NULL UNIQUE COMMENT 'Username',
    display_name    VARCHAR(128) NOT NULL COMMENT 'Display name for UI',
    email           VARCHAR(255) NOT NULL UNIQUE COMMENT 'Email address',
    password_hash   VARCHAR(255) COMMENT 'Hashed password',
    last_login_at   DATETIME COMMENT 'Most recent login timestamp',
    profile_picture_url VARCHAR(2048) COMMENT 'Profile picture URL',
    timezone        VARCHAR(64) NOT NULL DEFAULT 'UTC' COMMENT 'IANA timezone',
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Creation timestamp',
    updated_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Last update timestamp'
) DEFAULT CHARSET=utf8mb4 COMMENT='Application users';


-- ----------------------------------------------------------------------------
-- workspace : tenant boundary for workspace-scoped records.
-- ----------------------------------------------------------------------------
CREATE TABLE workspace (
    id          INT AUTO_INCREMENT PRIMARY KEY COMMENT 'Workspace ID',
    name        VARCHAR(128) NOT NULL COMMENT 'Workspace name',
    slug        VARCHAR(128) NOT NULL UNIQUE COMMENT 'Workspace slug',
    created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Creation timestamp',
    updated_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Last update timestamp'
) DEFAULT CHARSET=utf8mb4 COMMENT='Workspaces';


-- ----------------------------------------------------------------------------
-- workspace_member : users belonging to a workspace.
-- ----------------------------------------------------------------------------
CREATE TABLE workspace_member (
    workspace_id INT NOT NULL COMMENT 'Workspace ID',
    user_id      INT NOT NULL COMMENT 'Member User ID',
    role         VARCHAR(32) NOT NULL DEFAULT 'member' COMMENT 'Workspace role',
    created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Membership creation timestamp',
    PRIMARY KEY (workspace_id, user_id),
    CONSTRAINT fk_workspace_member_workspace FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE CASCADE,
    CONSTRAINT fk_workspace_member_user FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE,
    INDEX idx_workspace_member_user (user_id, workspace_id)
) DEFAULT CHARSET=utf8mb4 COMMENT='Workspace memberships';


-- ----------------------------------------------------------------------------
-- company : a business entity. A person (contact) can be linked to a company, and a deal can be associated with a company.
-- ----------------------------------------------------------------------------
CREATE TABLE company (
    id          INT AUTO_INCREMENT PRIMARY KEY COMMENT 'Company ID',
    name        VARCHAR(255) NOT NULL COMMENT 'Company name',
    website     VARCHAR(255) COMMENT 'Company website',
    industry    VARCHAR(128) COMMENT 'Industry',
    phone       VARCHAR(64) COMMENT 'Phone number',
    address     VARCHAR(512) COMMENT 'Address',
    logo_url    VARCHAR(2048) COMMENT 'Logo URL',
    created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Creation timestamp',
    updated_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Last update timestamp'
) DEFAULT CHARSET=utf8mb4 COMMENT='Companies';


-- ----------------------------------------------------------------------------
-- pipeline : a sales process template. Contains stages.
-- ----------------------------------------------------------------------------
CREATE TABLE pipeline (
    id          INT AUTO_INCREMENT PRIMARY KEY COMMENT 'Pipeline ID',
    name        VARCHAR(128) NOT NULL COMMENT 'Pipeline name',
    created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Creation timestamp',
    updated_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Last update timestamp'
) DEFAULT CHARSET=utf8mb4 COMMENT='Pipelines';


-- ----------------------------------------------------------------------------
-- tag : a category or label for organizing entities.
-- ----------------------------------------------------------------------------
CREATE TABLE tag (
    id      INT AUTO_INCREMENT PRIMARY KEY COMMENT 'Tag ID',
    name    VARCHAR(64) NOT NULL UNIQUE COMMENT 'Tag name',
    color   VARCHAR(9) COMMENT 'Hex color code'
) DEFAULT CHARSET=utf8mb4 COMMENT='Tags';


-- ----------------------------------------------------------------------------
-- person : CRM contact. Belongs to a company (optional).
-- ----------------------------------------------------------------------------
CREATE TABLE person (
    id          INT AUTO_INCREMENT PRIMARY KEY COMMENT 'Person ID',
    name        VARCHAR(255) NOT NULL COMMENT 'Person name',
    email       VARCHAR(255) COMMENT 'Person email',
    phone       VARCHAR(64) COMMENT 'Person phone',
    company_id  INT COMMENT 'Company ID',
    title       VARCHAR(128) COMMENT 'Person title',
    image_url   VARCHAR(2048) COMMENT 'Person image URL',
    created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Creation timestamp',
    updated_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Last update timestamp',
    CONSTRAINT fk_person_company FOREIGN KEY (company_id) REFERENCES company(id) ON DELETE SET NULL,
    INDEX idx_person_company (company_id),
    INDEX idx_person_email   (email)
) DEFAULT CHARSET=utf8mb4 COMMENT='Persons';


-- ----------------------------------------------------------------------------
-- stage : a step within a pipeline.
-- ----------------------------------------------------------------------------
CREATE TABLE stage (
    id          INT AUTO_INCREMENT PRIMARY KEY COMMENT 'Stage ID',
    name        VARCHAR(128) NOT NULL COMMENT 'Stage name',
    pipeline_id INT NOT NULL COMMENT 'Pipeline ID',
    position    INT NOT NULL COMMENT 'Stage position',
    is_success  BOOLEAN NOT NULL DEFAULT FALSE COMMENT 'Whether this stage is a success stage',
    is_failure  BOOLEAN NOT NULL DEFAULT FALSE COMMENT 'Whether this stage is a failure stage',
    CONSTRAINT fk_stage_pipeline FOREIGN KEY (pipeline_id) REFERENCES pipeline(id) ON DELETE CASCADE,
    -- a stage may be neither (normal/in-progress), but never both at once
    CONSTRAINT chk_stage_terminal CHECK (NOT (is_success AND is_failure)),
    INDEX idx_stage_pipeline (pipeline_id),
    UNIQUE KEY uq_stage_pipeline_position (pipeline_id, position)
) DEFAULT CHARSET=utf8mb4 COMMENT='Stages';


-- ----------------------------------------------------------------------------
-- deal : a sales opportunity. Lives in a stage of a pipeline; optionally
-- linked to a company.
-- ----------------------------------------------------------------------------
CREATE TABLE deal (
    id                  INT AUTO_INCREMENT PRIMARY KEY COMMENT 'Deal ID',
    workspace_id        INT NOT NULL COMMENT 'Workspace ID',
    owner_id            INT COMMENT 'Owning workspace member User ID',
    name                VARCHAR(255) NOT NULL COMMENT 'Deal name',
    value               DECIMAL(15, 2) NOT NULL DEFAULT 0,
    actual_value        DECIMAL(15, 2) NOT NULL DEFAULT 0,
    currency            VARCHAR(8) NOT NULL DEFAULT 'USD',
    pipeline_id         INT NOT NULL COMMENT 'Pipeline ID',
    stage_id            INT NOT NULL COMMENT 'Stage ID',
    company_id          INT COMMENT 'Company ID',
    expected_close_date DATE COMMENT 'Expected close date',
    closed_at           DATETIME COMMENT 'When the deal was closed (NULL = open). The stage_id at close records where it closed.',
    closed_reason       VARCHAR(255) COMMENT 'Reason the deal was closed (won/lost)',
    won                 BOOLEAN COMMENT 'Outcome when closed: TRUE = won, FALSE = lost, NULL = open. Set by the client and independent of stage — a deal may be won/lost at any stage. closed_at follows this.',
    created_at          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Creation timestamp',
    updated_at          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Last update timestamp',
    CONSTRAINT fk_deal_workspace FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE RESTRICT,
    CONSTRAINT fk_deal_owner FOREIGN KEY (owner_id) REFERENCES app_user(id) ON DELETE SET NULL,
    CONSTRAINT fk_deal_pipeline FOREIGN KEY (pipeline_id) REFERENCES pipeline(id) ON DELETE RESTRICT,
    CONSTRAINT fk_deal_stage    FOREIGN KEY (stage_id)    REFERENCES stage(id)    ON DELETE RESTRICT,
    CONSTRAINT fk_deal_company  FOREIGN KEY (company_id)  REFERENCES company(id)  ON DELETE SET NULL,
    -- a deal has an outcome iff it is closed: won is set exactly when closed_at is set
    CONSTRAINT chk_deal_outcome_closed CHECK ((won IS NULL) = (closed_at IS NULL)),
    CONSTRAINT chk_deal_reason_requires_close CHECK (
        closed_reason IS NULL OR closed_at IS NOT NULL
    ),
    UNIQUE KEY uq_deal_workspace_id (workspace_id, id),
    INDEX idx_deal_workspace (workspace_id),
    INDEX idx_deal_owner (workspace_id, owner_id),
    INDEX idx_deal_reminder (workspace_id, won, expected_close_date),
    INDEX idx_deal_pipeline (pipeline_id),
    INDEX idx_deal_stage    (stage_id),
    INDEX idx_deal_company  (company_id),
    INDEX idx_deal_won      (won)
) DEFAULT CHARSET=utf8mb4 COMMENT='Deals';


-- ----------------------------------------------------------------------------
-- activity : a logged interaction (call, email, meeting). Linked to a person
-- and/or a deal; always created by a user.
-- ----------------------------------------------------------------------------
CREATE TABLE activity (
    id              INT AUTO_INCREMENT PRIMARY KEY COMMENT 'Activity ID',
    type            VARCHAR(32)  NOT NULL COMMENT 'Activity type',            -- "call" | "email" | "meeting" | etc.
    subject         VARCHAR(255) NOT NULL COMMENT 'Activity subject',
    notes           TEXT COMMENT 'Activity notes',
    person_id       INT COMMENT 'Person ID',
    deal_id         INT COMMENT 'Deal ID',
    created_by_id   INT NOT NULL COMMENT 'Created by User ID',
    timestamp       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Activity timestamp',
    CONSTRAINT fk_activity_person     FOREIGN KEY (person_id)     REFERENCES person(id)   ON DELETE SET NULL,
    CONSTRAINT fk_activity_deal       FOREIGN KEY (deal_id)       REFERENCES deal(id)     ON DELETE SET NULL,
    CONSTRAINT fk_activity_created_by FOREIGN KEY (created_by_id) REFERENCES app_user(id) ON DELETE RESTRICT,
    INDEX idx_activity_person     (person_id),
    INDEX idx_activity_deal       (deal_id),
    INDEX idx_activity_created_by (created_by_id),
    INDEX idx_activity_timestamp  (timestamp)
) DEFAULT CHARSET=utf8mb4 COMMENT='Activities';


-- ----------------------------------------------------------------------------
-- task : a to-do item assigned to a user, optionally linked to a person/deal.
-- ----------------------------------------------------------------------------
CREATE TABLE task (
    id              INT AUTO_INCREMENT PRIMARY KEY COMMENT 'Task ID',
    workspace_id    INT NOT NULL COMMENT 'Workspace ID',
    description     TEXT NOT NULL COMMENT 'Task description',
    completed       BOOLEAN NOT NULL DEFAULT FALSE COMMENT 'Completion status',
    due_date        DATE COMMENT 'Due date',
    assigned_to_id  INT NOT NULL COMMENT 'Assigned to User ID',
    person_id       INT COMMENT 'Person ID',
    deal_id         INT COMMENT 'Deal ID',
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Creation timestamp',
    updated_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Last update timestamp',
    CONSTRAINT fk_task_workspace FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE RESTRICT,
    CONSTRAINT fk_task_assigned_member FOREIGN KEY (workspace_id, assigned_to_id) REFERENCES workspace_member(workspace_id, user_id) ON DELETE RESTRICT,
    CONSTRAINT fk_task_person   FOREIGN KEY (person_id)      REFERENCES person(id)   ON DELETE SET NULL,
    CONSTRAINT fk_task_deal FOREIGN KEY (deal_id) REFERENCES deal(id) ON DELETE SET NULL,
    INDEX idx_task_workspace (workspace_id),
    INDEX idx_task_workspace_assigned (workspace_id, assigned_to_id),
    INDEX idx_task_reminder (workspace_id, completed, due_date, assigned_to_id),
    INDEX idx_task_assigned  (assigned_to_id),
    INDEX idx_task_person    (person_id),
    INDEX idx_task_deal      (deal_id),
    INDEX idx_task_due_date  (due_date),
    INDEX idx_task_completed (completed)
) DEFAULT CHARSET=utf8mb4 COMMENT='Tasks';


-- ----------------------------------------------------------------------------
-- note : a free-form text entry created by a user, linked to a person and/or deal.
-- ----------------------------------------------------------------------------
CREATE TABLE note (
    id          INT AUTO_INCREMENT PRIMARY KEY COMMENT 'Note ID',
    content     TEXT NOT NULL COMMENT 'Note content',
    author_id   INT NOT NULL COMMENT 'Author User ID',
    person_id   INT COMMENT 'Person ID',
    deal_id     INT COMMENT 'Deal ID',
    created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Creation timestamp',
    updated_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Last update timestamp',
    CONSTRAINT fk_note_author FOREIGN KEY (author_id) REFERENCES app_user(id) ON DELETE RESTRICT,
    CONSTRAINT fk_note_person FOREIGN KEY (person_id) REFERENCES person(id)   ON DELETE SET NULL,
    CONSTRAINT fk_note_deal   FOREIGN KEY (deal_id)   REFERENCES deal(id)     ON DELETE SET NULL,
    INDEX idx_note_author (author_id),
    INDEX idx_note_person (person_id),
    INDEX idx_note_deal   (deal_id)
) DEFAULT CHARSET=utf8mb4 COMMENT='Notes';


-- ----------------------------------------------------------------------------
-- attachment : a generic file attached to any entity (company, person, deal, user, ...).
-- ----------------------------------------------------------------------------
CREATE TABLE attachment (
    id              INT AUTO_INCREMENT PRIMARY KEY COMMENT 'Attachment ID',
    entity_type     VARCHAR(32)   NOT NULL COMMENT 'Owning entity type (company, person, deal, user, ...)',
    entity_id       INT           NOT NULL COMMENT 'Owning entity ID',
    file_name       VARCHAR(255)  NOT NULL COMMENT 'Original file name',
    url             VARCHAR(2048) NOT NULL COMMENT 'Public URL to the stored file',
    content_type    VARCHAR(255)  COMMENT 'MIME type',
    size            BIGINT        COMMENT 'File size in bytes',
    uploaded_by_id  INT           COMMENT 'Uploader User ID',
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Creation timestamp',
    updated_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Last update timestamp',
    CONSTRAINT fk_attachment_uploaded_by FOREIGN KEY (uploaded_by_id) REFERENCES app_user(id) ON DELETE SET NULL,
    -- polymorphic owner: no FK is possible, so cleanup of orphans is handled in the application layer
    INDEX idx_attachment_entity (entity_type, entity_id),
    INDEX idx_attachment_uploaded_by (uploaded_by_id)
) DEFAULT CHARSET=utf8mb4 COMMENT='Generic file attachments for any entity';

-- ============================================================================
-- Junction tables
-- ============================================================================

-- deal_person : a deal can involve multiple people (decision makers, champions).
CREATE TABLE deal_person (
    deal_id     INT NOT NULL,
    person_id   INT NOT NULL,
    role        VARCHAR(64),
    PRIMARY KEY (deal_id, person_id),
    CONSTRAINT fk_deal_person_deal   FOREIGN KEY (deal_id)   REFERENCES deal(id)   ON DELETE CASCADE,
    CONSTRAINT fk_deal_person_person FOREIGN KEY (person_id) REFERENCES person(id) ON DELETE CASCADE,
    INDEX idx_deal_person_person (person_id)
) DEFAULT CHARSET=utf8mb4 COMMENT='Deal-Person Relationships';

CREATE TABLE deal_collaborator (
    workspace_id INT NOT NULL COMMENT 'Workspace ID',
    deal_id      INT NOT NULL COMMENT 'Collaborated deal ID',
    user_id      INT NOT NULL COMMENT 'Collaborator User ID',
    created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Collaboration creation timestamp',
    PRIMARY KEY (workspace_id, deal_id, user_id),
    CONSTRAINT fk_deal_collaborator_deal FOREIGN KEY (workspace_id, deal_id) REFERENCES deal(workspace_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_deal_collaborator_member FOREIGN KEY (workspace_id, user_id) REFERENCES workspace_member(workspace_id, user_id) ON DELETE CASCADE,
    INDEX idx_deal_collaborator_user (workspace_id, user_id, deal_id)
) DEFAULT CHARSET=utf8mb4 COMMENT='Deal collaborators';

-- person_tag, company_tag, deal_tag - labels for records
CREATE TABLE person_tag (
    person_id INT NOT NULL,
    tag_id    INT NOT NULL,
    PRIMARY KEY (person_id, tag_id),
    CONSTRAINT fk_person_tag_person FOREIGN KEY (person_id) REFERENCES person(id) ON DELETE CASCADE,
    CONSTRAINT fk_person_tag_tag    FOREIGN KEY (tag_id)    REFERENCES tag(id)    ON DELETE CASCADE,
    INDEX idx_person_tag_tag (tag_id)
) DEFAULT CHARSET=utf8mb4 COMMENT='Person-Tag Relationships';

CREATE TABLE company_tag (
    company_id INT NOT NULL,
    tag_id     INT NOT NULL,
    PRIMARY KEY (company_id, tag_id),
    CONSTRAINT fk_company_tag_company FOREIGN KEY (company_id) REFERENCES company(id) ON DELETE CASCADE,
    CONSTRAINT fk_company_tag_tag     FOREIGN KEY (tag_id)     REFERENCES tag(id)     ON DELETE CASCADE,
    INDEX idx_company_tag_tag (tag_id)
) DEFAULT CHARSET=utf8mb4 COMMENT='Company-Tag Relationships';

CREATE TABLE deal_tag (
    deal_id INT NOT NULL,
    tag_id  INT NOT NULL,
    PRIMARY KEY (deal_id, tag_id),
    CONSTRAINT fk_deal_tag_deal FOREIGN KEY (deal_id) REFERENCES deal(id) ON DELETE CASCADE,
    CONSTRAINT fk_deal_tag_tag  FOREIGN KEY (tag_id)  REFERENCES tag(id)  ON DELETE CASCADE,
    INDEX idx_deal_tag_tag (tag_id)
) DEFAULT CHARSET=utf8mb4 COMMENT='Deal-Tag Relationships';

CREATE TABLE attachment_tag (
    attachment_id INT NOT NULL,
    tag_id        INT NOT NULL,
    PRIMARY KEY (attachment_id, tag_id),
    CONSTRAINT fk_attachment_tag_attachment FOREIGN KEY (attachment_id) REFERENCES attachment(id) ON DELETE CASCADE,
    CONSTRAINT fk_attachment_tag_tag        FOREIGN KEY (tag_id)        REFERENCES tag(id)        ON DELETE CASCADE,
    INDEX idx_attachment_tag_tag (tag_id)
) DEFAULT CHARSET=utf8mb4 COMMENT='Attachment-Tag Relationships';

-- ============================================================================
-- Notifications
-- ============================================================================
CREATE TABLE notification (
    id                  INT AUTO_INCREMENT PRIMARY KEY COMMENT 'Notification ID',
    workspace_id        INT NOT NULL COMMENT 'Workspace ID',
    recipient_id        INT NOT NULL COMMENT 'Recipient User ID',
    type                VARCHAR(64) NOT NULL COMMENT 'Stable notification type',
    category            VARCHAR(32) NOT NULL COMMENT 'Notification category',
    severity            VARCHAR(16) NOT NULL COMMENT 'Current notification severity',
    template_version    INT NOT NULL DEFAULT 1 COMMENT 'Message template version',
    title               VARCHAR(255) NOT NULL COMMENT 'Rendered title snapshot',
    body                TEXT NULL COMMENT 'Rendered body snapshot',
    actor_id            INT NULL COMMENT 'Actor User ID when applicable',
    actor_label         VARCHAR(255) NULL COMMENT 'Actor label snapshot',
    source_type         VARCHAR(64) NULL COMMENT 'Source entity type',
    source_id           INT NULL COMMENT 'Source entity ID',
    source_label        VARCHAR(255) NULL COMMENT 'Source entity label snapshot',
    context_type        VARCHAR(64) NULL COMMENT 'Context entity type',
    context_id          INT NULL COMMENT 'Context entity ID',
    context_label       VARCHAR(255) NULL COMMENT 'Context entity label snapshot',
    action_url          VARCHAR(2048) NULL COMMENT 'Internal notification action URL',
    data                JSON NULL COMMENT 'Structured rendering and action data',
    dedupe_key          VARCHAR(255) NOT NULL COMMENT 'Stable event identity within a recipient workspace',
    triggered_at        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Most recent material trigger timestamp',
    read_at             DATETIME NULL COMMENT 'Read timestamp',
    dismissed_at        DATETIME NULL COMMENT 'Dismissal timestamp',
    resolved_at         DATETIME NULL COMMENT 'Condition resolution timestamp',
    created_at          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Creation timestamp',
    updated_at          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Last update timestamp',
    CONSTRAINT fk_notification_recipient_member FOREIGN KEY (workspace_id, recipient_id) REFERENCES workspace_member(workspace_id, user_id) ON DELETE CASCADE,
    CONSTRAINT fk_notification_actor FOREIGN KEY (actor_id) REFERENCES app_user(id) ON DELETE SET NULL,
    UNIQUE KEY uq_notification_dedupe (workspace_id, recipient_id, dedupe_key),
    INDEX idx_notification_inbox (workspace_id, recipient_id, dismissed_at, resolved_at, read_at, triggered_at),
    INDEX idx_notification_context (workspace_id, recipient_id, context_type, context_id, dismissed_at, resolved_at),
    INDEX idx_notification_source (workspace_id, source_type, source_id, type),
    INDEX idx_notification_actor (actor_id)
) DEFAULT CHARSET=utf8mb4 COMMENT='Durable user notifications';

CREATE TABLE notification_preference (
    id          INT AUTO_INCREMENT PRIMARY KEY COMMENT 'Notification preference ID',
    user_id     INT NOT NULL COMMENT 'User ID',
    type        VARCHAR(64) NOT NULL COMMENT 'Notification type or wildcard',
    channel     VARCHAR(32) NOT NULL COMMENT 'Delivery channel',
    enabled     BOOLEAN NOT NULL DEFAULT TRUE COMMENT 'Whether delivery is enabled',
    created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Creation timestamp',
    updated_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Last update timestamp',
    CONSTRAINT fk_notification_preference_user FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE,
    UNIQUE KEY uq_notification_preference (user_id, type, channel)
) DEFAULT CHARSET=utf8mb4 COMMENT='Per-user notification channel preferences';

-- ============================================================================
-- audit_log : append-only record of "who did what, when"
-- ============================================================================
CREATE TABLE audit_log (
    id            INT AUTO_INCREMENT PRIMARY KEY COMMENT 'Audit event ID',
    action        VARCHAR(48)  NOT NULL COMMENT 'e.g. company.create, company.update, auth.login',
    entity_type   VARCHAR(32)  NOT NULL COMMENT 'Target entity type (company, person, deal, user, ...)',
    entity_id     INT          NULL COMMENT 'Target entity ID (null for non-entity events)',
    actor_id      INT          NULL COMMENT 'User who performed the action (null = unauthenticated/system)',
    actor_label   VARCHAR(255) NULL COMMENT 'Actor display name AT EVENT TIME (survives rename/delete)',
    target_label  VARCHAR(255) NULL COMMENT 'Target descriptor AT EVENT TIME (survives target delete)',
    outcome       VARCHAR(16)  NOT NULL DEFAULT 'success' COMMENT 'success | failure',
    summary       VARCHAR(255) NULL COMMENT 'Human-readable one-liner',
    changes       JSON         NULL COMMENT 'Field diff {field:{old,new}} over an explicit allowlist',
    context       JSON         NULL COMMENT 'Client-asserted intent (reserved for future enrichment)',
    ip_address    VARCHAR(45)  NULL COMMENT 'Client IP, IPv6-safe (low-confidence until trusted proxy configured)',
    user_agent    VARCHAR(512) NULL COMMENT 'Request User-Agent',
    session_id    VARCHAR(64)  NULL COMMENT 'Server-derived session hash (non-spoofable)',
    request_id    VARCHAR(64)  NULL COMMENT 'Per-request id, groups events from one HTTP request',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Event time (append-only; intentionally no updated_at)',
    CONSTRAINT fk_audit_log_actor FOREIGN KEY (actor_id) REFERENCES app_user(id) ON DELETE SET NULL,
    INDEX idx_audit_log_entity     (entity_type, entity_id),
    INDEX idx_audit_log_actor      (actor_id),
    INDEX idx_audit_log_action     (action),
    INDEX idx_audit_log_created_at (created_at)
) DEFAULT CHARSET=utf8mb4 COMMENT='Append-only audit log';

CREATE TRIGGER trg_audit_log_no_update BEFORE UPDATE ON audit_log
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'audit_log is append-only';
CREATE TRIGGER trg_audit_log_no_delete BEFORE DELETE ON audit_log
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'audit_log is append-only';