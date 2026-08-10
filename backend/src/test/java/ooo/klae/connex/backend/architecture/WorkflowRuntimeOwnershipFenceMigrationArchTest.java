package ooo.klae.connex.backend.architecture;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/** Pins the database-enforced mixed-version workflow ownership fence. */
class WorkflowRuntimeOwnershipFenceMigrationArchTest {

    private static final String RESOURCE =
        "db/migration/tenant/V149__workflow_legacy_attachment_run_fence.sql";

    @Test
    void firstLegacyAttachmentUsesCurrentRunHistoryAndFailsClosed() throws Exception {
        String sql = compact();

        assertTrue(sql.contains(
            "CREATE TRIGGER trg_workflow_legacy_attachment_run_fence BEFORE UPDATE ON workflow"));
        assertTrue(sql.contains(
            "OLD.legacy_rule_id IS NULL AND NEW.legacy_rule_id IS NOT NULL"));
        assertTrue(sql.contains("FROM workflow_run"));
        assertTrue(sql.contains("workspace_id = OLD.workspace_id"));
        assertTrue(sql.contains("workflow_id = OLD.id"));
        assertTrue(sql.contains("LIMIT 1 FOR SHARE"));
        assertTrue(sql.contains("SIGNAL SQLSTATE '45000'"));
    }

    private static String compact() throws Exception {
        try (InputStream input = WorkflowRuntimeOwnershipFenceMigrationArchTest.class
                .getClassLoader().getResourceAsStream(RESOURCE)) {
            assertNotNull(input);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8)
                .replaceAll("\\s+", " ")
                .trim();
        }
    }
}
