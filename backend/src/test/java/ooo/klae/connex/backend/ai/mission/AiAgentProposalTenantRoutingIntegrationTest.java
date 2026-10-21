package ooo.klae.connex.backend.ai.mission;

import static ooo.klae.connex.backend.support.OrganizationShareScopes.orgWorkspaceIdsJson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import jakarta.servlet.Filter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.config.TenantRoutingConfig;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.TenantRoutingProperties;

/** Known mission ids remain catalog-local for a requester belonging to two organizations. */
@SpringBootTest
class AiAgentProposalTenantRoutingIntegrationTest extends AbstractAiAgentProposalIntegrationTest {
    private static final String PASSWORD = "Mission-Catalog-Route9!";
    private static final List<String> TENANT_TABLES = List.of("company", "company_share", "ai_agent_run",
            "ai_agent_segment", "ai_agent_action", "ai_chat_session", "ai_chat_session_participant",
            "ai_chat_message", "ai_chat_tool_call", "ai_chat_turn", "ai_agent_privacy_barrier");
    @Autowired private WebApplicationContext application;
    @Autowired @Qualifier("springSecurityFilterChain") private Filter security;
    @Autowired private PasswordEncoder passwords;
    @Autowired private OrganizationMapper organizations;
    @Autowired private TenantContext tenantContext;
    private MockMvc http;
    private Organization foreignOrganization;
    private Workspace foreignWorkspace;
    private String catalog;

    @DynamicPropertySource
    static void routing(DynamicPropertyRegistry registry) {
        registry.add("connex.tenancy.routing.mode", () -> TenantRoutingProperties.MODE_CATALOG_PER_PLACEMENT);
        registry.add("connex.tenancy.routing.default-catalog", AiAgentProposalTenantRoutingIntegrationTest::defaultCatalog);
    }

    @BeforeEach
    void prepareHttp() {
        http = MockMvcBuilders.webAppContextSetup(application).addFilters(security).build();
    }

    @AfterEach
    void removeForeignCatalog() {
        tenantContext.clear();
        if (foreignOrganization != null) {
            jdbc.update("DELETE FROM org_placement WHERE org_id=?", foreignOrganization.getId());
        }
        if (foreignWorkspace != null) {
            ooo.klae.connex.backend.support.TestEntityFixtures.deleteChatPrivacy(jdbc, foreignWorkspace.getId());
            jdbc.update("DELETE FROM workspace_member WHERE workspace_id=?", foreignWorkspace.getId());
            jdbc.update("DELETE FROM workspace WHERE id=?", foreignWorkspace.getId());
        }
        if (foreignOrganization != null) {
            ooo.klae.connex.backend.support.TestEntityFixtures.deleteOrganizationPrivacy(jdbc, foreignOrganization.getId());
            jdbc.update("DELETE FROM organization WHERE id=?", foreignOrganization.getId());
        }
        if (catalog != null) {
            jdbc.execute("DROP DATABASE IF EXISTS `" + identifier(catalog) + "`");
        }
    }

