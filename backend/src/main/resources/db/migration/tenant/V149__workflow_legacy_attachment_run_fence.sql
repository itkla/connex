DELIMITER //
CREATE TRIGGER trg_workflow_legacy_attachment_run_fence
BEFORE UPDATE ON workflow
FOR EACH ROW
BEGIN
    DECLARE has_canonical_run BOOLEAN DEFAULT FALSE;

    IF OLD.legacy_rule_id IS NULL AND NEW.legacy_rule_id IS NOT NULL THEN
        SELECT EXISTS(
            SELECT 1
            FROM workflow_run
            WHERE workspace_id = OLD.workspace_id
              AND workflow_id = OLD.id
            LIMIT 1
            FOR SHARE
        )
        INTO has_canonical_run;

        IF has_canonical_run THEN
            SIGNAL SQLSTATE '45000'
                SET MESSAGE_TEXT =
                    'workflow run history forbids first legacy rule attachment';
        END IF;
    END IF;
END//
DELIMITER ;
