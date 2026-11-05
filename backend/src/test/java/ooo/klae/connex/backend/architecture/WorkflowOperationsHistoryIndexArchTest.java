package ooo.klae.connex.backend.architecture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import org.junit.jupiter.api.Test;

/** Pins workspace chronological paging and fail-closed online index creation. */
class WorkflowOperationsHistoryIndexArchTest {
    @Test
    void tenantMigrationIndexesTheWorkspaceSortWithoutChangingForeignKeys() throws Exception {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(
                "db/migration/tenant/V239__rule_execution_workspace_history_index.sql")) {
            assertNotNull(input);
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8)
                .replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
            assertTrue(sql.contains("alter table rule_execution"));
            assertTrue(sql.contains("(workspace_id, executed_at desc, id desc)"));
            assertTrue(sql.contains("algorithm=inplace, lock=none"));
            assertFalse(sql.contains("drop"));
            assertFalse(sql.contains("foreign_key_checks"));
            assertFalse(sql.contains("algorithm=copy"));
        }
    }
}
