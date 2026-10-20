ALTER TABLE ai_agent_run
    ADD COLUMN proposal_membership_revision BIGINT UNSIGNED NOT NULL DEFAULT 0,
    ADD COLUMN proposal_frozen_at DATETIME(6) NULL DEFAULT NULL,
    ADD COLUMN proposal_first_prepared_at DATETIME(6) NULL DEFAULT NULL,
    ADD COLUMN review_expires_at DATETIME(6) NULL DEFAULT NULL,
    ALGORITHM=INSTANT, LOCK=DEFAULT;

ALTER TABLE ai_agent_run
    ADD INDEX idx_ai_agent_run_review_expiry
        (workspace_id, status, review_expires_at, id),
    ALGORITHM=INPLACE, LOCK=NONE;

CREATE TABLE ai_agent_action (
    id INT NOT NULL AUTO_INCREMENT,
    workspace_id INT NOT NULL,
    root_run_id INT NOT NULL,
    producer_run_id INT NOT NULL,
    action_slot CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    semantic_slot_key VARCHAR(192)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_kind VARCHAR(16)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_id INT NOT NULL,
    tool_name VARCHAR(128)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    plan_item_key VARCHAR(64)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    plan_revision BIGINT UNSIGNED NOT NULL,
    preparation_id CHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NULL DEFAULT NULL,
    intent_fingerprint BINARY(32) NULL DEFAULT NULL,
    prepared_at DATETIME(6) NULL DEFAULT NULL,
    source_observed_at DATETIME(6) NULL DEFAULT NULL,
    source_versions_json TEXT
        CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    origin_segment_id INT NULL DEFAULT NULL,
    origin_execution_generation BIGINT UNSIGNED NULL DEFAULT NULL,
    output_ordinal SMALLINT UNSIGNED NULL DEFAULT NULL,
    privacy_generation BIGINT UNSIGNED NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_ai_agent_action_workspace_id (workspace_id, id),
    UNIQUE KEY uq_ai_agent_action_root_id (workspace_id, root_run_id, id),
    UNIQUE KEY uq_ai_agent_action_slot
        (workspace_id, root_run_id, action_slot),
    UNIQUE KEY uq_ai_agent_action_semantic
        (workspace_id, root_run_id, semantic_slot_key),
    UNIQUE KEY uq_ai_agent_action_target_tool
        (workspace_id, root_run_id, target_kind, target_id, tool_name),
    UNIQUE KEY uq_ai_agent_action_output
        (workspace_id, root_run_id, output_ordinal),
    UNIQUE KEY uq_ai_agent_action_preparation
        (workspace_id, preparation_id),
    INDEX idx_ai_agent_action_producer
        (workspace_id, producer_run_id, id),
    INDEX idx_ai_agent_action_origin_segment
        (workspace_id, producer_run_id, origin_segment_id),
    INDEX idx_ai_agent_action_privacy
        (workspace_id, privacy_generation, id),
    CONSTRAINT fk_ai_agent_action_root
        FOREIGN KEY (workspace_id, root_run_id)
        REFERENCES ai_agent_run (workspace_id, id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_ai_agent_action_producer
        FOREIGN KEY (workspace_id, producer_run_id)
        REFERENCES ai_agent_run (workspace_id, id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_ai_agent_action_segment
        FOREIGN KEY (workspace_id, producer_run_id, origin_segment_id)
        REFERENCES ai_agent_segment (workspace_id, run_id, id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT chk_ai_agent_action_target
        CHECK (target_kind IN ('person', 'company', 'deal') AND target_id > 0),
    CONSTRAINT chk_ai_agent_action_semantic
        CHECK (semantic_slot_key =
            CONCAT(target_kind, ':', target_id, ':', tool_name)),
    CONSTRAINT chk_ai_agent_action_plan
        CHECK (CHAR_LENGTH(plan_item_key) BETWEEN 1 AND 64
            AND plan_revision > 0),
    CONSTRAINT chk_ai_agent_action_generation
        CHECK (privacy_generation > 0),
    CONSTRAINT chk_ai_agent_action_ordinal
        CHECK (output_ordinal IS NULL OR output_ordinal BETWEEN 1 AND 50)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE ai_chat_tool_call
    ADD COLUMN proposal_origin VARCHAR(16)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'legacy',
    ADD COLUMN mission_action_id INT NULL DEFAULT NULL,
    ALGORITHM=INSTANT, LOCK=DEFAULT;

ALTER TABLE ai_chat_tool_call
    ADD UNIQUE KEY uq_ai_chat_tool_call_mission_action
        (workspace_id, mission_action_id),
    ADD INDEX idx_ai_chat_tool_call_mission_action_fk
        (workspace_id, mission_run_id, mission_action_id),
    ALGORITHM=INPLACE, LOCK=NONE;

DELIMITER $$

CREATE TRIGGER trg_ai_agent_action_binding_insert
BEFORE INSERT ON ai_agent_action
FOR EACH ROW
BEGIN
    IF NEW.producer_run_id <> NEW.root_run_id OR NOT (
        (NEW.preparation_id IS NULL AND NEW.intent_fingerprint IS NULL
                AND NEW.prepared_at IS NULL AND NEW.source_observed_at IS NULL
                AND NEW.source_versions_json IS NULL
                AND NEW.origin_segment_id IS NULL
                AND NEW.origin_execution_generation IS NULL
                AND NEW.output_ordinal IS NULL)
            OR
            (NEW.preparation_id IS NOT NULL AND NEW.intent_fingerprint IS NOT NULL
                AND NEW.prepared_at IS NOT NULL AND NEW.source_observed_at IS NOT NULL
                AND NEW.source_versions_json IS NOT NULL
                AND NEW.origin_segment_id IS NOT NULL
                AND NEW.origin_execution_generation IS NOT NULL
                AND NEW.origin_execution_generation > 0
                AND NEW.prepared_at >= NEW.source_observed_at
                AND JSON_VALID(NEW.source_versions_json)
                AND OCTET_LENGTH(NEW.source_versions_json) BETWEEN 2 AND 32768)
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'Invalid mission action binding';
    END IF;
END$$

CREATE TRIGGER trg_ai_agent_action_immutable_update
BEFORE UPDATE ON ai_agent_action
FOR EACH ROW
BEGIN
    IF NEW.producer_run_id <> NEW.root_run_id OR NOT (
        (NEW.preparation_id IS NULL AND NEW.intent_fingerprint IS NULL
                AND NEW.prepared_at IS NULL AND NEW.source_observed_at IS NULL
                AND NEW.source_versions_json IS NULL
                AND NEW.origin_segment_id IS NULL
                AND NEW.origin_execution_generation IS NULL
                AND NEW.output_ordinal IS NULL)
            OR
            (NEW.preparation_id IS NOT NULL AND NEW.intent_fingerprint IS NOT NULL
                AND NEW.prepared_at IS NOT NULL AND NEW.source_observed_at IS NOT NULL
                AND NEW.source_versions_json IS NOT NULL
                AND NEW.origin_segment_id IS NOT NULL
                AND NEW.origin_execution_generation IS NOT NULL
                AND NEW.origin_execution_generation > 0
                AND NEW.prepared_at >= NEW.source_observed_at
                AND JSON_VALID(NEW.source_versions_json)
                AND OCTET_LENGTH(NEW.source_versions_json) BETWEEN 2 AND 32768)
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'Invalid mission action binding';
    END IF;
    IF NEW.id <> OLD.id
        OR NEW.workspace_id <> OLD.workspace_id
        OR NEW.root_run_id <> OLD.root_run_id
        OR NEW.producer_run_id <> OLD.producer_run_id
        OR NOT (NEW.action_slot <=> OLD.action_slot)
        OR NOT (NEW.semantic_slot_key <=> OLD.semantic_slot_key)
        OR NOT (NEW.target_kind <=> OLD.target_kind)
        OR NEW.target_id <> OLD.target_id
        OR NOT (NEW.tool_name <=> OLD.tool_name)
        OR NOT (NEW.plan_item_key <=> OLD.plan_item_key)
        OR NEW.plan_revision <> OLD.plan_revision
        OR NEW.privacy_generation <> OLD.privacy_generation THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'Immutable mission action identity';
    END IF;

    IF OLD.preparation_id IS NOT NULL AND (
        NOT (NEW.preparation_id <=> OLD.preparation_id)
        OR NOT (NEW.intent_fingerprint <=> OLD.intent_fingerprint)
        OR NOT (NEW.prepared_at <=> OLD.prepared_at)
        OR NOT (NEW.source_observed_at <=> OLD.source_observed_at)
        OR NOT (CAST(NEW.source_versions_json AS BINARY)
            <=> CAST(OLD.source_versions_json AS BINARY))
        OR NOT (NEW.origin_segment_id <=> OLD.origin_segment_id)
        OR NOT (NEW.origin_execution_generation
            <=> OLD.origin_execution_generation)
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'Immutable mission action preparation';
    END IF;

    IF OLD.output_ordinal IS NOT NULL
        AND NOT (NEW.output_ordinal <=> OLD.output_ordinal) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'Immutable mission output membership';
    END IF;
END$$

CREATE TRIGGER trg_ai_chat_tool_call_mission_origin_insert
BEFORE INSERT ON ai_chat_tool_call
FOR EACH ROW
BEGIN
    IF NOT (
        (NEW.proposal_origin = 'legacy' AND NEW.mission_action_id IS NULL)
        OR
        (NEW.proposal_origin = 'chat_set'
            AND NEW.mission_action_id IS NULL
            AND NEW.artifact_origin = 'reactive')
        OR
        (NEW.proposal_origin = 'mission'
            AND NEW.mission_action_id IS NOT NULL
            AND NEW.mission_run_id IS NOT NULL
            AND NEW.artifact_origin = 'mission'
            AND NEW.server_proposal = TRUE
            AND NEW.privacy_generation IS NOT NULL
            AND NEW.privacy_generation > 0
            AND NEW.proposal_key IS NULL
            AND NEW.proposal_ordinal IS NULL
            AND NEW.proposal_prepared_at IS NULL)
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'Invalid mission proposal origin';
    END IF;
END$$

CREATE TRIGGER trg_ai_chat_tool_call_mission_origin_update
BEFORE UPDATE ON ai_chat_tool_call
FOR EACH ROW
BEGIN
    IF NOT (
        (NEW.proposal_origin = 'legacy' AND NEW.mission_action_id IS NULL)
        OR
        (NEW.proposal_origin = 'chat_set'
            AND NEW.mission_action_id IS NULL
            AND NEW.artifact_origin = 'reactive')
        OR
        (NEW.proposal_origin = 'mission'
            AND NEW.mission_action_id IS NOT NULL
            AND NEW.mission_run_id IS NOT NULL
            AND NEW.artifact_origin = 'mission'
            AND NEW.server_proposal = TRUE
            AND NEW.privacy_generation IS NOT NULL
            AND NEW.privacy_generation > 0
            AND NEW.proposal_key IS NULL
            AND NEW.proposal_ordinal IS NULL
            AND NEW.proposal_prepared_at IS NULL)
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'Invalid mission proposal origin';
    END IF;

    IF NOT (NEW.proposal_origin <=> OLD.proposal_origin)
        OR NOT (NEW.mission_action_id <=> OLD.mission_action_id)
        OR (OLD.proposal_origin = 'mission' AND (
            NEW.workspace_id <> OLD.workspace_id
            OR NEW.message_id <> OLD.message_id
            OR NOT (NEW.mission_run_id <=> OLD.mission_run_id)
            OR NOT (NEW.artifact_origin <=> OLD.artifact_origin)
            OR NOT (NEW.privacy_generation <=> OLD.privacy_generation)
            OR NEW.server_proposal <> OLD.server_proposal
            OR NOT (NEW.tool_name <=> OLD.tool_name)
            OR NOT (CAST(NEW.arguments_json AS BINARY)
                <=> CAST(OLD.arguments_json AS BINARY))
        )) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'Immutable mission proposal source';
    END IF;
END$$

CREATE TRIGGER trg_ai_agent_run_review_insert
BEFORE INSERT ON ai_agent_run
FOR EACH ROW
BEGIN
    IF (NEW.proposal_frozen_at IS NULL
            AND NEW.review_expires_at IS NOT NULL)
        OR (NEW.proposal_frozen_at IS NOT NULL AND (
            NEW.proposal_membership_revision = 0
            OR ((NEW.proposal_first_prepared_at IS NULL)
                <> (NEW.review_expires_at IS NULL))))
        OR (NEW.review_expires_at IS NOT NULL AND (
            NEW.proposal_first_prepared_at IS NULL
            OR NEW.review_expires_at <> LEAST(
                DATE_ADD(NEW.proposal_first_prepared_at, INTERVAL 72 HOUR),
                NEW.hard_deadline_at)))
        OR (NEW.status = 'waiting_approval' AND (
            NEW.proposal_frozen_at IS NULL
            OR NEW.proposal_first_prepared_at IS NULL
            OR NEW.review_expires_at IS NULL))
        OR (NEW.purged_at IS NOT NULL AND (
            NEW.proposal_membership_revision <> 0
            OR NEW.proposal_frozen_at IS NOT NULL
            OR NEW.proposal_first_prepared_at IS NOT NULL
            OR NEW.review_expires_at IS NOT NULL))
        OR (NEW.runtime_contract_version < 3 AND (
            NEW.proposal_membership_revision <> 0
            OR NEW.proposal_frozen_at IS NOT NULL
            OR NEW.proposal_first_prepared_at IS NOT NULL
            OR NEW.review_expires_at IS NOT NULL)) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'Invalid mission review metadata';
    END IF;
END$$

CREATE TRIGGER trg_ai_agent_run_review_update
BEFORE UPDATE ON ai_agent_run
FOR EACH ROW
BEGIN
    IF (NEW.proposal_frozen_at IS NULL
            AND NEW.review_expires_at IS NOT NULL)
        OR (NEW.proposal_frozen_at IS NOT NULL AND (
            NEW.proposal_membership_revision = 0
            OR ((NEW.proposal_first_prepared_at IS NULL)
                <> (NEW.review_expires_at IS NULL))))
        OR (NEW.review_expires_at IS NOT NULL AND (
            NEW.proposal_first_prepared_at IS NULL
            OR NEW.review_expires_at <> LEAST(
                DATE_ADD(NEW.proposal_first_prepared_at, INTERVAL 72 HOUR),
                NEW.hard_deadline_at)))
        OR (NEW.status = 'waiting_approval' AND (
            NEW.proposal_frozen_at IS NULL
            OR NEW.proposal_first_prepared_at IS NULL
            OR NEW.review_expires_at IS NULL))
        OR (NEW.purged_at IS NOT NULL AND (
            NEW.proposal_membership_revision <> 0
            OR NEW.proposal_frozen_at IS NOT NULL
            OR NEW.proposal_first_prepared_at IS NOT NULL
            OR NEW.review_expires_at IS NOT NULL))
        OR (NEW.runtime_contract_version < 3 AND (
            NEW.proposal_membership_revision <> 0
            OR NEW.proposal_frozen_at IS NOT NULL
            OR NEW.proposal_first_prepared_at IS NOT NULL
            OR NEW.review_expires_at IS NOT NULL)) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'Invalid mission review metadata';
    END IF;

    IF OLD.proposal_frozen_at IS NOT NULL
        AND NEW.purged_at IS NULL AND (
            NEW.proposal_membership_revision
                <> OLD.proposal_membership_revision
            OR NOT (NEW.proposal_frozen_at <=> OLD.proposal_frozen_at)
            OR NOT (NEW.proposal_first_prepared_at
                <=> OLD.proposal_first_prepared_at)
            OR NOT (NEW.review_expires_at <=> OLD.review_expires_at)
        ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'Immutable frozen mission review';
    END IF;
END$$

DELIMITER ;

-- These columns are new and unused; every pre-cutover value is NULL.
-- Suspend validation only on this migration connection, with no intervening DML.
SET @connex_m3_foreign_key_checks = @@SESSION.foreign_key_checks;
SET SESSION foreign_key_checks = 0;

ALTER TABLE ai_chat_tool_call
    ADD CONSTRAINT fk_ai_chat_tool_call_mission_action
        FOREIGN KEY (workspace_id, mission_run_id, mission_action_id)
        REFERENCES ai_agent_action (workspace_id, root_run_id, id)
        ON DELETE CASCADE ON UPDATE RESTRICT,
    ALGORITHM=INPLACE, LOCK=NONE;

SET SESSION foreign_key_checks = @connex_m3_foreign_key_checks;
SET @connex_m3_foreign_key_checks = NULL;
