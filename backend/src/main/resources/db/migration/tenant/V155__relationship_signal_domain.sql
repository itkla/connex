CREATE TABLE relationship_signal (
    id BIGINT NOT NULL AUTO_INCREMENT,
    workspace_id INT NOT NULL,
    family VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
    subject_type VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
    subject_id INT NOT NULL,
    subject_label VARCHAR(255) NOT NULL,
    priority VARCHAR(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
    priority_rank TINYINT UNSIGNED NOT NULL,
    rank_value INT NOT NULL,
    dedupe_key VARCHAR(191) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
    evidence_json JSON NOT NULL,
    rank_explanation_json JSON NOT NULL,
    evidence_as_of DATETIME(6) NOT NULL,
    source_state_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    generation_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    resolved_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_relationship_signal_workspace_id (workspace_id, id),
    UNIQUE KEY uq_relationship_signal_dedupe (workspace_id, dedupe_key),
    KEY idx_relationship_signal_family_generation (workspace_id, family, generation_token),
    KEY idx_relationship_signal_rank (workspace_id, resolved_at, priority_rank, rank_value DESC, family, subject_type, subject_id),
    CONSTRAINT chk_relationship_signal_family CHECK (family IN ('relationship_decay', 'deal_risk', 'warm_path')),
    CONSTRAINT chk_relationship_signal_subject CHECK (subject_type IN ('person', 'company', 'deal')),
    CONSTRAINT chk_relationship_signal_family_subject CHECK (
        (family = 'relationship_decay' AND subject_type IN ('person', 'company'))
        OR (family = 'deal_risk' AND subject_type = 'deal')
        OR (family = 'warm_path' AND subject_type = 'person')
    )
) ENGINE=InnoDB;

CREATE TABLE relationship_signal_state (
    workspace_id INT NOT NULL,
    signal_id BIGINT NOT NULL,
    user_id INT NOT NULL,
    disposition VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'active',
    snooze_until DATETIME(6) NULL,
    dismissed_source_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    task_id INT NULL,
    task_source_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    version BIGINT NOT NULL DEFAULT 1,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (workspace_id, signal_id, user_id),
    KEY idx_relationship_signal_state_user (workspace_id, user_id, disposition, snooze_until),
    KEY idx_relationship_signal_state_user_anywhere (user_id),
    CONSTRAINT fk_relationship_signal_state_signal
        FOREIGN KEY (workspace_id, signal_id)
        REFERENCES relationship_signal (workspace_id, id)
        ON DELETE CASCADE,
    CONSTRAINT chk_relationship_signal_state_disposition
        CHECK (disposition IN ('active', 'followed', 'snoozed', 'dismissed'))
) ENGINE=InnoDB;

CREATE TABLE relationship_signal_family_state (
    workspace_id INT NOT NULL,
    family VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
    status VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
    last_attempt_at DATETIME(6) NOT NULL,
    last_success_at DATETIME(6) NULL,
    evidence_as_of DATETIME(6) NULL,
    error_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    PRIMARY KEY (workspace_id, family),
    CONSTRAINT chk_relationship_signal_family_state_family
        CHECK (family IN ('relationship_decay', 'deal_risk', 'warm_path')),
    CONSTRAINT chk_relationship_signal_family_state_status
        CHECK (status IN ('available', 'unavailable'))
) ENGINE=InnoDB;
