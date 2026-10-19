-- Dormant storage only. Production proposal writers require a later semantic cutover.
-- User identifiers are service-validated control-plane references, never cross-plane FKs.
ALTER TABLE ai_chat_message
    ADD UNIQUE KEY uq_ai_chat_message_session_id (workspace_id, session_id, id),
    ALGORITHM=INPLACE, LOCK=NONE;

ALTER TABLE ai_chat_turn
    ADD UNIQUE KEY uq_ai_chat_turn_session_id (workspace_id, session_id, id),
    ALGORITHM=INPLACE, LOCK=NONE;

CREATE TABLE ai_assistant_proposal_set (
    workspace_id         INT NOT NULL,
    proposal_key         VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    session_id           INT NOT NULL,
    origin_turn_id       INT NOT NULL,
    message_id           INT NOT NULL,
    requested_by_user_id INT NULL,
    step_number          INT NOT NULL,
    call_ordinal         INT NOT NULL,
    tool_name            VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
    invocation_mode      VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    bulk_turn_slot       TINYINT GENERATED ALWAYS AS
        (CASE WHEN invocation_mode = 'bulk' THEN 1 ELSE NULL END) STORED,
    row_count            INT NOT NULL,
    intent_fingerprint   CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    scope_revision       VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    publication_state    VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'staged',
    failure_reason       VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
    privacy_generation   BIGINT NOT NULL,
    created_at           DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (workspace_id, proposal_key),
    UNIQUE KEY uq_ai_assistant_proposal_set_message (workspace_id, proposal_key, message_id),
    UNIQUE KEY uq_ai_assistant_proposal_set_call
        (workspace_id, origin_turn_id, step_number, call_ordinal),
    UNIQUE KEY uq_ai_assistant_proposal_set_bulk (workspace_id, origin_turn_id, bulk_turn_slot),
    INDEX idx_ai_assistant_proposal_set_session (workspace_id, session_id, created_at, proposal_key),
    INDEX idx_ai_assistant_proposal_set_requester (requested_by_user_id, workspace_id),
    CONSTRAINT chk_ai_assistant_proposal_set_key CHECK (CHAR_LENGTH(proposal_key) > 0),
    CONSTRAINT chk_ai_assistant_proposal_set_call
        CHECK (step_number BETWEEN 1 AND 64 AND call_ordinal BETWEEN 0 AND 4),
    CONSTRAINT chk_ai_assistant_proposal_set_mode
        CHECK ((invocation_mode = 'scalar' AND row_count = 1)
            OR (invocation_mode = 'bulk' AND row_count BETWEEN 1 AND 20)),
    CONSTRAINT chk_ai_assistant_proposal_set_intent CHECK (CHAR_LENGTH(intent_fingerprint) = 64),
    CONSTRAINT chk_ai_assistant_proposal_set_revision CHECK (CHAR_LENGTH(scope_revision) > 0),
    CONSTRAINT chk_ai_assistant_proposal_set_generation CHECK (privacy_generation >= 0),
    CONSTRAINT chk_ai_assistant_proposal_set_publication
        CHECK ((publication_state IN ('staged', 'ready') AND failure_reason IS NULL)
            OR (publication_state = 'failed' AND failure_reason IS NOT NULL
                AND failure_reason IN ('call_failed', 'cancelled', 'timed_out', 'execution_lost'))),
    CONSTRAINT fk_ai_assistant_proposal_set_message
        FOREIGN KEY (workspace_id, session_id, message_id)
        REFERENCES ai_chat_message(workspace_id, session_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_ai_assistant_proposal_set_turn
        FOREIGN KEY (workspace_id, session_id, origin_turn_id)
        REFERENCES ai_chat_turn(workspace_id, session_id, id) ON DELETE CASCADE
) DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Dormant immutable chat-call membership and technical publication metadata';

-- Legacy rows stay null-set singles, retaining their existing trust and status.
ALTER TABLE ai_chat_tool_call
    ADD COLUMN proposal_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN proposal_ordinal INT NULL,
    ADD COLUMN proposal_prepared_at DATETIME(6) NULL,
    ALGORITHM=INSTANT, LOCK=DEFAULT;

ALTER TABLE ai_chat_tool_call
    ADD UNIQUE KEY uq_ai_chat_tool_call_proposal (workspace_id, proposal_key, proposal_ordinal),
    ADD INDEX idx_ai_chat_tool_call_proposal_message (workspace_id, proposal_key, message_id),
    ALGORITHM=INPLACE, LOCK=NONE;

-- MySQL requires COPY to add an enforced CHECK to a retained table. Triggers preserve
-- the same invariant for future writes without scanning or rebuilding legacy rows.
DELIMITER $$
CREATE TRIGGER trg_ai_chat_tool_call_proposal_insert
BEFORE INSERT ON ai_chat_tool_call
FOR EACH ROW
BEGIN
    IF NOT ((NEW.proposal_key IS NULL AND NEW.proposal_ordinal IS NULL AND NEW.proposal_prepared_at IS NULL)
        OR (NEW.proposal_key IS NOT NULL AND NEW.proposal_ordinal IS NOT NULL
            AND NEW.proposal_prepared_at IS NOT NULL AND NEW.proposal_ordinal BETWEEN 1 AND 20
            AND NEW.server_proposal = TRUE)) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Invalid proposal membership metadata';
    END IF;
END$$

CREATE TRIGGER trg_ai_chat_tool_call_proposal_update
BEFORE UPDATE ON ai_chat_tool_call
FOR EACH ROW
BEGIN
    IF NOT ((NEW.proposal_key IS NULL AND NEW.proposal_ordinal IS NULL AND NEW.proposal_prepared_at IS NULL)
        OR (NEW.proposal_key IS NOT NULL AND NEW.proposal_ordinal IS NOT NULL
            AND NEW.proposal_prepared_at IS NOT NULL AND NEW.proposal_ordinal BETWEEN 1 AND 20
            AND NEW.server_proposal = TRUE)) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Invalid proposal membership metadata';
    END IF;
END$$
DELIMITER ;

-- Only this migration connection suspends FK validation, and it performs no DML.
-- Existing rows and concurrent legacy writers have null proposal keys; no set producer
-- may run before the later semantic cutover. There are no existing references to validate.
SET @connex_v233_foreign_key_checks = @@SESSION.foreign_key_checks;
SET SESSION foreign_key_checks = 0;
ALTER TABLE ai_chat_tool_call
    ADD CONSTRAINT fk_ai_chat_tool_call_proposal
        FOREIGN KEY (workspace_id, proposal_key, message_id)
        REFERENCES ai_assistant_proposal_set(workspace_id, proposal_key, message_id)
        ON DELETE CASCADE,
    ALGORITHM=INPLACE, LOCK=NONE;
SET SESSION foreign_key_checks = @connex_v233_foreign_key_checks;
SET @connex_v233_foreign_key_checks = NULL;