    @Test
    void foreignCatalogWithCollidingRunActionAndToolIdsNeverReadsOrMutatesDefaultTenant() throws Exception {
        Fixture original = frozen("assign_owner");
        var originalCall = chatMapper.getMissionToolCallForMaintenance(workspace.getId(), original.runId(), original.selected().getFirst().getId());
        foreignOrganization = new Organization();
        foreignOrganization.setName("Foreign mission routing");
        foreignOrganization.setSlug("foreign-mission-" + UUID.randomUUID());
        organizations.insert(foreignOrganization);
        foreignWorkspace = new Workspace();
        foreignWorkspace.setOrgId(foreignOrganization.getId());
        foreignWorkspace.setName("Foreign mission routing");
        foreignWorkspace.setSlug("foreign-mission-" + UUID.randomUUID());
        workspaceMapper.insert(foreignWorkspace);
        workspaceMapper.addMember(foreignWorkspace.getId(), currentUser.getId(), "member");
        ooo.klae.connex.backend.support.TestEntityFixtures.seedExistingWorkspacePrivacy(jdbc, foreignWorkspace.getId());
        grantAiUse(workspace.getId());
        grantAiUse(foreignWorkspace.getId());
        catalog = "cnx_mission_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE DATABASE `" + identifier(catalog) + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        for (String table : TENANT_TABLES) {
            copyWorkspaceTable(table);
        }
        jdbc.execute("CREATE TABLE `" + identifier(catalog) + "`.workspace LIKE `" + identifier(defaultCatalog()) + "`.workspace");
        jdbc.update("INSERT INTO `" + identifier(catalog) + "`.workspace SELECT * FROM `" + identifier(defaultCatalog())
                + "`.workspace WHERE id IN (?,?)", workspace.getId(), foreignWorkspace.getId());
        jdbc.update("UPDATE `" + identifier(catalog) + "`.company SET name='Foreign colliding company' WHERE workspace_id=? AND id=?",
                foreignWorkspace.getId(), primary.getId());
        jdbc.update("UPDATE `" + identifier(catalog) + "`.ai_agent_run SET checkpoint_json=JSON_SET(checkpoint_json,'$.workspaceId',?) WHERE workspace_id=? AND id=?",
                foreignWorkspace.getId(), foreignWorkspace.getId(), original.runId());
        jdbc.update("UPDATE `" + identifier(catalog) + "`.ai_chat_message SET content='Foreign colliding report' WHERE workspace_id=? AND id=?",
                foreignWorkspace.getId(), runs.getRun(workspace.getId(), original.runId()).getReportMessageId());
        jdbc.update("INSERT INTO org_placement (org_id,placement_mode,database_handle,storage_encryption_mode,key_controller,revocation_supported) VALUES (?,'dedicated_database',?,'provider_managed','connex_cloud_provider',FALSE)",
                foreignOrganization.getId(), catalog);
        jdbc.update("UPDATE app_user SET password_hash=? WHERE id=?", passwords.encode(PASSWORD), currentUser.getId());
        String decisionBody = json.writeValueAsString(request(original, "discard", original.selected().getFirst()));
        clearAuthentication();
        var login = http.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(java.util.Map.of("username", currentUser.getUsername(), "password", PASSWORD))))
                .andExpect(status().isOk()).andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        assertNotNull(session);
        String outputPath = "/api/ai/assistant/missions/" + original.runId() + "/output";

        String foreignOutput = http.perform(get(outputPath).session(session).header("X-Workspace-Id", foreignWorkspace.getId()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals("Foreign colliding report", json.readTree(foreignOutput).path("report").path("markdown").asString());
        assertFalse(foreignOutput.contains(primary.getName()));
        http.perform(post("/api/ai/assistant/missions/" + original.runId() + "/proposal-decisions")
                .session(session).with(SecurityMockMvcRequestPostProcessors.csrf().asHeader())
                .header("X-Workspace-Id", foreignWorkspace.getId()).contentType(MediaType.APPLICATION_JSON)
                .content(decisionBody))
                .andExpect(status().isOk());

        assertEquals("rejected", jdbc.queryForObject("SELECT status FROM `" + identifier(catalog)
                + "`.ai_chat_tool_call WHERE workspace_id=? AND id=?", String.class, foreignWorkspace.getId(), originalCall.getId()));
        authenticateAs(currentUser, workspace.getId());
        assertEquals("proposed", chatMapper.getMissionToolCallForMaintenance(workspace.getId(), original.runId(), original.selected().getFirst().getId()).getStatus());
        assertEquals(primary.getOwnerId(), companyMapper.getCompanyById(workspace.getId(), orgWorkspaceIdsJson(workspaceMapper, workspace.getId()), primary.getId()).getOwnerId());
        String defaultOutput = http.perform(get(outputPath).session(session).header("X-Workspace-Id", workspace.getId()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertFalse(defaultOutput.contains("Foreign colliding"));
    }

    private void grantAiUse(int workspaceId) {
        jdbc.update("INSERT INTO workspace_role (workspace_id,name) VALUES (?,'Mission routed reviewer')", workspaceId);
        Integer role = jdbc.queryForObject("SELECT id FROM workspace_role WHERE workspace_id=? AND name='Mission routed reviewer'", Integer.class, workspaceId);
        assertNotNull(role);
        jdbc.update("INSERT INTO workspace_role_permission (workspace_role_id,permission) VALUES (?,'AI_USE')", role);
        jdbc.update("UPDATE workspace_member SET role='member',role_id=? WHERE workspace_id=? AND user_id=?", role, workspaceId, currentUser.getId());
    }

    private void copyWorkspaceTable(String table) {
        String source = identifier(defaultCatalog());
        String target = identifier(catalog);
        String safeTable = identifier(table);
        jdbc.execute("CREATE TABLE `" + target + "`.`" + safeTable + "` LIKE `" + source + "`.`" + safeTable + "`");
        List<String> columns = jdbc.queryForList("SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=? AND TABLE_NAME=? ORDER BY ORDINAL_POSITION",
                String.class, defaultCatalog(), table);
        String projection = columns.stream().map(column -> "workspace_id".equals(column)
                ? Integer.toString(foreignWorkspace.getId()) : "`" + identifier(column) + "`")
                .collect(java.util.stream.Collectors.joining(","));
        jdbc.update("INSERT INTO `" + target + "`.`" + safeTable + "` SELECT " + projection + " FROM `" + source
                + "`.`" + safeTable + "` WHERE workspace_id=?", workspace.getId());
    }

    private static String defaultCatalog() {
        String database = TenantRoutingConfig.databaseFromJdbcUrl(System.getenv("CONNEX_DB_URL"));
        if (database != null) {
            return database;
        }
        String configured = System.getenv("CONNEX_DB_NAME");
        return configured == null ? "connexdb" : configured;
    }

    private static String identifier(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_]{1,64}")) {
            throw new IllegalArgumentException("Invalid fixture SQL identifier");
        }
        return value;
    }
}
