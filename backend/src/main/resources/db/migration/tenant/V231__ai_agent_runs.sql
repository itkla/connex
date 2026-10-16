-- Drain every older serving binary and background message writer before this migration.
-- Keep message writes stopped until the compatible durable allocator is deployed.
-- Workspace privacy barriers are initialized by routed application reconciliation.

ALTER TABLE ai_chat_session
    ADD COLUMN message_seq_high_water INT NOT NULL DEFAULT 0,
    ADD COLUMN mission_taint_generation BIGINT UNSIGNED NULL DEFAULT NULL,
    ADD COLUMN mission_taint_seq INT NULL DEFAULT NULL,
    ADD COLUMN title_privacy_generation BIGINT UNSIGNED NULL DEFAULT NULL,
    ADD INDEX idx_ai_chat_session_mission_taint (workspace_id, mission_taint_generation, id);

ALTER TABLE ai_chat_turn
    ADD COLUMN mission_run_id INT NULL DEFAULT NULL,
    ADD COLUMN mission_segment_id INT NULL DEFAULT NULL,
    ADD COLUMN privacy_generation BIGINT UNSIGNED NULL DEFAULT NULL,
    ADD COLUMN privacy_taint_seq INT NULL DEFAULT NULL,
    ADD UNIQUE KEY uq_ai_chat_turn_workspace_id (workspace_id, id),
    ADD INDEX idx_ai_chat_turn_mission (workspace_id, mission_run_id, id),
    ADD INDEX idx_ai_chat_turn_privacy (workspace_id, session_id, privacy_generation, id);

ALTER TABLE ai_chat_message
    ADD COLUMN mission_run_id INT NULL DEFAULT NULL,
    ADD COLUMN mission_message_kind VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL DEFAULT NULL,
    ADD COLUMN privacy_generation BIGINT UNSIGNED NULL DEFAULT NULL,
    ADD UNIQUE KEY uq_ai_chat_message_mission_kind (workspace_id, mission_run_id, mission_message_kind),
    ADD INDEX idx_ai_chat_message_privacy (workspace_id, session_id, privacy_generation, id);

ALTER TABLE ai_chat_tool_call
    ADD COLUMN mission_run_id INT NULL DEFAULT NULL,
    ADD COLUMN artifact_origin VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'reactive',
    ADD COLUMN privacy_generation BIGINT UNSIGNED NULL DEFAULT NULL,
    ADD UNIQUE KEY uq_ai_chat_tool_call_workspace_id (workspace_id, id),
    ADD INDEX idx_ai_chat_tool_call_mission (workspace_id, mission_run_id, id),
    ADD INDEX idx_ai_chat_tool_call_privacy (workspace_id, privacy_generation, id);

UPDATE ai_chat_session s
LEFT JOIN (
    SELECT workspace_id, session_id, MAX(seq) AS maximum_seq
    FROM ai_chat_message
    GROUP BY workspace_id, session_id
) m ON m.workspace_id = s.workspace_id AND m.session_id = s.id
SET s.message_seq_high_water = COALESCE(m.maximum_seq, 0);

