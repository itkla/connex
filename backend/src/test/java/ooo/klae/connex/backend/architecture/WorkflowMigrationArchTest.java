package ooo.klae.connex.backend.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/** Verifies the workflow-domain migration's schema, tenant ownership, and offboarding contracts. */
class WorkflowMigrationArchTest {

    private static final String RESOURCE = "db/migration/tenant/V116__workflow_domain.sql";

    @Test
    void createsOnlyWorkflowDomainTablesWithPinnedMetadataTypes() throws Exception {
        String sql = migration();
        String compact = sql.replaceAll("\\s+", " ");

        Matcher tables = Pattern.compile("CREATE TABLE (\\w+)").matcher(sql);
        assertTrue(tables.find());
        assertEquals("workflow", tables.group(1));
        assertTrue(tables.find());
        assertEquals("workflow_version", tables.group(1));
        assertFalse(tables.find());

        assertTrue(compact.contains("id INT AUTO_INCREMENT PRIMARY KEY"));
        assertTrue(compact.contains("workspace_id INT NOT NULL"));
        assertTrue(compact.contains("legacy_rule_id INT NULL"));
        assertTrue(compact.contains("name VARCHAR(128) NOT NULL"));
        assertTrue(compact.contains("description VARCHAR(512) NULL"));
        assertTrue(compact.contains("draft_record_type VARCHAR(16) NULL"));
        assertTrue(compact.contains("draft_execution_mode VARCHAR(8) NOT NULL"));
        assertTrue(compact.contains("active_version_id BIGINT NULL"));
        assertTrue(compact.contains("id BIGINT AUTO_INCREMENT PRIMARY KEY"));
        assertTrue(compact.contains("record_type VARCHAR(16) NOT NULL"));
        assertTrue(compact.contains("trigger_type VARCHAR(16) NOT NULL"));
        assertTrue(compact.contains("execution_mode VARCHAR(8) NOT NULL"));
        assertTrue(compact.contains("definition_hash BINARY(32) NOT NULL"));
        assertTrue(compact.contains("trigger_config JSON NOT NULL"));
        assertTrue(compact.contains("condition_json JSON NULL"));
        assertTrue(compact.contains("actions_json JSON NOT NULL"));
        assertEquals(4, occurrences(compact, "MEDIUMTEXT NOT NULL"));
        assertEquals(2, occurrences(compact, "DEFAULT CHARSET=utf8mb4"));
    }

    @Test
    void enforcesScopedOwnershipLifecycleAndJsonShapes() throws Exception {
        String compact = migration().replaceAll("\\s+", " ");

        assertTrue(compact.contains("ADD UNIQUE KEY uq_rule_workspace_id (workspace_id, id)"));
        assertTrue(compact.contains("UNIQUE KEY uq_workflow_workspace_id (workspace_id, id)"));
        assertTrue(compact.contains("UNIQUE KEY uq_workflow_legacy_rule (workspace_id, legacy_rule_id)"));
        assertTrue(compact.contains("FOREIGN KEY (workspace_id, legacy_rule_id) REFERENCES rule(workspace_id, id) ON DELETE RESTRICT"));
        assertTrue(compact.contains("FOREIGN KEY (workspace_id, workflow_id) REFERENCES workflow(workspace_id, id) ON DELETE CASCADE"));
        assertTrue(compact.contains("UNIQUE KEY uq_workflow_version_identity (workspace_id, workflow_id, id)"));
        assertTrue(compact.contains("UNIQUE KEY uq_workflow_version_number (workspace_id, workflow_id, version_number)"));
        assertTrue(compact.contains("FOREIGN KEY (workspace_id, id, active_version_id) REFERENCES workflow_version(workspace_id, workflow_id, id) ON DELETE RESTRICT"));
        assertTrue(compact.contains("draft_revision >= 0"));
        assertTrue(compact.contains("version_number > 0"));
        assertTrue(compact.contains("CONSTRAINT chk_workflow_enabled CHECK (enabled IN (FALSE, TRUE))"));
        assertFalse(compact.contains("enabled = FALSE OR active_version_id IS NOT NULL"));
        assertTrue(compact.contains("JSON_TYPE(draft_definition_json) = 'OBJECT'"));
        assertTrue(compact.contains("JSON_VALID(draft_definition_json) = 1"));
        assertTrue(compact.contains("JSON_CONTAINS_PATH(draft_definition_json, 'one', '$.schemaVersion') = 1"));
        assertTrue(compact.contains("OCTET_LENGTH(draft_definition_json) <= 65536"));
        assertTrue(compact.contains("JSON_VALID(draft_canvas_json) = 1"));
        assertTrue(compact.contains("OCTET_LENGTH(draft_canvas_json) <= 16384"));
        assertTrue(compact.contains("JSON_TYPE(trigger_config) = 'OBJECT'"));
        assertTrue(compact.contains("condition_json IS NULL OR JSON_TYPE(condition_json) = 'OBJECT'"));
        assertTrue(compact.contains("JSON_TYPE(actions_json) = 'ARRAY'"));
        assertTrue(compact.contains("JSON_TYPE(definition_json) = 'OBJECT'"));
        assertTrue(compact.contains("JSON_TYPE(canvas_json) = 'OBJECT'"));
        assertTrue(compact.contains("JSON_VALID(definition_json) = 1"));
        assertTrue(compact.contains("OCTET_LENGTH(definition_json) <= 65536"));
        assertTrue(compact.contains("JSON_VALID(canvas_json) = 1"));
        assertTrue(compact.contains("OCTET_LENGTH(canvas_json) <= 16384"));
        assertFalse(compact.contains("REFERENCES workspace("));
        assertFalse(compact.contains("REFERENCES app_user("));
    }

    @Test
    void givesEveryUserReferenceALeadingIndex() throws Exception {
        String sql = migration();
        for (String column : List.of(
                "draft_run_as_user_id",
                "created_by_id",
                "updated_by_id",
                "run_as_user_id",
                "published_by_id")) {
            assertTrue(Pattern.compile("INDEX\\s+\\w+\\s*\\(\\s*" + column + "(?:\\s*,|\\s*\\))")
                .matcher(sql).find(), column);
        }
        assertEquals(2, leadingIndexCount(sql, "created_by_id"));
    }

    @Test
    void keepsCheckAndForeignKeyNamesSchemaUniqueAndWithinMysqlLimit() throws Exception {
        Matcher matcher = Pattern.compile("CONSTRAINT\\s+(\\w+)").matcher(migration());
        Set<String> names = new HashSet<>();
        while (matcher.find()) {
            String name = matcher.group(1);
            assertTrue(name.length() <= 64, name);
            assertTrue(names.add(name), name);
        }
        assertEquals(15, names.size());
    }

    private String migration() throws Exception {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(RESOURCE)) {
            assertNotNull(input);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static int occurrences(String value, String token) {
        return value.split(Pattern.quote(token), -1).length - 1;
    }

    private static int leadingIndexCount(String sql, String column) {
        Matcher matcher = Pattern.compile("INDEX\\s+\\w+\\s*\\(\\s*" + column + "(?:\\s*,|\\s*\\))")
            .matcher(sql);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }
}
