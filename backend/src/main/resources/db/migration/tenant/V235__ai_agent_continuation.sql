-- Semantic runtime-2 cutover. Keep mission execution disabled during deployment.
-- Existing runtime-1 rows retain their versions and receive empty continuation fields.

ALTER TABLE ai_agent_run
    ADD COLUMN plan_schema_version SMALLINT UNSIGNED NULL DEFAULT NULL,
    ADD COLUMN plan_revision BIGINT UNSIGNED NOT NULL DEFAULT 0,
    ADD COLUMN plan_json TEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    ADD COLUMN checkpoint_schema_version SMALLINT UNSIGNED NULL DEFAULT NULL,
    ADD COLUMN checkpoint_revision BIGINT UNSIGNED NOT NULL DEFAULT 0,
    ADD COLUMN checkpoint_json TEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    ADD COLUMN memory_schema_version SMALLINT UNSIGNED NULL DEFAULT NULL,
    ADD COLUMN memory_json TEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    ADD COLUMN last_committed_segment_no SMALLINT UNSIGNED NOT NULL DEFAULT 0,
    ALTER COLUMN runtime_contract_version SET DEFAULT 2,
    ALGORITHM=INSTANT, LOCK=DEFAULT;

ALTER TABLE ai_agent_segment
    DROP CHECK chk_ai_agent_segment_attempt,
    ALGORITHM=INPLACE, LOCK=NONE;

DELIMITER $$

CREATE TRIGGER trg_ai_agent_segment_attempt_insert
BEFORE INSERT ON ai_agent_segment
FOR EACH ROW
BEGIN
    IF NEW.segment_no NOT BETWEEN 1 AND 8
        OR NEW.attempt_no NOT BETWEEN 1 AND 3 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'Invalid mission segment attempt';
    END IF;
END$$

CREATE TRIGGER trg_ai_agent_segment_attempt_update
BEFORE UPDATE ON ai_agent_segment
FOR EACH ROW
BEGIN
    IF NEW.segment_no NOT BETWEEN 1 AND 8
        OR NEW.attempt_no NOT BETWEEN 1 AND 3 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'Invalid mission segment attempt';
    END IF;

    IF NEW.segment_no <> OLD.segment_no
        OR NEW.attempt_no <> OLD.attempt_no THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'Immutable mission segment attempt';
    END IF;
END$$

DELIMITER ;
