package ooo.klae.connex.backend.architecture;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.tenant.TablePlaneRegistry;

/** Pins the S1 plane boundary and semantic rollback compatibility independently of feature flags. */
class StandingApprovalControlArchTest {
    @Test
    void organizationSettingsAreControlStateAndNotAssistantContent() throws Exception {
        String table = "organization_ai_standing_approval_policy";
        assertTrue(TablePlaneRegistry.CONTROL_PLANE_STATE_TABLES.contains(table));
        assertFalse(TablePlaneRegistry.ORG_DATA_TABLES.contains(table));
        assertFalse(TablePlaneRegistry.CONTROL_PLANE_WORKSPACE_DATA_TABLES.contains(table));
        String migration = Files.readString(Path.of("src/main/resources/db/migration/control/V240__ai_standing_approval_control.sql"));
        assertTrue(migration.contains("REFERENCES organization (id) ON DELETE CASCADE"));
        assertFalse(migration.contains("user_id"));
        assertFalse(migration.contains("workspace_id"));
        assertFalse(migration.contains("UPDATE "));
        assertFalse(migration.contains("ALGORITHM=COPY"));
        assertEquals(2, migration.split("ALGORITHM=INSTANT", -1).length - 1);
        assertEquals("control/V240__ai_standing_approval_control.sql", Files.readString(
                Path.of("../deploy/staging/rollback-floor")).strip());
    }

    @Test
    void allPolicyStatementsAreExplicitlyClassifiedOnTheControlPlane() throws Exception {
        String interceptor = Files.readString(Path.of("src/main/java/ooo/klae/connex/backend/tenant/TenantScopeInterceptor.java"));
        assertTrue(interceptor.contains("MAPPERS + \"AiStandingApprovalPolicyMapper\""));
        String access = Files.readString(Path.of("src/main/java/ooo/klae/connex/backend/ai/AiStandingApprovalControlAccess.java"));
        assertTrue(access.contains("executeRead(ControlPlaneRead<T> read)"));
        assertTrue(access.contains("tenantWorkScope.unroutedRead(read)"));
    }
}
