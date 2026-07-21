package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

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
                "mappers/RoleMapper.xml")) {
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
