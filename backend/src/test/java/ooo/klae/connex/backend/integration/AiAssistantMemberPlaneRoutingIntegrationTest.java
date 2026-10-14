package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import ooo.klae.connex.backend.ai.assistant.AiAssistantMemberIdentifiers;
import ooo.klae.connex.backend.ai.masking.MaskingContext;
import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.TenantRoutingProperties;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/** Member candidates stay control-plane reads even inside an identity-free dedicated catalog. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class AiAssistantMemberPlaneRoutingIntegrationTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private OrganizationMapper organizations;
    @Autowired private WorkspaceMapper workspaces;
    @Autowired private WorkspaceService workspaceService;
    @Autowired private TenantContext tenantContext;
    @Autowired private TenantWorkScope workScope;
    @Autowired private PlatformTransactionManager transactionManager;

    private final String unique = UUID.randomUUID().toString().replace("-", "");
    private final String catalog = "connex_member_" + unique;
    private Organization organization;
    private Workspace workspace;
    private boolean catalogCreated;

    @DynamicPropertySource
    static void routing(DynamicPropertyRegistry registry) {
        registry.add("connex.tenancy.routing.mode", () -> TenantRoutingProperties.MODE_CATALOG_PER_PLACEMENT);
        registry.add("connex.tenancy.routing.default-catalog", () -> {
            String url = System.getenv("CONNEX_DB_URL");
            assertTrue(url != null && url.contains("/"), "This test requires a real MySQL schema");
            return url.substring(url.lastIndexOf('/') + 1).split("\\?", 2)[0];
        });
    }

    @AfterEach
    void cleanUp() {
        tenantContext.clear();
        workScope.withCatalog(null, () -> {
            if (workspace != null) {
                jdbc.update("DELETE FROM workspace_member WHERE workspace_id = ?", workspace.getId());
                jdbc.update("DELETE FROM workspace WHERE id = ?", workspace.getId());
            }
            jdbc.update("DELETE FROM app_user WHERE username IN (?, ?)", "member-active-" + unique, "member-pending-" + unique);
            if (organization != null) {
                jdbc.update("DELETE FROM organization WHERE id = ?", organization.getId());
            }
            if (catalogCreated) {
                jdbc.execute("DROP DATABASE " + catalog);
            }
            return null;
        });
    }

    @Test
    void memberReadsSwitchOnlyToControlCatalogAndRestoreTheSameTransactionConnection() {
        organization = new Organization();
        organization.setName("Member routing " + unique);
        organization.setSlug("member-routing-" + unique);
        organizations.insert(organization);
        workspace = new Workspace();
        workspace.setOrgId(organization.getId());
        workspace.setName("Member routing " + unique);
        workspace.setSlug("member-routing-" + unique);
        workspaces.insert(workspace);
        jdbc.update("INSERT INTO app_user (username, display_name, email, password_hash, timezone) VALUES "
                        + "(?, 'Active Member Label', ?, 'fixture-hash', 'UTC'), "
                        + "(?, 'Pending Member Label', ?, 'fixture-hash', 'UTC')",
                "member-active-" + unique, "active-" + unique + "@example.test",
                "member-pending-" + unique, "pending-" + unique + "@example.test");
        jdbc.update("INSERT INTO workspace_member (workspace_id, user_id, role, status) "
                        + "SELECT ?, id, 'member', IF(username = ?, 'active', 'pending') FROM app_user WHERE username IN (?, ?)",
                workspace.getId(), "member-active-" + unique, "member-active-" + unique, "member-pending-" + unique);
        int actor = jdbc.queryForObject("SELECT id FROM app_user WHERE username = ?", Integer.class, "member-active-" + unique);
        jdbc.execute("CREATE DATABASE " + catalog);
        catalogCreated = true;
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = ?", Integer.class, catalog));
        tenantContext.set(workspace.getId(), organization.getId(), actor, "member", catalog);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.executeWithoutResult(status -> {
            Long connection = jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class);
            assertEquals(catalog, jdbc.queryForObject("SELECT DATABASE()", String.class));
            List<User> members = workspaceService.getMembers(workspace.getId());
            assertEquals(List.of(actor), members.stream().map(User::getId).toList());
            MaskingContext context = new MaskingContext();
            AiAssistantMemberIdentifiers.seed(members, context);
            assertEquals(List.of("Active Member Label"), List.copyOf(context.identifierDictionary()));
            assertEquals(0, context.tokenBindings().size());
            assertEquals(connection, jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
            assertEquals(catalog, jdbc.queryForObject("SELECT DATABASE()", String.class));
        });
    }
}
