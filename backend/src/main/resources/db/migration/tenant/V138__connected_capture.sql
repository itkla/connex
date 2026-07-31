CREATE TABLE provider_capture_workspace_policy (
    workspace_id INT NOT NULL,
    provider VARCHAR(16) NOT NULL,
    allowed BOOLEAN NOT NULL DEFAULT FALSE,
    calendar_allowed BOOLEAN NOT NULL DEFAULT TRUE,
    mail_inbox_allowed BOOLEAN NOT NULL DEFAULT TRUE,
    mail_sent_allowed BOOLEAN NOT NULL DEFAULT TRUE,
    max_backfill_days SMALLINT UNSIGNED NOT NULL DEFAULT 90,
    body_capture_allowed BOOLEAN NOT NULL DEFAULT FALSE,
    review_required BOOLEAN NOT NULL DEFAULT TRUE,
    exclude_private_events BOOLEAN NOT NULL DEFAULT TRUE,
    exclude_internal_only BOOLEAN NOT NULL DEFAULT FALSE,
    excluded_domains_json JSON NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    updated_by_user_id INT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (workspace_id, provider),
    CONSTRAINT chk_provider_capture_workspace_policy_provider
        CHECK (provider IN ('google', 'microsoft')),
    CONSTRAINT chk_provider_capture_workspace_policy_backfill
        CHECK (max_backfill_days BETWEEN 1 AND 180),
    CONSTRAINT chk_provider_capture_workspace_policy_domains
        CHECK (JSON_TYPE(excluded_domains_json) = 'ARRAY'),
    CONSTRAINT chk_provider_capture_workspace_private
        CHECK (exclude_private_events = TRUE),
    CONSTRAINT chk_provider_capture_workspace_policy_version CHECK (version > 0)
) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE TABLE provider_capture_user_policy (
    workspace_id INT NOT NULL,
    user_id INT NOT NULL,
    provider VARCHAR(16) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    calendar_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    mail_inbox_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    mail_sent_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    backfill_days SMALLINT UNSIGNED NOT NULL DEFAULT 90,
    include_bodies BOOLEAN NOT NULL DEFAULT FALSE,
    admission_mode VARCHAR(16) NOT NULL DEFAULT 'review',
    excluded_people_json JSON NOT NULL,
    excluded_conversations_json JSON NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (workspace_id, user_id, provider),
    CONSTRAINT chk_provider_capture_user_policy_provider
        CHECK (provider IN ('google', 'microsoft')),
    CONSTRAINT chk_provider_capture_user_policy_backfill
        CHECK (backfill_days BETWEEN 1 AND 180),
    CONSTRAINT chk_provider_capture_user_policy_mode
        CHECK (admission_mode IN ('manual', 'review', 'automatic')),
    CONSTRAINT chk_provider_capture_user_policy_people
        CHECK (JSON_TYPE(excluded_people_json) = 'ARRAY'),
    CONSTRAINT chk_provider_capture_user_policy_conversations
        CHECK (JSON_TYPE(excluded_conversations_json) = 'ARRAY'),
    CONSTRAINT chk_provider_capture_user_policy_version CHECK (version > 0),
    KEY idx_provider_capture_user_policy_user (user_id, provider, workspace_id)
) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE TABLE provider_capture_sync_state (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    workspace_id INT NOT NULL,
    user_id INT NOT NULL,
    provider VARCHAR(16) NOT NULL,
    stream VARCHAR(16) NOT NULL,
    credential_generation BIGINT NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'idle',
    initial_sync_completed BOOLEAN NOT NULL DEFAULT FALSE,
    stable_cursor TEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL,
    page_cursor MEDIUMTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL,
    lease_owner CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    lease_until DATETIME(6) NULL,
    reconciliation_marker CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    backfill_started_at DATETIME(6) NULL,
    last_attempt_at DATETIME(6) NULL,
    last_success_at DATETIME(6) NULL,
    next_attempt_at DATETIME(6) NULL,
    processed_items BIGINT UNSIGNED NOT NULL DEFAULT 0,
    estimated_items BIGINT UNSIGNED NULL,
    consecutive_failures SMALLINT UNSIGNED NOT NULL DEFAULT 0,
    error_code VARCHAR(64) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT chk_provider_capture_sync_state_provider
        CHECK (provider IN ('google', 'microsoft')),
    CONSTRAINT chk_provider_capture_sync_state_stream
        CHECK (stream IN ('calendar', 'mail_inbox', 'mail_sent')),
    CONSTRAINT chk_provider_capture_sync_state_status
        CHECK (status IN (
            'idle', 'queued', 'backfilling', 'syncing', 'retrying',
            'intervention_required', 'paused', 'purging'
        )),
    CONSTRAINT chk_provider_capture_sync_state_generation
        CHECK (credential_generation > 0),
    CONSTRAINT chk_provider_capture_sync_state_lease CHECK (
        (lease_owner IS NULL AND lease_until IS NULL)
        OR (lease_owner IS NOT NULL AND lease_until IS NOT NULL)
    ),
    UNIQUE KEY uq_provider_capture_sync_state (
        workspace_id, user_id, provider, stream
    ),
    KEY idx_provider_capture_sync_due (
        status, next_attempt_at, lease_until, workspace_id, id
    ),
    KEY idx_provider_capture_sync_user (user_id, provider, workspace_id)
) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE TABLE provider_captured_interaction (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    workspace_id INT NOT NULL,
    user_id INT NOT NULL,
    provider VARCHAR(16) NOT NULL,
    stream VARCHAR(16) NOT NULL,
    provider_source_id VARCHAR(512) NOT NULL,
    provider_conversation_id VARCHAR(512) NULL,
    source_key_hash BINARY(32) NOT NULL,
    source_version VARCHAR(512) NULL,
    payload_hash BINARY(32) NOT NULL,
    interaction_type VARCHAR(16) NOT NULL,
    subject VARCHAR(255) NULL,
    body MEDIUMTEXT NULL,
    occurred_at DATETIME(6) NOT NULL,
    ended_at DATETIME(6) NULL,
    visibility VARCHAR(16) NOT NULL DEFAULT 'workspace',
    admission_status VARCHAR(24) NOT NULL DEFAULT 'held',
    admitted_fields_json JSON NOT NULL,
    material_exclusions_json JSON NOT NULL,
    policy_version BIGINT NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    last_seen_reconciliation_marker CHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NULL,
    tombstoned_at DATETIME(6) NULL,
    captured_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT chk_provider_captured_interaction_provider
        CHECK (provider IN ('google', 'microsoft')),
    CONSTRAINT chk_provider_captured_interaction_stream
        CHECK (stream IN ('calendar', 'mail_inbox', 'mail_sent')),
    CONSTRAINT chk_provider_captured_interaction_type
        CHECK (interaction_type IN ('email', 'meeting')),
    CONSTRAINT chk_provider_captured_interaction_visibility
        CHECK (visibility IN ('workspace', 'private')),
    CONSTRAINT chk_provider_captured_interaction_admission
        CHECK (admission_status IN ('held', 'admitted', 'ignored', 'withdrawn')),
    CONSTRAINT chk_provider_captured_interaction_fields
        CHECK (JSON_TYPE(admitted_fields_json) = 'ARRAY'),
    CONSTRAINT chk_provider_captured_interaction_exclusions
        CHECK (JSON_TYPE(material_exclusions_json) = 'ARRAY'),
    CONSTRAINT chk_provider_captured_interaction_policy_version
        CHECK (policy_version > 0),
    CONSTRAINT chk_provider_captured_interaction_version CHECK (version > 0),
    UNIQUE KEY uq_provider_captured_interaction_source (
        workspace_id, user_id, provider, source_key_hash
    ),
    UNIQUE KEY uq_provider_captured_interaction_workspace_id (workspace_id, id),
    KEY idx_provider_captured_interaction_review (
        workspace_id, user_id, provider, admission_status, occurred_at, id
    ),
    KEY idx_provider_captured_interaction_retention (
        workspace_id, user_id, provider, stream, occurred_at, id
    ),
    KEY idx_provider_captured_interaction_user (user_id, provider, workspace_id)
) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE TABLE provider_captured_participant (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    workspace_id INT NOT NULL,
    interaction_id BIGINT NOT NULL,
    participant_role VARCHAR(16) NOT NULL,
    display_name VARCHAR(255) NULL,
    email VARCHAR(254) NULL,
    normalized_email VARCHAR(254) CHARACTER SET ascii COLLATE ascii_bin NULL,
    person_id INT NULL,
    match_state VARCHAR(16) NOT NULL DEFAULT 'unmatched',
    held_reason VARCHAR(32) NULL,
    version BIGINT NOT NULL DEFAULT 1,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_provider_captured_participant_interaction
        FOREIGN KEY (workspace_id, interaction_id)
        REFERENCES provider_captured_interaction(workspace_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_provider_captured_participant_person
        FOREIGN KEY (workspace_id, person_id)
        REFERENCES person(workspace_id, id) ON DELETE RESTRICT,
    CONSTRAINT chk_provider_captured_participant_role
        CHECK (participant_role IN ('organizer', 'attendee', 'from', 'to', 'cc')),
    CONSTRAINT chk_provider_captured_participant_state
        CHECK (match_state IN ('unmatched', 'ambiguous', 'matched', 'ignored')),
    CONSTRAINT chk_provider_captured_participant_version CHECK (version > 0),
    UNIQUE KEY uq_provider_captured_participant_workspace_id (workspace_id, id),
    KEY idx_provider_captured_participant_email (
        workspace_id, normalized_email, match_state, id
    ),
    KEY idx_provider_captured_participant_person (workspace_id, person_id)
) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE TABLE provider_participant_decision (
    workspace_id INT NOT NULL,
    user_id INT NOT NULL,
    provider VARCHAR(16) NOT NULL,
    normalized_email VARCHAR(254) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    decision VARCHAR(16) NOT NULL,
    person_id INT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (workspace_id, user_id, provider, normalized_email),
    CONSTRAINT fk_provider_participant_decision_person
        FOREIGN KEY (workspace_id, person_id)
        REFERENCES person(workspace_id, id) ON DELETE CASCADE,
    CONSTRAINT chk_provider_participant_decision_provider
        CHECK (provider IN ('google', 'microsoft')),
    CONSTRAINT chk_provider_participant_decision_value
        CHECK (
            (decision = 'attach' AND person_id IS NOT NULL)
            OR (decision = 'ignore' AND person_id IS NULL)
        )
) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

ALTER TABLE activity
    ADD COLUMN provider_owned BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN provider_name VARCHAR(16) NULL,
    ADD COLUMN provider_stream VARCHAR(16) NULL,
    ADD COLUMN provider_source_id VARCHAR(512) NULL,
    ADD COLUMN provider_captured_at DATETIME(6) NULL,
    ADD COLUMN provider_visibility VARCHAR(16) NULL,
    ADD COLUMN provider_admitted_fields_json JSON NULL,
    ADD COLUMN provider_material_exclusions_json JSON NULL,
    ADD COLUMN provider_projection_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD UNIQUE KEY uq_activity_workspace_id (workspace_id, id),
    ADD UNIQUE KEY uq_activity_provider_projection (
        workspace_id, provider_projection_key
    ),
    ADD CONSTRAINT chk_activity_provider_provenance CHECK (
        (
            provider_owned = FALSE
            AND provider_name IS NULL
            AND provider_stream IS NULL
            AND provider_source_id IS NULL
            AND provider_captured_at IS NULL
            AND provider_visibility IS NULL
            AND provider_admitted_fields_json IS NULL
            AND provider_material_exclusions_json IS NULL
            AND provider_projection_key IS NULL
        )
        OR (
            provider_owned = TRUE
            AND provider_name IN ('google', 'microsoft')
            AND provider_stream IN ('calendar', 'mail_inbox', 'mail_sent')
            AND provider_source_id IS NOT NULL
            AND provider_captured_at IS NOT NULL
            AND provider_visibility IN ('workspace', 'private')
            AND JSON_TYPE(provider_admitted_fields_json) = 'ARRAY'
            AND JSON_TYPE(provider_material_exclusions_json) = 'ARRAY'
            AND provider_projection_key IS NOT NULL
        )
    );

CREATE TABLE provider_activity_projection (
    workspace_id INT NOT NULL,
    interaction_id BIGINT NOT NULL,
    participant_id BIGINT NOT NULL,
    activity_id INT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (workspace_id, interaction_id, participant_id),
    CONSTRAINT fk_provider_activity_projection_interaction
        FOREIGN KEY (workspace_id, interaction_id)
        REFERENCES provider_captured_interaction(workspace_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_provider_activity_projection_participant
        FOREIGN KEY (workspace_id, participant_id)
        REFERENCES provider_captured_participant(workspace_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_provider_activity_projection_activity
        FOREIGN KEY (workspace_id, activity_id)
        REFERENCES activity(workspace_id, id) ON DELETE CASCADE,
    UNIQUE KEY uq_provider_activity_projection_activity (workspace_id, activity_id)
) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
