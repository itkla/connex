package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.Filter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.request.RequestContextHolder;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.OrgPlacement;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.beans.WorkspaceRole;
import ooo.klae.connex.backend.config.TenantRoutingConfig;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.OrgPlacementMapper;
import ooo.klae.connex.backend.mappers.RoleMapper;
import ooo.klae.connex.backend.mappers.TeamMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.services.SessionSecurityService;
import ooo.klae.connex.backend.tenant.TablePlaneRegistry;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.TenantRoutingProperties;
import ooo.klae.connex.backend.tenant.TenantWorkScope;
import tools.jackson.databind.ObjectMapper;

/** Full security-chain proof for team RBAC, seat validation, and workspace isolation. */
@SpringBootTest
@Import(TeamIsolationIntegrationTest.ScopedTeamMapperProbeController.class)
@UnenrolledPrivilegedFixture
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TeamIsolationIntegrationTest {
    private static final String PASSWORD = "Team-Test-Pw1!";

    @Autowired private WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") private Filter springSecurityFilterChain;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private OrgPlacementMapper orgPlacementMapper;
    @Autowired private WorkspaceMapper workspaceMapper;
    @Autowired private RoleMapper roleMapper;
    @Autowired private UserMapper userMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TenantContext tenantContext;
    @Autowired private TenantWorkScope tenantWorkScope;
    @Autowired private SessionSecurityService sessionSecurityService;

    private final List<Integer> workspaceIds = new ArrayList<>();
    private final List<Integer> organizationIds = new ArrayList<>();
    private final List<Integer> userIds = new ArrayList<>();
    private final List<String> scratchCatalogs = new ArrayList<>();

    private MockMvc mockMvc;

    @DynamicPropertySource
    static void routingProperties(DynamicPropertyRegistry registry) {
        registry.add(
            "connex.tenancy.routing.mode",
            () -> TenantRoutingProperties.MODE_CATALOG_PER_PLACEMENT);
        registry.add(
            "connex.tenancy.routing.default-catalog",
            TeamIsolationIntegrationTest::defaultCatalog);
    }

    @BeforeEach
    void setUp() {
        tenantContext.clear();
        RequestContextHolder.resetRequestAttributes();
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .addFilters(springSecurityFilterChain)
            .build();
    }

    @AfterEach
    void cleanUp() {
        tenantContext.clear();
        RequestContextHolder.resetRequestAttributes();
        tenantWorkScope.withCatalog(null, () -> {
            for (int workspaceId : workspaceIds) {
                jdbcTemplate.update("DELETE FROM team_member WHERE workspace_id = ?", workspaceId);
                jdbcTemplate.update("DELETE FROM team WHERE workspace_id = ?", workspaceId);
                jdbcTemplate.update("DELETE FROM workspace WHERE id = ?", workspaceId);
            }
            for (int organizationId : organizationIds) {
                jdbcTemplate.update("DELETE FROM org_placement WHERE org_id = ?", organizationId);
                jdbcTemplate.update("DELETE FROM organization WHERE id = ?", organizationId);
            }
            for (int userId : userIds) {
                jdbcTemplate.update(
                    "DELETE FROM notification_recipient_state WHERE recipient_id = ?", userId);
                jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", userId);
            }
            return null;
        });
        for (String scratchCatalog : scratchCatalogs) {
            jdbcTemplate.execute("DROP DATABASE IF EXISTS `" + identifier(scratchCatalog) + "`");
        }
    }

    @Test
    void activeMemberCanReadWhileOnlyTeamManagerCanMutate() throws Exception {
        RequestContextHolder.resetRequestAttributes();
        Workspace workspace = newWorkspace(newOrganization());
        User manager = newMember(workspace);
        grantTeamManage(workspace, manager);
        User reader = newMember(workspace);
        MockHttpSession managerSession = login(manager);
        MockHttpSession readerSession = login(reader);

        int teamId = createTeam(managerSession, workspace, "Revenue", manager.getId());

        mockMvc.perform(get("/api/teams/{id}", teamId)
                .header("X-Workspace-Id", workspace.getId())
                .session(readerSession))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("Revenue"))
            .andExpect(jsonPath("$.managerUserId").value(manager.getId()))
            .andExpect(jsonPath("$.members[0].name").value(manager.getDisplayName()))
            .andExpect(jsonPath("$.members[0].role").value("manager"));
        mockMvc.perform(post("/api/teams")
                .header("X-Workspace-Id", workspace.getId())
                .session(readerSession)
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(teamBody("Forbidden", null)))
            .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/teams/{id}", teamId)
                .header("X-Workspace-Id", workspace.getId())
                .session(readerSession)
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(teamBody("Forbidden", null)))
            .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/teams/{id}/archive", teamId)
                .header("X-Workspace-Id", workspace.getId())
                .session(readerSession)
                .with(csrf().asHeader()))
            .andExpect(status().isForbidden());
        addSeat(readerSession, workspace, teamId, reader.getId(), "member", 403);
        mockMvc.perform(delete("/api/teams/{id}/members/{userId}", teamId, manager.getId())
                .header("X-Workspace-Id", workspace.getId())
                .session(readerSession)
                .with(csrf().asHeader()))
            .andExpect(status().isForbidden());

        addSeat(managerSession, workspace, teamId, reader.getId(), "member", 200);
        mockMvc.perform(put("/api/teams/{id}", teamId)
                .header("X-Workspace-Id", workspace.getId())
                .session(managerSession)
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(teamBody("Revenue Operations", manager.getId())))
            .andExpect(status().isOk());
        mockMvc.perform(delete("/api/teams/{id}/members/{userId}", teamId, reader.getId())
                .header("X-Workspace-Id", workspace.getId())
                .session(managerSession)
                .with(csrf().asHeader()))
            .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/teams/{id}/archive", teamId)
                .header("X-Workspace-Id", workspace.getId())
                .session(managerSession)
                .with(csrf().asHeader()))
            .andExpect(status().isOk());
        mockMvc.perform(post("/api/teams/{id}/archive", teamId)
                .header("X-Workspace-Id", workspace.getId())
                .session(managerSession)
                .with(csrf().asHeader()))
            .andExpect(status().isOk());

        List<MutationAudit> audits = jdbcTemplate.query(
            "SELECT action, actor_id, target_label, outcome, changes FROM audit_log"
                + " WHERE workspace_id = ? AND action LIKE 'team.%' ORDER BY id",
            (resultSet, rowNum) -> new MutationAudit(
                resultSet.getString("action"),
                resultSet.getInt("actor_id"),
                resultSet.getString("target_label"),
                resultSet.getString("outcome"),
                resultSet.getString("changes")),
            workspace.getId());
        assertEquals(6, audits.size());
        for (MutationAudit audit : audits) {
            assertEquals(manager.getId(), audit.actorId());
            assertNotNull(audit.targetLabel());
            assertEquals("success", audit.outcome());
        }
        assertEquals(
            List.of(
                "team.archive",
                "team.archive",
                "team.create",
                "team.member.add",
                "team.member.remove",
                "team.update"),
            audits.stream().map(MutationAudit::action).sorted().toList());
        assertTrue(objectMapper.readTree(audits.getLast().changes()).get("idempotent").asBoolean());
    }

    @Test
    void otherWorkspaceCannotReadOrMutateATeam() throws Exception {
        RequestContextHolder.resetRequestAttributes();
        Workspace first = newWorkspace(newOrganization());
        Workspace second = newWorkspace(newOrganization());
        User firstManager = newMember(first);
        grantTeamManage(first, firstManager);
        User secondManager = newMember(second);
        grantTeamManage(second, secondManager);
        int teamId = createTeam(login(firstManager), first, "First workspace", null);
        MockHttpSession secondSession = login(secondManager);

        mockMvc.perform(get("/api/teams/{id}", teamId)
                .header("X-Workspace-Id", second.getId())
                .session(secondSession))
            .andExpect(status().isNotFound());
        mockMvc.perform(put("/api/teams/{id}", teamId)
                .header("X-Workspace-Id", second.getId())
                .session(secondSession)
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(teamBody("Renamed", null)))
            .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/teams/{id}", teamId)
                .header("X-Workspace-Id", first.getId())
                .session(secondSession))
            .andExpect(status().isForbidden());
    }

    @Test
    void seatsRequireActiveMembershipWhileLiveNamesConflictAndArchivePreservesSeats()
            throws Exception {
        RequestContextHolder.resetRequestAttributes();
        Workspace workspace = newWorkspace(newOrganization());
        Workspace foreignWorkspace = newWorkspace(newOrganization());
        User manager = newMember(workspace);
        grantTeamManage(workspace, manager);
        User pending = newUser();
        workspaceMapper.addPendingMember(workspace.getId(), pending.getId(), "member");
        User foreign = newMember(foreignWorkspace);
        User active = newMember(workspace);
        User removed = newMember(workspace);
        workspaceMapper.removeMember(workspace.getId(), removed.getId());
        MockHttpSession session = login(manager);
        int teamId = createTeam(session, workspace, "Reusable", null);

        mockMvc.perform(post("/api/teams")
                .header("X-Workspace-Id", workspace.getId())
                .session(session)
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(teamBody("Reusable", null)))
            .andExpect(status().isConflict());
        addSeat(session, workspace, teamId, active.getId(), "member", 200);
        addSeat(session, workspace, teamId, pending.getId(), "member", 400);
        addSeat(session, workspace, teamId, foreign.getId(), "member", 400);
        addSeat(session, workspace, teamId, removed.getId(), "member", 400);
        mockMvc.perform(post("/api/teams/{id}/archive", teamId)
                .header("X-Workspace-Id", workspace.getId())
                .session(session)
                .with(csrf().asHeader()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.archivedAt").isNotEmpty());
        assertEquals(1, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM team_member WHERE workspace_id = ? AND team_id = ?",
            Integer.class,
            workspace.getId(),
            teamId));
        createTeam(session, workspace, "Reusable", null);

        mockMvc.perform(get("/api/teams")
                .header("X-Workspace-Id", workspace.getId())
                .session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1));
        mockMvc.perform(get("/api/teams")
                .param("includeArchived", "true")
                .header("X-Workspace-Id", workspace.getId())
                .session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void migrationRejectsInvalidTeamMemberRole() throws Exception {
        Workspace workspace = newWorkspace(newOrganization());
        User manager = newMember(workspace);
        grantTeamManage(workspace, manager);
        User member = newMember(workspace);
        int teamId = createTeam(login(manager), workspace, "Role constraint", null);

        DataAccessException failure = assertThrows(DataAccessException.class, () -> jdbcTemplate.update(
            "INSERT INTO team_member (workspace_id, team_id, user_id, role) VALUES (?, ?, ?, 'lead')",
            workspace.getId(), teamId, member.getId()));
        assertTrue(failure.getMostSpecificCause().getMessage().contains("chk_team_member_role"));
    }

    @Test
    void scopedTeamMapperWithoutTenantContextSurfacesOpaqueServerError() throws Exception {
        User user = newUser();

        mockMvc.perform(get("/api/test/team-tenant-scope-refusal").session(login(user)))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.message").value("An unexpected error occurred"))
            .andExpect(jsonPath("$.correlationId").isString());
    }

    @Test
    void pathWorkspaceRemovalAndAccountErasureCleanEveryRoutedCatalog() throws Exception {
        Organization defaultOrganization = newOrganization();
        Workspace defaultWorkspace = newWorkspace(defaultOrganization);
        Organization dedicatedOrganization = newOrganization();
        Workspace dedicatedWorkspace = newWorkspace(dedicatedOrganization);
        String dedicatedCatalog = "cnx_team_it_" + compactUuid();
        createDedicatedCatalog(dedicatedOrganization, dedicatedCatalog);

        User actor = newMember(defaultWorkspace);
        workspaceMapper.addMember(dedicatedWorkspace.getId(), actor.getId(), "member");
        grantMemberManage(dedicatedWorkspace, actor);
        User removed = newMember(dedicatedWorkspace);
        int removedTeamId = insertTeam(
            dedicatedCatalog, dedicatedWorkspace.getId(), removed.getId(), "Removed member");
        MockHttpSession actorSession = login(actor);
        MockHttpServletRequest stepUpRequest =
            new MockHttpServletRequest(context.getServletContext());
        stepUpRequest.setSession(actorSession);
        sessionSecurityService.markStepUp(stepUpRequest, actor.getId());

        mockMvc.perform(delete(
                "/api/workspaces/{id}/members/{userId}",
                dedicatedWorkspace.getId(),
                removed.getId())
                .header("X-Workspace-Id", defaultWorkspace.getId())
                .session(actorSession)
                .with(csrf().asHeader()))
            .andExpect(status().isNoContent());

        assertEquals(0, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM `" + identifier(dedicatedCatalog)
                + "`.team_member WHERE workspace_id = ? AND team_id = ? AND user_id = ?",
            Integer.class,
            dedicatedWorkspace.getId(), removedTeamId, removed.getId()));
        assertEquals(0, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM `" + identifier(dedicatedCatalog)
                + "`.team WHERE workspace_id = ? AND id = ? AND manager_user_id IS NOT NULL",
            Integer.class,
            dedicatedWorkspace.getId(), removedTeamId));
        MembershipAudit removalAudit = jdbcTemplate.queryForObject(
            "SELECT workspace_id, org_id, actor_id FROM audit_log"
                + " WHERE action = 'workspace.member.remove' AND entity_id = ? AND actor_id = ?"
                + " ORDER BY id DESC LIMIT 1",
            (resultSet, rowNum) -> new MembershipAudit(
                resultSet.getInt("workspace_id"),
                resultSet.getInt("org_id"),
                resultSet.getInt("actor_id")),
            dedicatedWorkspace.getId(),
            actor.getId());
        assertNotNull(removalAudit);
        assertEquals(dedicatedWorkspace.getId(), removalAudit.workspaceId());
        assertEquals(dedicatedOrganization.getId(), removalAudit.orgId());
        assertEquals(actor.getId(), removalAudit.actorId());
        assertEquals(0, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM audit_log"
                + " WHERE action = 'workspace.member.remove' AND entity_id = ?"
                + " AND actor_id = ? AND workspace_id = ?",
            Integer.class,
            dedicatedWorkspace.getId(),
            actor.getId(),
            defaultWorkspace.getId()));

        User erased = newMember(defaultWorkspace);
        workspaceMapper.addMember(dedicatedWorkspace.getId(), erased.getId(), "member");
        int defaultTeamId = insertTeam(
            defaultCatalog(), defaultWorkspace.getId(), erased.getId(), "Default erasure");
        int dedicatedTeamId = insertTeam(
            dedicatedCatalog, dedicatedWorkspace.getId(), erased.getId(), "Dedicated erasure");

        mockMvc.perform(delete("/api/users/{id}", erased.getId())
                .header("X-Workspace-Id", defaultWorkspace.getId())
                .session(login(erased))
                .with(csrf().asHeader()))
            .andExpect(status().isOk());

        assertEquals(0, teamReferenceCount(
            defaultCatalog(), defaultWorkspace.getId(), defaultTeamId, erased.getId()));
        assertEquals(0, teamReferenceCount(
            dedicatedCatalog, dedicatedWorkspace.getId(), dedicatedTeamId, erased.getId()));
    }

    @Test
    void unauthorizedMemberRemovalDoesNotRevealPathWorkspaceExistence() throws Exception {
        Workspace activeWorkspace = newWorkspace(newOrganization());
        User actor = newMember(activeWorkspace);
        Workspace foreignWorkspace = newWorkspace(newOrganization());
        User foreignMember = newMember(foreignWorkspace);
        int missingWorkspaceId = jdbcTemplate.queryForObject(
            "SELECT COALESCE(MAX(id), 0) + 100000 FROM workspace",
            Integer.class);
        MockHttpSession session = login(actor);

        MvcResult existing = mockMvc.perform(delete(
                "/api/workspaces/{id}/members/{userId}",
                foreignWorkspace.getId(),
                foreignMember.getId())
                .header("X-Workspace-Id", activeWorkspace.getId())
                .session(session)
                .with(csrf().asHeader()))
            .andExpect(status().isForbidden())
            .andReturn();
        MvcResult missing = mockMvc.perform(delete(
                "/api/workspaces/{id}/members/{userId}",
                missingWorkspaceId,
                foreignMember.getId())
                .header("X-Workspace-Id", activeWorkspace.getId())
                .session(session)
                .with(csrf().asHeader()))
            .andExpect(status().isForbidden())
            .andReturn();

        assertEquals(
            existing.getResponse().getContentAsString(),
            missing.getResponse().getContentAsString());
    }

    @Test
    void pendingDeclineCleansAndAuditsThePathWorkspaceCatalog() throws Exception {
        Workspace activeWorkspace = newWorkspace(newOrganization());
        Organization dedicatedOrganization = newOrganization();
        Workspace dedicatedWorkspace = newWorkspace(dedicatedOrganization);
        String dedicatedCatalog = "cnx_team_decline_" + compactUuid();
        createDedicatedCatalog(dedicatedOrganization, dedicatedCatalog);
        User invitee = newMember(activeWorkspace);
        workspaceMapper.addPendingMember(dedicatedWorkspace.getId(), invitee.getId(), "member");
        int teamId = insertTeam(
            dedicatedCatalog, dedicatedWorkspace.getId(), invitee.getId(), "Pending decline");
        insertNotification(dedicatedCatalog, dedicatedWorkspace.getId(), invitee.getId());

        mockMvc.perform(post("/api/workspaces/{id}/decline", dedicatedWorkspace.getId())
                .header("X-Workspace-Id", activeWorkspace.getId())
                .session(login(invitee))
                .with(csrf().asHeader()))
            .andExpect(status().isNoContent());

        assertNull(workspaceMapper.getMember(dedicatedWorkspace.getId(), invitee.getId()));
        assertEquals(0, teamReferenceCount(
            dedicatedCatalog, dedicatedWorkspace.getId(), teamId, invitee.getId()));
        assertEquals(0, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM `" + identifier(dedicatedCatalog)
                + "`.notification WHERE workspace_id = ? AND recipient_id = ?",
            Integer.class,
            dedicatedWorkspace.getId(),
            invitee.getId()));
        MembershipAudit declineAudit = jdbcTemplate.queryForObject(
            "SELECT workspace_id, org_id, actor_id FROM audit_log"
                + " WHERE action = 'workspace.member.decline' AND entity_id = ? AND actor_id = ?"
                + " ORDER BY id DESC LIMIT 1",
            (resultSet, rowNum) -> new MembershipAudit(
                resultSet.getInt("workspace_id"),
                resultSet.getInt("org_id"),
                resultSet.getInt("actor_id")),
            dedicatedWorkspace.getId(),
            invitee.getId());
        assertNotNull(declineAudit);
        assertEquals(dedicatedWorkspace.getId(), declineAudit.workspaceId());
        assertEquals(dedicatedOrganization.getId(), declineAudit.orgId());
        assertEquals(invitee.getId(), declineAudit.actorId());
    }

    private void addSeat(
            MockHttpSession session,
            Workspace workspace,
            int teamId,
            int userId,
            String role,
            int expectedStatus) throws Exception {
        mockMvc.perform(post("/api/teams/{id}/members", teamId)
                .header("X-Workspace-Id", workspace.getId())
                .session(session)
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":" + userId + ",\"role\":\"" + role + "\"}"))
            .andExpect(status().is(expectedStatus));
    }

    private int createTeam(
            MockHttpSession session, Workspace workspace, String name, Integer managerUserId)
            throws Exception {
        MvcResult result = mockMvc.perform(post("/api/teams")
                .header("X-Workspace-Id", workspace.getId())
                .session(session)
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(teamBody(name, managerUserId)))
            .andExpect(status().isCreated())
            .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asInt();
    }

    private static String teamBody(String name, Integer managerUserId) {
        String manager = managerUserId == null ? "null" : managerUserId.toString();
        return "{\"name\":\"" + name + "\",\"managerUserId\":" + manager + "}";
    }

    private void grantTeamManage(Workspace workspace, User user) {
        grantPermission(workspace, user, "Team manager ", "TEAM_MANAGE");
    }

    private void grantMemberManage(Workspace workspace, User user) {
        grantPermission(workspace, user, "Member manager ", "MEMBER_MANAGE");
    }

    private void grantPermission(
            Workspace workspace,
            User user,
            String roleNamePrefix,
            String permission) {
        tenantWorkScope.inWorkspace(workspace.getId(), () -> {
            WorkspaceRole role = new WorkspaceRole();
            role.setWorkspaceId(workspace.getId());
            role.setName(roleNamePrefix + compactUuid().substring(0, 8));
            roleMapper.insertRole(role);
            roleMapper.insertPermissions(workspace.getId(), role.getId(), List.of(permission));
            workspaceMapper.setMemberCustomRole(workspace.getId(), user.getId(), role.getId());
            return null;
        });
    }

    private Organization newOrganization() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Organization organization = new Organization();
        organization.setName("Team Org " + suffix);
        organization.setSlug("team-org-" + suffix);
        organizationMapper.insert(organization);
        organizationIds.add(organization.getId());
        return organization;
    }

    private Workspace newWorkspace(Organization organization) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Workspace workspace = new Workspace();
        workspace.setName("Team Workspace " + suffix);
        workspace.setSlug("team-ws-" + suffix);
        workspace.setOrgId(organization.getId());
        workspaceMapper.insert(workspace);
        workspaceIds.add(workspace.getId());
        return workspace;
    }

    private User newMember(Workspace workspace) {
        User user = newUser();
        workspaceMapper.addMember(workspace.getId(), user.getId(), "member");
        return user;
    }

    private User newUser() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User user = new User();
        user.setUsername("team_" + suffix);
        user.setDisplayName("Team User " + suffix);
        user.setEmail(suffix + "@example.com");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setTimezone("UTC");
        userMapper.insert(user);
        userIds.add(user.getId());
        return user;
    }

    private void createDedicatedCatalog(Organization organization, String catalog) {
        String scratch = identifier(catalog);
        String source = identifier(defaultCatalog());
        scratchCatalogs.add(scratch);
        jdbcTemplate.execute("CREATE DATABASE `" + scratch
            + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        for (String table : TablePlaneRegistry.ORG_DATA_TABLES.stream().sorted().toList()) {
            jdbcTemplate.execute(
                "CREATE TABLE `" + scratch + "`.`" + identifier(table)
                    + "` LIKE `" + source + "`.`" + identifier(table) + "`");
        }
        OrgPlacement placement = OrgPlacement.sharedDefault(organization.getId());
        placement.setPlacementMode("dedicated_database");
        placement.setDatabaseHandle(scratch);
        orgPlacementMapper.insert(placement);
    }

    private int insertTeam(String catalog, int workspaceId, int userId, String name) {
        String qualified = "`" + identifier(catalog) + "`.";
        jdbcTemplate.update(
            "INSERT INTO " + qualified
                + "team (workspace_id, name, manager_user_id) VALUES (?, ?, ?)",
            workspaceId, name, userId);
        int teamId = jdbcTemplate.queryForObject(
            "SELECT id FROM " + qualified + "team WHERE workspace_id = ? AND name = ?",
            Integer.class,
            workspaceId,
            name);
        jdbcTemplate.update(
            "INSERT INTO " + qualified
                + "team_member (workspace_id, team_id, user_id, role) VALUES (?, ?, ?, 'manager')",
            workspaceId, teamId, userId);
        return teamId;
    }

    private void insertNotification(String catalog, int workspaceId, int recipientId) {
        jdbcTemplate.update(
            "INSERT INTO `" + identifier(catalog) + "`.notification"
                + " (workspace_id, recipient_id, type, category, severity, template_version,"
                + " title, dedupe_key, triggered_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP())",
            workspaceId,
            recipientId,
            "workspace.join",
            "workspace",
            "info",
            1,
            "Workspace invitation",
            "workspace.join:" + workspaceId);
    }

    private int teamReferenceCount(String catalog, int workspaceId, int teamId, int userId) {
        String qualified = "`" + identifier(catalog) + "`.";
        return jdbcTemplate.queryForObject(
            "SELECT (SELECT COUNT(*) FROM " + qualified
                + "team_member WHERE workspace_id = ? AND team_id = ? AND user_id = ?)"
                + " + (SELECT COUNT(*) FROM " + qualified
                + "team WHERE workspace_id = ? AND id = ? AND manager_user_id = ?)",
            Integer.class,
            workspaceId, teamId, userId, workspaceId, teamId, userId);
    }

    private static String defaultCatalog() {
        String catalog = TenantRoutingConfig.databaseFromJdbcUrl(System.getenv("CONNEX_DB_URL"));
        if (catalog != null) {
            return catalog;
        }
        String configured = System.getenv("CONNEX_DB_NAME");
        return configured != null ? configured : "connexdb";
    }

    private static String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String identifier(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_]{1,64}")) {
            throw new IllegalArgumentException("Invalid test catalog identifier");
        }
        return value;
    }

    private MockHttpSession login(User user) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + user.getUsername()
                    + "\",\"password\":\"" + PASSWORD + "\"}"))
            .andExpect(status().isOk())
            .andReturn();
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertNotNull(session);
        return session;
    }

    private record MutationAudit(
        String action,
        int actorId,
        String targetLabel,
        String outcome,
        String changes) {
    }

    private record MembershipAudit(int workspaceId, int orgId, int actorId) {
    }

    /** Executes a scoped team mapper without installing a tenant context. */
    @RestController
    @RequestMapping("/api/test/team-tenant-scope-refusal")
    @RequiredArgsConstructor
    static class ScopedTeamMapperProbeController {
        private final TeamMapper teamMapper;

        @GetMapping
        int probe() {
            return teamMapper.getAll(1, false).size();
        }
    }
}