CREATE TABLE ai_agent_privacy_barrier (
    workspace_id INT NOT NULL,
    generation BIGINT UNSIGNED NOT NULL DEFAULT 1,
    blocked BOOLEAN NOT NULL DEFAULT FALSE,
    latest_operation_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL DEFAULT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (workspace_id),
    CONSTRAINT chk_ai_agent_barrier_generation CHECK (generation > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE ai_agent_privacy_work (
    id BIGINT NOT NULL AUTO_INCREMENT,
    workspace_id INT NOT NULL,
    operation_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    operation_fingerprint BINARY(32) NOT NULL,
    reason VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    dsr_request_id BIGINT NULL DEFAULT NULL,
    control_acceptance_audit_id INT NULL DEFAULT NULL,
    cutoff_generation BIGINT UNSIGNED NOT NULL,
    invalidated_generation BIGINT UNSIGNED NOT NULL,
    max_run_id INT NOT NULL DEFAULT 0,
    max_session_id INT NOT NULL DEFAULT 0,
    max_turn_id INT NOT NULL DEFAULT 0,
    max_message_id INT NOT NULL DEFAULT 0,
    max_tool_call_id INT NOT NULL DEFAULT 0,
    phase VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'runs',
    revision BIGINT UNSIGNED NOT NULL DEFAULT 1,
    last_run_id INT NOT NULL DEFAULT 0,
    last_session_id INT NOT NULL DEFAULT 0,
    current_session_id INT NULL DEFAULT NULL,
    session_phase VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL DEFAULT NULL,
    last_turn_id INT NOT NULL DEFAULT 0,
    last_tool_call_id INT NOT NULL DEFAULT 0,
    last_message_id INT NOT NULL DEFAULT 0,
    retry_count INT UNSIGNED NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    last_error_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL DEFAULT NULL,
    last_residual_count BIGINT UNSIGNED NULL DEFAULT NULL,
    verified_at DATETIME(6) NULL DEFAULT NULL,
    completed_at DATETIME(6) NULL DEFAULT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_ai_agent_privacy_work_workspace_id (workspace_id, id),
    UNIQUE KEY uq_ai_agent_privacy_work_operation (workspace_id, operation_key),
    UNIQUE KEY uq_ai_agent_privacy_work_generation (workspace_id, invalidated_generation),
    INDEX idx_ai_agent_privacy_work_due (workspace_id, phase, next_attempt_at, id),
    INDEX idx_ai_agent_privacy_work_dsr (workspace_id, dsr_request_id, id),
    CONSTRAINT fk_ai_agent_privacy_work_barrier FOREIGN KEY (workspace_id) REFERENCES ai_agent_privacy_barrier (workspace_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT chk_ai_agent_work_generation CHECK (cutoff_generation > 0 AND invalidated_generation = cutoff_generation + 1),
    CONSTRAINT chk_ai_agent_work_revision CHECK (revision > 0),
    CONSTRAINT chk_ai_agent_work_cursors CHECK (max_run_id >= 0 AND max_session_id >= 0 AND max_turn_id >= 0 AND max_message_id >= 0 AND max_tool_call_id >= 0 AND last_run_id >= 0 AND last_session_id >= 0 AND last_turn_id >= 0 AND last_tool_call_id >= 0 AND last_message_id >= 0 AND (current_session_id IS NULL OR current_session_id >= 0)),
    CONSTRAINT chk_ai_agent_work_reason CHECK (reason IN ('restriction', 'dsr', 'offboarding', 'account_erasure')),
    CONSTRAINT chk_ai_agent_work_dsr CHECK ((reason = 'dsr' AND dsr_request_id IS NOT NULL AND control_acceptance_audit_id IS NOT NULL) OR (reason <> 'dsr' AND dsr_request_id IS NULL AND control_acceptance_audit_id IS NULL)),
    CONSTRAINT chk_ai_agent_work_phase CHECK (phase IN ('runs', 'sessions', 'verify', 'complete')),
    CONSTRAINT chk_ai_agent_work_session_phase CHECK (session_phase IS NULL OR session_phase IN ('settle_turns', 'tools', 'messages', 'turns', 'title')),
    CONSTRAINT chk_ai_agent_work_session_pair CHECK ((current_session_id IS NULL AND session_phase IS NULL) OR (current_session_id IS NOT NULL AND session_phase IS NOT NULL AND phase = 'sessions')),
    CONSTRAINT chk_ai_agent_work_completion CHECK ((phase = 'complete' AND completed_at IS NOT NULL AND verified_at IS NOT NULL AND last_residual_count IS NOT NULL AND last_residual_count = 0 AND current_session_id IS NULL) OR (phase <> 'complete' AND completed_at IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE ai_agent_privacy_barrier
    ADD CONSTRAINT fk_ai_agent_barrier_latest_work FOREIGN KEY (workspace_id, latest_operation_key) REFERENCES ai_agent_privacy_work (workspace_id, operation_key) ON DELETE RESTRICT ON UPDATE RESTRICT;

CREATE TABLE ai_agent_run (
    id INT NOT NULL AUTO_INCREMENT,
    workspace_id INT NOT NULL,
    session_id INT NULL DEFAULT NULL,
    owner_user_id INT NULL DEFAULT NULL,
    client_request_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash BINARY(32) NULL DEFAULT NULL,
    mission_type VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    definition_version SMALLINT UNSIGNED NOT NULL DEFAULT 1,
    runtime_contract_version SMALLINT UNSIGNED NOT NULL DEFAULT 1,
    locale VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    goal TEXT NULL,
    scope_json JSON NULL,
    scope_fingerprint BINARY(32) NULL DEFAULT NULL,
    status VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'queued',
    reason_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL DEFAULT NULL,
    state_version BIGINT UNSIGNED NOT NULL DEFAULT 1,
    execution_generation BIGINT UNSIGNED NOT NULL DEFAULT 0,
    active_segment_id INT NULL DEFAULT NULL,
    execution_deadline_at DATETIME(6) NOT NULL,
    hard_deadline_at DATETIME(6) NOT NULL,
    next_attempt_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    max_steps SMALLINT UNSIGNED NOT NULL DEFAULT 8,
    max_tokens BIGINT UNSIGNED NOT NULL DEFAULT 131072,
    reserved_steps INT UNSIGNED NOT NULL DEFAULT 0,
    consumed_steps INT UNSIGNED NOT NULL DEFAULT 0,
    reserved_tokens BIGINT UNSIGNED NOT NULL DEFAULT 0,
    consumed_tokens BIGINT UNSIGNED NOT NULL DEFAULT 0,
    report_message_id INT NULL DEFAULT NULL,
    privacy_generation BIGINT UNSIGNED NOT NULL,
    withheld_at DATETIME(6) NULL DEFAULT NULL,
    purged_at DATETIME(6) NULL DEFAULT NULL,
    started_at DATETIME(6) NULL DEFAULT NULL,
    finished_at DATETIME(6) NULL DEFAULT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_ai_agent_run_workspace_id (workspace_id, id),
    UNIQUE KEY uq_ai_agent_run_request (workspace_id, owner_user_id, client_request_key),
    UNIQUE KEY uq_ai_agent_run_report (workspace_id, report_message_id),
    INDEX idx_ai_agent_run_scheduler (workspace_id, status, next_attempt_at, id),
    INDEX idx_ai_agent_run_owner (workspace_id, owner_user_id, id),
    INDEX idx_ai_agent_run_session (workspace_id, session_id, status, id),
    INDEX idx_ai_agent_run_owner_cleanup (owner_user_id, workspace_id, id),
    INDEX idx_ai_agent_run_privacy (workspace_id, privacy_generation, id),
    INDEX idx_ai_agent_run_execution_expiry (workspace_id, status, execution_deadline_at, id),
    INDEX idx_ai_agent_run_hard_expiry (workspace_id, hard_deadline_at, id),
    CONSTRAINT fk_ai_agent_run_session FOREIGN KEY (workspace_id, session_id) REFERENCES ai_chat_session (workspace_id, id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_ai_agent_run_report FOREIGN KEY (workspace_id, report_message_id) REFERENCES ai_chat_message (workspace_id, id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT chk_ai_agent_run_status CHECK (status IN ('queued', 'running', 'waiting_approval', 'paused', 'done', 'failed', 'cancelled')),
    CONSTRAINT chk_ai_agent_run_versions CHECK (state_version > 0 AND privacy_generation > 0 AND definition_version > 0 AND runtime_contract_version > 0),
    CONSTRAINT chk_ai_agent_run_limits CHECK (max_steps BETWEEN 1 AND 8 AND max_tokens BETWEEN 8192 AND 131072),
    CONSTRAINT chk_ai_agent_run_deadlines CHECK (hard_deadline_at >= execution_deadline_at),
    CONSTRAINT chk_ai_agent_run_goal CHECK (goal IS NULL OR CHAR_LENGTH(goal) BETWEEN 1 AND 16000),
    CONSTRAINT chk_ai_agent_run_purge CHECK ((purged_at IS NOT NULL AND withheld_at IS NOT NULL AND session_id IS NULL AND request_hash IS NULL AND goal IS NULL AND scope_json IS NULL AND scope_fingerprint IS NULL AND active_segment_id IS NULL AND report_message_id IS NULL) OR (purged_at IS NULL AND session_id IS NOT NULL AND request_hash IS NOT NULL AND goal IS NOT NULL AND scope_json IS NOT NULL AND scope_fingerprint IS NOT NULL)),
    CONSTRAINT chk_ai_agent_run_active CHECK ((status = 'running' AND active_segment_id IS NOT NULL) OR (status <> 'running' AND active_segment_id IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE ai_agent_segment (
    id INT NOT NULL AUTO_INCREMENT,
    workspace_id INT NOT NULL,
    run_id INT NOT NULL,
    turn_id INT NULL DEFAULT NULL,
    segment_no SMALLINT UNSIGNED NOT NULL DEFAULT 1,
    attempt_no SMALLINT UNSIGNED NOT NULL,
    execution_generation BIGINT UNSIGNED NOT NULL,
    privacy_generation BIGINT UNSIGNED NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'claimed',
    reserved_steps INT UNSIGNED NOT NULL,
    reserved_tokens BIGINT UNSIGNED NOT NULL,
    known_steps INT UNSIGNED NOT NULL DEFAULT 0,
    known_input_tokens BIGINT UNSIGNED NOT NULL DEFAULT 0,
    known_output_tokens BIGINT UNSIGNED NOT NULL DEFAULT 0,
    usage_uncertain BOOLEAN NOT NULL DEFAULT FALSE,
    provider_declaration_fingerprint BINARY(32) NOT NULL,
    deadline_at DATETIME(6) NOT NULL,
    failure_reason VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL DEFAULT NULL,
    started_at DATETIME(6) NULL DEFAULT NULL,
    settled_at DATETIME(6) NULL DEFAULT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_ai_agent_segment_workspace_id (workspace_id, id),
    UNIQUE KEY uq_ai_agent_segment_run_id (workspace_id, run_id, id),
    UNIQUE KEY uq_ai_agent_segment_attempt (workspace_id, run_id, segment_no, attempt_no),
    UNIQUE KEY uq_ai_agent_segment_turn (workspace_id, turn_id),
    INDEX idx_ai_agent_segment_privacy (workspace_id, privacy_generation, id),
    CONSTRAINT fk_ai_agent_segment_run FOREIGN KEY (workspace_id, run_id) REFERENCES ai_agent_run (workspace_id, id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_ai_agent_segment_turn FOREIGN KEY (workspace_id, turn_id) REFERENCES ai_chat_turn (workspace_id, id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT chk_ai_agent_segment_attempt CHECK (segment_no = 1 AND attempt_no BETWEEN 1 AND 3),
    CONSTRAINT chk_ai_agent_segment_generation CHECK (execution_generation > 0 AND privacy_generation > 0),
    CONSTRAINT chk_ai_agent_segment_status CHECK (status IN ('claimed', 'running', 'completed', 'interrupted', 'failed', 'cancelled')),
    CONSTRAINT chk_ai_agent_segment_settlement CHECK ((status IN ('claimed', 'running') AND settled_at IS NULL AND turn_id IS NOT NULL) OR (status NOT IN ('claimed', 'running') AND settled_at IS NOT NULL)),
    CONSTRAINT chk_ai_agent_segment_reservations CHECK (reserved_steps > 0 AND reserved_tokens > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE ai_agent_run
    ADD CONSTRAINT fk_ai_agent_run_active_segment FOREIGN KEY (workspace_id, id, active_segment_id) REFERENCES ai_agent_segment (workspace_id, run_id, id) ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE ai_chat_session
    ADD CONSTRAINT chk_ai_chat_session_high_water CHECK (message_seq_high_water >= 0),
    ADD CONSTRAINT chk_ai_chat_session_taint CHECK ((mission_taint_generation IS NULL AND mission_taint_seq IS NULL) OR (mission_taint_generation IS NOT NULL AND mission_taint_generation > 0 AND mission_taint_seq IS NOT NULL AND mission_taint_seq > 0 AND mission_taint_seq <= message_seq_high_water)),
    ADD CONSTRAINT chk_ai_chat_session_title_privacy CHECK (title_privacy_generation IS NULL OR title_privacy_generation > 0);

ALTER TABLE ai_chat_turn
    ADD CONSTRAINT fk_ai_chat_turn_mission_segment FOREIGN KEY (workspace_id, mission_run_id, mission_segment_id) REFERENCES ai_agent_segment (workspace_id, run_id, id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    ADD CONSTRAINT chk_ai_chat_turn_mission_pair CHECK ((mission_run_id IS NULL AND mission_segment_id IS NULL) OR (mission_run_id IS NOT NULL AND mission_segment_id IS NOT NULL AND privacy_generation IS NOT NULL)),
    ADD CONSTRAINT chk_ai_chat_turn_privacy_generation CHECK (privacy_generation IS NULL OR privacy_generation > 0),
    ADD CONSTRAINT chk_ai_chat_turn_taint_seq CHECK (privacy_taint_seq IS NULL OR privacy_taint_seq > 0);

ALTER TABLE ai_chat_message
    ADD CONSTRAINT fk_ai_chat_message_mission FOREIGN KEY (workspace_id, mission_run_id) REFERENCES ai_agent_run (workspace_id, id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    ADD CONSTRAINT chk_ai_chat_message_mission_pair CHECK ((mission_run_id IS NULL AND mission_message_kind IS NULL) OR (mission_run_id IS NOT NULL AND mission_message_kind IS NOT NULL AND mission_message_kind IN ('goal', 'report') AND privacy_generation IS NOT NULL)),
    ADD CONSTRAINT chk_ai_chat_message_privacy_generation CHECK (privacy_generation IS NULL OR privacy_generation > 0);

ALTER TABLE ai_chat_tool_call
    ADD CONSTRAINT fk_ai_chat_tool_call_mission FOREIGN KEY (workspace_id, mission_run_id) REFERENCES ai_agent_run (workspace_id, id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    ADD CONSTRAINT chk_ai_chat_tool_call_origin CHECK ((artifact_origin = 'reactive' AND mission_run_id IS NULL) OR (artifact_origin = 'mission' AND mission_run_id IS NOT NULL AND privacy_generation IS NOT NULL)),
    ADD CONSTRAINT chk_ai_chat_tool_call_privacy_generation CHECK (privacy_generation IS NULL OR privacy_generation > 0);

