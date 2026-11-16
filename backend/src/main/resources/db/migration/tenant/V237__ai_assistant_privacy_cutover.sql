ALTER TABLE ai_chat_session
    ADD COLUMN organization_privacy_generation BIGINT UNSIGNED NOT NULL DEFAULT 0,
    ALGORITHM=INSTANT, LOCK=DEFAULT;

ALTER TABLE ai_chat_message
    ADD COLUMN organization_privacy_generation BIGINT UNSIGNED NOT NULL DEFAULT 0,
    ALGORITHM=INSTANT, LOCK=DEFAULT;

ALTER TABLE ai_chat_turn
    ADD COLUMN organization_privacy_generation BIGINT UNSIGNED NOT NULL DEFAULT 0,
    ALGORITHM=INSTANT, LOCK=DEFAULT;

ALTER TABLE ai_chat_tool_call
    ADD COLUMN organization_privacy_generation BIGINT UNSIGNED NOT NULL DEFAULT 0,
    ALGORITHM=INSTANT, LOCK=DEFAULT;

ALTER TABLE ai_assistant_proposal_set
    ADD COLUMN organization_privacy_generation BIGINT UNSIGNED NOT NULL DEFAULT 0,
    ALGORITHM=INSTANT, LOCK=DEFAULT;

ALTER TABLE ai_agent_run
    ADD COLUMN organization_privacy_generation BIGINT UNSIGNED NOT NULL DEFAULT 0,
    ALGORITHM=INSTANT, LOCK=DEFAULT;

ALTER TABLE ai_agent_segment
    ADD COLUMN organization_privacy_generation BIGINT UNSIGNED NOT NULL DEFAULT 0,
    ALGORITHM=INSTANT, LOCK=DEFAULT;

ALTER TABLE ai_agent_privacy_work
    ADD COLUMN content_scope VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin
        NOT NULL DEFAULT 'organization_mission_holdings_v1',
    ALGORITHM=INSTANT, LOCK=DEFAULT;

ALTER TABLE ai_chat_session
    ADD INDEX idx_ai_chat_session_org_privacy
        (workspace_id, organization_privacy_generation, id),
    ALGORITHM=INPLACE, LOCK=NONE;

ALTER TABLE ai_agent_run
    ADD INDEX idx_ai_agent_run_org_privacy
        (workspace_id, organization_privacy_generation, id),
    ALGORITHM=INPLACE, LOCK=NONE;

ALTER TABLE ai_assistant_proposal_set
    ADD INDEX idx_ai_proposal_set_org_privacy
        (workspace_id, organization_privacy_generation, proposal_key),
    ALGORITHM=INPLACE, LOCK=NONE;

DELIMITER $$

CREATE TRIGGER trg_ai_agent_privacy_work_scope_insert
BEFORE INSERT ON ai_agent_privacy_work
FOR EACH ROW
BEGIN
    IF NOT (
        NEW.cutoff_generation < 9223372036854775807
        AND NEW.invalidated_generation = NEW.cutoff_generation + 1
        AND (
            (NEW.content_scope = 'organization_mission_holdings_v1'
             AND NEW.cutoff_generation > 0
             AND NEW.reason IN ('restriction', 'dsr', 'offboarding', 'account_erasure'))
            OR
            (NEW.content_scope = 'organization_assistant_holdings_v2'
             AND NEW.reason IN ('bootstrap', 'restriction', 'dsr',
                                'offboarding', 'account_erasure'))
        )
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'Invalid assistant privacy work scope';
    END IF;
END$$

CREATE TRIGGER trg_ai_agent_privacy_work_scope_update
BEFORE UPDATE ON ai_agent_privacy_work
FOR EACH ROW
BEGIN
    IF NOT (
        NEW.cutoff_generation < 9223372036854775807
        AND NEW.invalidated_generation = NEW.cutoff_generation + 1
        AND (
            (NEW.content_scope = 'organization_mission_holdings_v1'
             AND NEW.cutoff_generation > 0
             AND NEW.reason IN ('restriction', 'dsr', 'offboarding', 'account_erasure'))
            OR
            (NEW.content_scope = 'organization_assistant_holdings_v2'
             AND NEW.reason IN ('bootstrap', 'restriction', 'dsr',
                                'offboarding', 'account_erasure'))
        )
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'Invalid assistant privacy work scope';
    END IF;

    IF NOT (NEW.content_scope <=> OLD.content_scope) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'Assistant privacy work scope is immutable';
    END IF;
END$$

DELIMITER ;

ALTER TABLE ai_agent_privacy_work
    DROP CHECK chk_ai_agent_work_generation,
    DROP CHECK chk_ai_agent_work_reason,
    DROP INDEX uq_ai_agent_privacy_work_generation,
    ADD UNIQUE KEY uq_ai_agent_privacy_work_scope_generation
        (workspace_id, content_scope, invalidated_generation),
    ALGORITHM=INPLACE, LOCK=NONE;

CREATE TABLE ai_agent_privacy_object (
    workspace_id INT NOT NULL,
    operation_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    object_key VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    deletion_queue_id BIGINT NULL,
    disposition VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    retained_attachment_id INT NULL,
    completed_at DATETIME(6) NULL,
    PRIMARY KEY (workspace_id, operation_key, object_key),
    INDEX idx_ai_agent_privacy_object_queue
        (workspace_id, deletion_queue_id),
    CONSTRAINT fk_ai_agent_privacy_object_work
        FOREIGN KEY (workspace_id, operation_key)
        REFERENCES ai_agent_privacy_work (workspace_id, operation_key)
        ON DELETE RESTRICT,
    CONSTRAINT chk_ai_agent_privacy_object_disposition
        CHECK (
            (disposition = 'pending' AND deletion_queue_id IS NOT NULL
             AND retained_attachment_id IS NULL AND completed_at IS NULL)
            OR
            (disposition = 'deleted' AND deletion_queue_id IS NOT NULL
             AND retained_attachment_id IS NULL AND completed_at IS NOT NULL)
            OR
            (disposition = 'retained' AND deletion_queue_id IS NULL
             AND retained_attachment_id IS NOT NULL AND completed_at IS NOT NULL)
        )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
