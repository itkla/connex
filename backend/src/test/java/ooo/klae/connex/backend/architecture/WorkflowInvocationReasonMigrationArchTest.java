package ooo.klae.connex.backend.architecture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.tenant.TenantLifecycleRegistry;
import ooo.klae.connex.backend.tenant.TablePlaneRegistry;

/** Pins the additive durable manual-preparation reason contract. */
class WorkflowInvocationReasonMigrationArchTest {

    @Test
    void migrationAddsOnlyTheClosedEnrollmentPreviewReasons() throws Exception {
        String sql = compact(resource(
            "db/migration/tenant/V210__workflow_invocation_preview_reasons.sql"));

        assertTrue(sql.contains("DROP CHECK chk_workflow_invocation_record_preview"));
        assertTrue(sql.contains("'record_unavailable'"));
        assertTrue(sql.contains("'entry_condition_not_matched'"));
        assertTrue(sql.contains("'active_run_exists'"));
        assertTrue(sql.contains("'cooldown_active'"));
        assertFalse(sql.contains("DROP CHECK chk_workflow_invocation_record_failure"));
    }

    @Test
    void invocationRecordsRemainTenantOwnedAndDeleteBeforeTheirParents() {
        var record = TenantLifecycleRegistry.require("workflow_invocation_record");
        var invocation = TenantLifecycleRegistry.require("workflow_invocation");
        var run = TenantLifecycleRegistry.require("workflow_run");

        assertTrue(record.direct());
        assertTrue(record.deleteOrder() < invocation.deleteOrder());
        assertTrue(record.deleteOrder() < run.deleteOrder());
        assertTrue(TablePlaneRegistry.ORG_DATA_TABLES.contains(
            "workflow_invocation_record"));
    }

    private static String resource(String name) throws Exception {
        try (InputStream input = WorkflowInvocationReasonMigrationArchTest.class
                .getClassLoader().getResourceAsStream(name)) {
            if (input == null) {
                throw new IllegalStateException("Migration resource is unavailable");
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String compact(String value) {
        return value.replaceAll("\\s+", " ").trim();
    }
}
