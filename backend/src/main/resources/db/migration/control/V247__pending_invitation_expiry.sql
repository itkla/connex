-- Pending membership grants need a deadline; active memberships have no invitation deadline.
SET @pending_invitation_v247_ddl = (
    SELECT CASE
        WHEN COUNT(*) = 0 THEN
            'ALTER TABLE workspace_member ADD COLUMN expires_at DATETIME NULL COMMENT ''Pending invitation deadline (UTC)'', ALGORITHM=INSTANT, LOCK=DEFAULT'
        WHEN COUNT(*) = 1 AND SUM(DATA_TYPE = 'datetime' AND DATETIME_PRECISION = 0
                 AND IS_NULLABLE = 'YES' AND COLUMN_DEFAULT IS NULL
                 AND EXTRA = '' AND GENERATION_EXPRESSION = '') = 1 THEN 'DO 0'
        ELSE 'V247_incompatible_workspace_member_expires_at'
    END
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'workspace_member' AND COLUMN_NAME = 'expires_at'
);
PREPARE pending_invitation_v247_statement FROM @pending_invitation_v247_ddl;
EXECUTE pending_invitation_v247_statement;
DEALLOCATE PREPARE pending_invitation_v247_statement;

-- Give historical pending grants thirty days of notice without extending an existing deadline.
UPDATE workspace_member
SET expires_at = UTC_TIMESTAMP() + INTERVAL 30 DAY
WHERE expires_at IS NULL AND status = 'pending';
