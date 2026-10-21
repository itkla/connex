package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.tenant.TenantScopeInterceptor;

/** Parses the control contract and pins exact locking keys without a database. */
class NotificationControlMapperXmlTest {
    private static final String NAMESPACE = "ooo.klae.connex.backend.mappers.NotificationControlMapper";
    private Configuration configuration;

    @BeforeEach
    void loadMapper() throws Exception {
        configuration = new Configuration();
        configuration.getTypeAliasRegistry().registerAlias("Workspace", Workspace.class);
        String resource = "mappers/NotificationControlMapper.xml";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input);
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
    }

    @Test
    void recipientAndOrganizationBatchesRetainUnfilteredLifecycleSemantics() {
        String memberships = sql("findRecipientMemberships", Map.of("recipientId", 42));
        assertTrue(memberships.contains("WHERE wm.user_id = ?"));
        assertTrue(memberships.contains("wm.membership_id"));
        assertTrue(memberships.contains("w.lifecycle_state AS workspace_lifecycle_state"));
        assertTrue(memberships.contains("o.lifecycle_state AS organization_lifecycle_state"));
        assertFalse(memberships.contains("= 'active'"));
        assertFalse(memberships.contains("FOR SHARE"));
        String organizations = sql("findOrganizationWorkspaces", Map.of("orgIds", List.of(900, 901)));
        assertTrue(organizations.contains("WHERE org_id IN ( ? , ? )"));
        assertFalse(organizations.contains("lifecycle_state"));
        assertFalse(organizations.contains("JOIN organization"));
        assertTrue(TenantScopeInterceptor.CONTROL_PLANE_NAMESPACES.contains(NAMESPACE));
        assertFalse(TenantScopeInterceptor.SCOPED_NAMESPACES.contains(NAMESPACE));
    }

    @Test
    void currentMembershipLocksUseOnlyBothExactKeysAndBypassCaches() {
        for (String statement : List.of("lockMembershipForShare", "lockMembershipForUpdate")) {
            MappedStatement mapped = configuration.getMappedStatement(NAMESPACE + "." + statement);
            String sql = sql(statement, Map.of("workspaceId", 7, "recipientId", 42));
            assertTrue(sql.contains("WHERE workspace_id = ? AND user_id = ?"));
            assertTrue(sql.contains("status, membership_id"));
            assertTrue(sql.endsWith(statement.endsWith("Share") ? "FOR SHARE" : "FOR UPDATE"));
            assertFalse(sql.contains("JOIN"));
            assertFalse(sql.contains("'active'"));
            assertTrue(mapped.isFlushCacheRequired());
            assertFalse(mapped.isUseCache());
            assertEquals(List.of("workspaceId", "recipientId"), mapped.getBoundSql(
                Map.of("workspaceId", 7, "recipientId", 42)).getParameterMappings().stream()
                .map(parameter -> parameter.getProperty()).toList());
        }
    }

    @Test
    void actorLockIsCurrentAndDoesNotPullMembershipOrTenantTablesIntoControlSql() {
        MappedStatement mapped = configuration.getMappedStatement(NAMESPACE + ".lockActorForShare");
        assertEquals("SELECT id FROM app_user WHERE id = ? FOR SHARE",
            sql("lockActorForShare", Map.of("actorId", 81)));
        assertTrue(mapped.isFlushCacheRequired());
        assertFalse(mapped.isUseCache());
    }

    private String sql(String statement, Map<String, ?> parameters) {
        return configuration.getMappedStatement(NAMESPACE + "." + statement)
            .getBoundSql(parameters).getSql().replaceAll("\\s+", " ").trim();
    }
}
