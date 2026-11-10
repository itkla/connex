-- Built-in roles retain export access by default; custom roles receive one explicit rollout grant.
-- Conditional DDL permits retry after a committed MySQL DDL prefix without resetting policy.

SET @export_data_v248_ddl = (
    SELECT CASE
        WHEN COUNT(*) = 0 THEN
            'ALTER TABLE workspace ADD COLUMN export_data_owner BOOLEAN NOT NULL DEFAULT TRUE, ALGORITHM=INSTANT'
        WHEN COUNT(*) = 1 AND SUM(DATA_TYPE = 'tinyint' AND IS_NULLABLE = 'NO'
                 AND COLUMN_DEFAULT = '1' AND EXTRA = '' AND GENERATION_EXPRESSION = '') = 1 THEN 'DO 0'
        ELSE 'V248_incompatible_workspace_export_data_owner'
    END
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'workspace' AND COLUMN_NAME = 'export_data_owner'
);
PREPARE export_data_v248_statement FROM @export_data_v248_ddl;
EXECUTE export_data_v248_statement;
DEALLOCATE PREPARE export_data_v248_statement;

SET @export_data_v248_ddl = (
    SELECT CASE
        WHEN COUNT(*) = 0 THEN
            'ALTER TABLE workspace ADD COLUMN export_data_admin BOOLEAN NOT NULL DEFAULT TRUE, ALGORITHM=INSTANT'
        WHEN COUNT(*) = 1 AND SUM(DATA_TYPE = 'tinyint' AND IS_NULLABLE = 'NO'
                 AND COLUMN_DEFAULT = '1' AND EXTRA = '' AND GENERATION_EXPRESSION = '') = 1 THEN 'DO 0'
        ELSE 'V248_incompatible_workspace_export_data_admin'
    END
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'workspace' AND COLUMN_NAME = 'export_data_admin'
);
PREPARE export_data_v248_statement FROM @export_data_v248_ddl;
EXECUTE export_data_v248_statement;
DEALLOCATE PREPARE export_data_v248_statement;

SET @export_data_v248_ddl = (
    SELECT CASE
        WHEN COUNT(*) = 0 THEN
            'ALTER TABLE workspace ADD COLUMN export_data_member BOOLEAN NOT NULL DEFAULT TRUE, ALGORITHM=INSTANT'
        WHEN COUNT(*) = 1 AND SUM(DATA_TYPE = 'tinyint' AND IS_NULLABLE = 'NO'
                 AND COLUMN_DEFAULT = '1' AND EXTRA = '' AND GENERATION_EXPRESSION = '') = 1 THEN 'DO 0'
        ELSE 'V248_incompatible_workspace_export_data_member'
    END
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'workspace' AND COLUMN_NAME = 'export_data_member'
);
PREPARE export_data_v248_statement FROM @export_data_v248_ddl;
EXECUTE export_data_v248_statement;
DEALLOCATE PREPARE export_data_v248_statement;

SET @export_data_v248_ddl = (
    SELECT CASE
        WHEN COUNT(*) = 0 THEN
            'ALTER TABLE organization ADD COLUMN export_data_owner BOOLEAN NOT NULL DEFAULT TRUE, ALGORITHM=INSTANT'
        WHEN COUNT(*) = 1 AND SUM(DATA_TYPE = 'tinyint' AND IS_NULLABLE = 'NO'
                 AND COLUMN_DEFAULT = '1' AND EXTRA = '' AND GENERATION_EXPRESSION = '') = 1 THEN 'DO 0'
        ELSE 'V248_incompatible_organization_export_data_owner'
    END
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'organization' AND COLUMN_NAME = 'export_data_owner'
);
PREPARE export_data_v248_statement FROM @export_data_v248_ddl;
EXECUTE export_data_v248_statement;
DEALLOCATE PREPARE export_data_v248_statement;

SET @export_data_v248_ddl = (
    SELECT CASE
        WHEN COUNT(*) = 0 THEN
            'ALTER TABLE organization ADD COLUMN export_data_admin BOOLEAN NOT NULL DEFAULT TRUE, ALGORITHM=INSTANT'
        WHEN COUNT(*) = 1 AND SUM(DATA_TYPE = 'tinyint' AND IS_NULLABLE = 'NO'
                 AND COLUMN_DEFAULT = '1' AND EXTRA = '' AND GENERATION_EXPRESSION = '') = 1 THEN 'DO 0'
        ELSE 'V248_incompatible_organization_export_data_admin'
    END
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'organization' AND COLUMN_NAME = 'export_data_admin'
);
PREPARE export_data_v248_statement FROM @export_data_v248_ddl;
EXECUTE export_data_v248_statement;
DEALLOCATE PREPARE export_data_v248_statement;

INSERT INTO workspace_role_permission (workspace_role_id, permission)
SELECT wr.id, 'EXPORT_DATA'
FROM workspace_role wr
WHERE NOT EXISTS (
    SELECT 1 FROM workspace_role_permission wrp
    WHERE wrp.workspace_role_id = wr.id AND wrp.permission = 'EXPORT_DATA'
);
