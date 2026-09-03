package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.Filter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.request.RequestContextHolder;

import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.beans.WorkspaceRole;
import ooo.klae.connex.backend.dto.sequence.SequenceRequest;
import ooo.klae.connex.backend.dto.sequence.SequenceStepRequest;
import ooo.klae.connex.backend.dto.sequence.SequenceStepType;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.RoleMapper;
import ooo.klae.connex.backend.mappers.SequenceMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import tools.jackson.databind.ObjectMapper;

/** Full-stack sequence route, tenant-isolation, validation, and RBAC proofs. */
@SpringBootTest(properties = {
    "connex.sequences.enabled=true",
    "spring.task.scheduling.enabled=false"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@UnenrolledPrivilegedFixture
class SequenceTenantIsolationIntegrationTest {
    private static final String PASSWORD = "Sequence-Http-Pw1!";

    @Autowired private WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") private Filter springSecurityFilterChain;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private PersonMapper personMapper;
    @Autowired private WorkspaceMapper workspaceMapper;
    @Autowired private UserMapper userMapper;
    @Autowired private RoleMapper roleMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @MockitoSpyBean private SequenceMapper sequenceMapper;

    private final List<Integer> workspaceIds = new ArrayList<>();
    private final List<Integer> userIds = new ArrayList<>();
    private MockMvc mockMvc;
    private Organization organization;
    private Workspace workspace;
    private Workspace foreignWorkspace;
    private User manager;
    private User viewer;
    private User nonViewer;
    private User foreignManager;

    @BeforeEach
    void setUp() {
        RequestContextHolder.resetRequestAttributes();
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .addFilters(springSecurityFilterChain)
            .build();
        String suffix = unique();
        organization = new Organization();
        organization.setName("Sequence HTTP " + suffix);
        organization.setSlug("sequence-http-" + suffix);
        organizationMapper.insert(organization);
        workspace = newWorkspace("Primary sequence", organization.getId());
        foreignWorkspace = newWorkspace("Foreign sequence", organization.getId());
        manager = newMember(workspace, "sequence_http_manager_" + suffix,
            List.of("SEQUENCE_VIEW", "SEQUENCE_MANAGE"));
        viewer = newMember(workspace, "sequence_http_viewer_" + suffix,
            List.of("SEQUENCE_VIEW"));
        nonViewer = newMember(workspace, "sequence_http_non_viewer_" + suffix,
            List.of("REPORT_READ"));
        foreignManager = newMember(foreignWorkspace, "sequence_http_foreign_" + suffix,
            List.of("SEQUENCE_VIEW", "SEQUENCE_MANAGE"));
    }

    @AfterEach
    void cleanUp() {
        RequestContextHolder.resetRequestAttributes();
        for (int workspaceId : workspaceIds) {
            jdbcTemplate.update("DELETE FROM sequence WHERE workspace_id = ?", workspaceId);
            jdbcTemplate.update("DELETE FROM person WHERE workspace_id = ?", workspaceId);
            jdbcTemplate.update("DELETE FROM workspace_member WHERE workspace_id = ?", workspaceId);
            jdbcTemplate.update(
                "DELETE wrp FROM workspace_role_permission wrp"
                    + " JOIN workspace_role wr ON wr.id = wrp.workspace_role_id"
                    + " WHERE wr.workspace_id = ?",
                workspaceId);
            jdbcTemplate.update("DELETE FROM workspace_role WHERE workspace_id = ?", workspaceId);
            jdbcTemplate.update("DELETE FROM workspace WHERE id = ?", workspaceId);
        }
        for (int userId : userIds) {
            jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", userId);
        }
        if (organization != null) {
            jdbcTemplate.update("DELETE FROM organization WHERE id = ?", organization.getId());
        }
        workspaceIds.clear();
        userIds.clear();
    }

    @Test
    void viewOnlyMemberGetsEveryReadAndNoMutator() throws Exception {
        MockHttpSession managerSession = login(manager);
        MockHttpSession viewerSession = login(viewer);
        int sequenceId = create(managerSession, workspace, "Shared route", "shared");
        publish(managerSession, workspace, sequenceId);

        mockMvc.perform(get("/api/sequences")
                .header("X-Workspace-Id", workspace.getId()).session(viewerSession))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.id == " + sequenceId + ")]").isNotEmpty());
        mockMvc.perform(get("/api/sequences/{id}", sequenceId)
                .header("X-Workspace-Id", workspace.getId()).session(viewerSession))
            .andExpect(status().isOk());
        mockMvc.perform(get("/api/sequences/{id}/versions", sequenceId)
                .header("X-Workspace-Id", workspace.getId()).session(viewerSession))
            .andExpect(status().isOk());
        mockMvc.perform(get("/api/sequences/{id}/versions/{version}", sequenceId, 1)
                .header("X-Workspace-Id", workspace.getId()).session(viewerSession))
            .andExpect(status().isOk());
        mockMvc.perform(get("/api/sequences/merge-fields")
                .header("X-Workspace-Id", workspace.getId()).session(viewerSession))
            .andExpect(status().isOk());

        mockMvc.perform(post("/api/sequences").with(csrf().asHeader())
                .header("X-Workspace-Id", workspace.getId()).session(viewerSession)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(request("Forbidden create", "shared"))))
            .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/sequences/{id}", sequenceId).with(csrf().asHeader())
                .header("X-Workspace-Id", workspace.getId()).session(viewerSession)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(request("Forbidden update", "shared"))))
            .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/sequences/{id}", sequenceId).with(csrf().asHeader())
                .header("X-Workspace-Id", workspace.getId()).session(viewerSession))
            .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/sequences/{id}/versions", sequenceId).with(csrf().asHeader())
                .header("X-Workspace-Id", workspace.getId()).session(viewerSession))
            .andExpect(status().isForbidden());
    }

    @Test
    void previewHttpBoundaryEnforcesViewPermissionOwnerScopeAndDsrRestrictions() throws Exception {
        MockHttpSession managerSession = login(manager);
        MockHttpSession viewerSession = login(viewer);
        MockHttpSession nonViewerSession = login(nonViewer);
        int sequenceId = create(managerSession, workspace, "Shared preview route", "shared");
        publish(managerSession, workspace, sequenceId);
        Person ownContact = person(viewer.getId(), "Viewer contact");
        Person teammateContact = person(manager.getId(), "Manager contact");
        Person restrictedContact = person(viewer.getId(), "Restricted contact");
        Person foreignContact = person(
            foreignWorkspace, foreignManager.getId(), "Foreign contact");
        assertEquals(1, personMapper.updateProcessingRestrictions(
            workspace.getId(), restrictedContact.getId(), false, true));

        mockMvc.perform(post("/api/sequences/{id}/versions/{version}/preview", sequenceId, 1)
                .with(csrf().asHeader())
                .header("X-Workspace-Id", workspace.getId()).session(viewerSession)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"personId\":" + ownContact.getId() + "}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.version").value(1));
        Map<String, Integer> before = sideEffectCounts();
        mockMvc.perform(post("/api/sequences/{id}/versions/{version}/preview", sequenceId, 1)
                .with(csrf().asHeader())
                .header("X-Workspace-Id", workspace.getId()).session(viewerSession)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"personId\":" + foreignContact.getId() + "}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("SEQUENCE_NOT_FOUND"))
            .andExpect(jsonPath("$.steps").doesNotExist())
            .andExpect(jsonPath("$.unresolvedMergeFields").doesNotExist());
        assertEquals(before, sideEffectCounts());
        mockMvc.perform(post("/api/sequences/{id}/versions/{version}/preview", sequenceId, 1)
                .with(csrf().asHeader())
                .header("X-Workspace-Id", workspace.getId()).session(viewerSession)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"personId\":" + teammateContact.getId() + "}"))
            .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/sequences/{id}/versions/{version}/preview", sequenceId, 1)
                .with(csrf().asHeader())
                .header("X-Workspace-Id", workspace.getId()).session(viewerSession)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"personId\":" + restrictedContact.getId() + "}"))
            .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/sequences/{id}/versions/{version}/preview", sequenceId, 1)
                .with(csrf().asHeader())
                .header("X-Workspace-Id", workspace.getId()).session(nonViewerSession)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"personId\":" + ownContact.getId() + "}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void everyIdentifierRouteReturnsNotFoundForAnotherWorkspace() throws Exception {
        MockHttpSession localSession = login(manager);
        MockHttpSession foreignSession = login(foreignManager);
        int foreignId = create(foreignSession, foreignWorkspace, "Foreign route", "shared");
        publish(foreignSession, foreignWorkspace, foreignId);

        mockMvc.perform(get("/api/sequences")
                .header("X-Workspace-Id", workspace.getId()).session(localSession))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.id == " + foreignId + ")]").isEmpty());
        mockMvc.perform(get("/api/sequences/{id}", foreignId)
                .header("X-Workspace-Id", workspace.getId()).session(localSession))
            .andExpect(status().isNotFound());
        mockMvc.perform(put("/api/sequences/{id}", foreignId).with(csrf().asHeader())
                .header("X-Workspace-Id", workspace.getId()).session(localSession)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(request("Foreign update", "shared"))))
            .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/sequences/{id}", foreignId).with(csrf().asHeader())
                .header("X-Workspace-Id", workspace.getId()).session(localSession))
            .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/sequences/{id}/versions", foreignId).with(csrf().asHeader())
                .header("X-Workspace-Id", workspace.getId()).session(localSession))
            .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/sequences/{id}/versions", foreignId)
                .header("X-Workspace-Id", workspace.getId()).session(localSession))
            .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/sequences/{id}/versions/{version}", foreignId, 1)
                .header("X-Workspace-Id", workspace.getId()).session(localSession))
            .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/sequences/{id}/versions/{version}/preview", foreignId, 1)
                .with(csrf().asHeader())
                .header("X-Workspace-Id", workspace.getId()).session(localSession)
                .contentType(MediaType.APPLICATION_JSON).content("{\"personId\":1}"))
            .andExpect(status().isNotFound());
        mockMvc.perform(get("/API/SEQUENCES/{id}", foreignId)
                .header("X-Workspace-Id", workspace.getId()).session(localSession))
            .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/sequences")
                .header("X-Workspace-Id", foreignWorkspace.getId()).session(localSession))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/sequences/merge-fields")
                .header("X-Workspace-Id", foreignWorkspace.getId()).session(localSession))
            .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/sequences").with(csrf().asHeader())
                .header("X-Workspace-Id", foreignWorkspace.getId()).session(localSession)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(request("Foreign root create", "shared"))))
            .andExpect(status().isForbidden());
    }

    @Test
    void malformedCreateAndUpdateReturnBadRequestWithoutPartialWrites() throws Exception {
        MockHttpSession session = login(manager);
        int before = sequenceCount(workspace);

        mockMvc.perform(post("/api/sequences").with(csrf().asHeader())
                .header("X-Workspace-Id", workspace.getId()).session(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Incomplete\"}"))
            .andExpect(status().isBadRequest());
        assertEquals(before, sequenceCount(workspace));

        int sequenceId = create(session, workspace, "Unchanged", "personal");
        mockMvc.perform(put("/api/sequences/{id}", sequenceId).with(csrf().asHeader())
                .header("X-Workspace-Id", workspace.getId()).session(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Invalid replacement\",\"steps\":[]}"))
            .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/sequences/{id}", sequenceId)
                .header("X-Workspace-Id", workspace.getId()).session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("Unchanged"));
    }

    @Test
    void postRollsBackWhenTheSecondLocalizedInsertFails() throws Exception {
        MockHttpSession session = login(manager);
        doThrow(new RuntimeException("localized insert failed"))
            .when(sequenceMapper)
            .insertStepContent(argThat(content -> "ja".equals(content.getLocale())));

        mockMvc.perform(post("/api/sequences").with(csrf().asHeader())
                .header("X-Workspace-Id", workspace.getId()).session(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(request("Rollback HTTP create", "personal"))))
            .andExpect(status().isInternalServerError());

        assertEquals(0, sequenceCount(workspace));
        assertEquals(0, sequenceChildCount("sequence_step"));
        assertEquals(0, sequenceChildCount("sequence_step_content"));
    }

    @Test
    void putRollsBackWhenTheSecondLocalizedInsertFails() throws Exception {
        MockHttpSession session = login(manager);
        int sequenceId = create(session, workspace, "Retained HTTP draft", "personal");
        doThrow(new RuntimeException("localized insert failed"))
            .when(sequenceMapper)
            .insertStepContent(argThat(content -> "ja".equals(content.getLocale())));

        mockMvc.perform(put("/api/sequences/{id}", sequenceId).with(csrf().asHeader())
                .header("X-Workspace-Id", workspace.getId()).session(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(request("Failed HTTP replacement", "shared"))))
            .andExpect(status().isInternalServerError());

        mockMvc.perform(get("/api/sequences/{id}", sequenceId)
                .header("X-Workspace-Id", workspace.getId()).session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("Retained HTTP draft"))
            .andExpect(jsonPath("$.visibility").value("personal"));
        assertEquals(1, sequenceChildCount("sequence_step"));
        assertEquals(2, sequenceChildCount("sequence_step_content"));
    }

    private int create(MockHttpSession session, Workspace target, String name, String visibility)
            throws Exception {
        MvcResult result = mockMvc.perform(post("/api/sequences").with(csrf().asHeader())
                .header("X-Workspace-Id", target.getId()).session(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(request(name, visibility))))
            .andExpect(status().isCreated())
            .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsByteArray()).get("id").asInt();
    }

    private void publish(MockHttpSession session, Workspace target, int sequenceId)
            throws Exception {
        mockMvc.perform(post("/api/sequences/{id}/versions", sequenceId).with(csrf().asHeader())
                .header("X-Workspace-Id", target.getId()).session(session))
            .andExpect(status().isCreated());
    }

    private MockHttpSession login(User user) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + user.getUsername()
                    + "\",\"password\":\"" + PASSWORD + "\"}"))
            .andExpect(status().isOk())
            .andReturn();
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertNotNull(session, "login did not establish a sequence test session");
        return session;
    }

    private User newMember(Workspace target, String username, List<String> permissions) {
        User user = new User();
        user.setUsername(username);
        user.setDisplayName(username);
        user.setEmail(username + "@example.com");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setTimezone("UTC");
        userMapper.insert(user);
        userIds.add(user.getId());
        workspaceMapper.addMember(target.getId(), user.getId(), "member");
        WorkspaceRole role = new WorkspaceRole();
        role.setWorkspaceId(target.getId());
        role.setName("Sequence HTTP role " + unique());
        roleMapper.insertRole(role);
        roleMapper.insertPermissions(target.getId(), role.getId(), permissions);
        workspaceMapper.setMemberCustomRole(target.getId(), user.getId(), role.getId());
        return user;
    }

    private Person person(int ownerId, String name) {
        return person(workspace, ownerId, name);
    }

    private Person person(Workspace target, int ownerId, String name) {
        Person person = new Person();
        person.setWorkspaceId(target.getId());
        person.setOwnerId(ownerId);
        person.setName(name);
        person.setEmail(name.toLowerCase(Locale.ROOT).replace(' ', '-') + "@example.com");
        personMapper.insert(person);
        return person;
    }

    private Workspace newWorkspace(String name, int orgId) {
        String suffix = unique();
        Workspace created = new Workspace();
        created.setOrgId(orgId);
        created.setName(name + " " + suffix);
        created.setSlug(name.toLowerCase(Locale.ROOT).replace(' ', '-') + "-" + suffix);
        workspaceMapper.insert(created);
        workspaceIds.add(created.getId());
        return created;
    }

    private int sequenceCount(Workspace target) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM sequence WHERE workspace_id = ?",
            Integer.class, target.getId());
    }

    private int sequenceChildCount(String table) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM " + table + " WHERE workspace_id = ?",
            Integer.class, workspace.getId());
    }

    private Map<String, Integer> sideEffectCounts() {
        return Map.of(
            "activity", sequenceChildCount("activity"),
            "task", sequenceChildCount("task"),
            "notification", sequenceChildCount("notification"),
            "campaign_send", sequenceChildCount("campaign_send"),
            "campaign_delivery", sequenceChildCount("campaign_delivery"));
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private static SequenceRequest request(String name, String visibility) {
        return new SequenceRequest(
            name, null, visibility, "UTC", 31,
            LocalTime.of(9, 0), LocalTime.of(17, 0),
            List.of(new SequenceStepRequest(
                SequenceStepType.SEND_EMAIL, 0, "hours", "automatic",
                List.of(
                    new SequenceStepRequest.Content("en", "Hello", "English body", null),
                    new SequenceStepRequest.Content("ja", "こんにちは", "日本語本文", null)))));
    }

    private static String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
