package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

/**
 * Pins the exact lock primitives the workflow authoring chain uses, in documented order: the
 * workspace root stays shared so audited CRM writes are never blocked, and mutual exclusion for
 * trigger admission comes from the dedicated {@code workflow_trigger_admission} row.
 *
 * <p>The admission mutex is the upsert itself. InnoDB places an exclusive lock on the duplicate
 * row when {@code INSERT ... ON DUPLICATE KEY UPDATE} hits the primary key and holds it to commit,
 * so the statement both creates the row on first use and serialises later authors. Pinning a
 * trailing {@code SELECT ... FOR UPDATE} instead would name a statement that never contends.
 */
class WorkflowPrincipalMapperXmlTest {

    @Test
    void principalAuthorizationStatementsUseExactOrderedLockPrimitives() throws Exception {
        Configuration configuration = configuration();

        assertTrue(sql(
            configuration, UserMapper.class, "lockById", Map.of("id", 7))
            .endsWith("WHERE id = ? FOR UPDATE"));

        String workspace = sql(
            configuration,
            WorkspaceMapper.class,
            "lockWorkspaceForShare",
            Map.of("workspaceId", 5));
        assertTrue(workspace.contains("WHERE id = ?"));
        assertTrue(workspace.endsWith("FOR SHARE"));

        String admission = sql(
            configuration,
            WorkflowMapper.class,
            "acquireTriggerAdmissionMutex",
            Map.of("workspaceId", 5));
        assertTrue(admission.startsWith("INSERT INTO workflow_trigger_admission"));
        assertTrue(admission.endsWith("ON DUPLICATE KEY UPDATE workspace_id = "
            + "workflow_trigger_admission.workspace_id"));

        String membership = sql(
            configuration,
            WorkspaceMapper.class,
            "lockAuthorizationMembership",
            Map.of("workspaceId", 5, "userId", 7));
        assertTrue(membership.contains("workspace_id = ?"));
        assertTrue(membership.contains("user_id = ?"));
        assertTrue(membership.contains("role_id"));
        assertTrue(membership.contains("status"));
        assertTrue(membership.endsWith("FOR UPDATE"));

        String role = sql(
            configuration,
            RoleMapper.class,
            "lockRole",
            Map.of("workspaceId", 5, "id", 11));
        assertTrue(role.contains("workspace_id = ?"));
        assertTrue(role.contains("id = ?"));
        assertTrue(role.endsWith("FOR UPDATE"));

        String permissions = sql(
            configuration,
            RoleMapper.class,
            "lockPermissions",
            Map.of("workspaceId", 5, "roleId", 11));
        assertTrue(permissions.contains("wr.workspace_id = ?"));
        assertTrue(permissions.contains("wrp.workspace_role_id = ?"));
        assertTrue(permissions.contains("wrp.permission NOT IN"));
        assertTrue(permissions.contains("ORDER BY wrp.permission"));
        assertTrue(permissions.endsWith("FOR UPDATE"));
    }

    private static Configuration configuration() throws Exception {
        Configuration configuration = new Configuration();
        configuration.getTypeAliasRegistry().registerAliases("ooo.klae.connex.backend.beans");
        for (String resource : List.of(
                "mappers/UserMapper.xml",
                "mappers/WorkspaceMapper.xml",
                "mappers/RoleMapper.xml",
                "mappers/WorkflowMapper.xml")) {
            try (InputStream input = WorkflowPrincipalMapperXmlTest.class
                    .getClassLoader().getResourceAsStream(resource)) {
                assertNotNull(input);
                new XMLMapperBuilder(
                    input, configuration, resource, configuration.getSqlFragments()).parse();
            }
        }
        return configuration;
    }

    private static String sql(
            Configuration configuration, Class<?> mapper, String statement, Object parameters) {
        return configuration.getMappedStatement(mapper.getName() + "." + statement)
            .getBoundSql(parameters)
            .getSql()
            .replaceAll("\\s+", " ")
            .trim();
    }
}
