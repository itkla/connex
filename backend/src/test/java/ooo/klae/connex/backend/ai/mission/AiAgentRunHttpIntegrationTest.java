package ooo.klae.connex.backend.ai.mission;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import jakarta.servlet.Filter;
import ooo.klae.connex.backend.ai.AiProperties;
import ooo.klae.connex.backend.ai.lease.AiRunLeaseGuard;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.config.ApiRequestBodySizeFilter;
import ooo.klae.connex.backend.config.RequestBodySizeProperties;
import ooo.klae.connex.backend.dto.AiChatSessionCreateRequest;
import ooo.klae.connex.backend.mappers.AiAgentRunMapper;
import ooo.klae.connex.backend.services.AiAssistantService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Production HTTP security, tenant routing, services, and MySQL; only provider readiness is synthetic. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class AiAgentRunHttpIntegrationTest extends AbstractAiAgentPrivacyIntegrationTest {
    private static final String BASE = "/api/ai/assistant/missions";
    private static final String PASSWORD = "MissionHttpFixture9!";
    @Autowired private WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") private Filter security;
    @Autowired private RequestBodySizeProperties limits;
    @Autowired private PasswordEncoder passwords;
    @Autowired private ObjectMapper json;
    @Autowired private AiAssistantService sessions;
    @Autowired private AiAgentRunPersistenceService persistence;
    @Autowired private AiAgentRunMapper runs;
    @Autowired private AiProperties properties;
    @MockitoBean private AiAgentCapabilityGate capability;
    private MockMvc mvc;
    private MockHttpSession http;
    private String csrfHeader;
    private String csrfToken;
    private int sessionId;
    private Company primary;
    private Workspace otherWorkspace;

    @BeforeEach
    void prepareHttp() throws Exception {
        when(capability.requireReady()).thenReturn(new byte[32]);
        workspaceMapper.updateMemberRole(workspace.getId(), currentUser.getId(), "owner");
        authenticateAs(currentUser, workspace.getId());
        approveForStepUp(currentUser);
        AiChatSessionCreateRequest request = new AiChatSessionCreateRequest();
        request.setTitle("HTTP record review");
        sessionId = sessions.create(request).getId();
        primary = newCompany();
        jdbc.update("UPDATE app_user SET password_hash=? WHERE id=?", passwords.encode(PASSWORD), currentUser.getId());
        clearAuthentication();
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(new ApiRequestBodySizeFilter(limits, json), security).build();
        var login = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("username", currentUser.getUsername(), "password", PASSWORD))))
                .andExpect(status().isOk()).andReturn().getRequest().getSession(false);
        http = org.junit.jupiter.api.Assertions.assertInstanceOf(MockHttpSession.class, login);
        var csrf = mvc.perform(get("/api/auth/csrf").session(http)).andExpect(status().isOk()).andReturn();
        JsonNode token = json.readTree(csrf.getResponse().getContentAsString());
        csrfHeader = token.path("headerName").asString();
        csrfToken = token.path("token").asString();
    }

    @AfterEach
    void removeHttpFixtures() {
        if (primary != null) {
            jdbc.update("DELETE FROM company WHERE workspace_id=? AND id=?", workspace.getId(), primary.getId());
        }
        if (otherWorkspace != null) {
            jdbc.update("DELETE FROM workspace_member WHERE workspace_id=?", otherWorkspace.getId());
            jdbc.update("DELETE FROM workspace WHERE id=?", otherWorkspace.getId());
        }
    }

    @Test
    void sliceOneHttpContractsEnforceAuthCsrfAndBounds() throws Exception {
        mvc.perform(get(BASE)).andExpect(status().isUnauthorized());
        mvc.perform(post(createPath()).session(http).header("X-Workspace-Id", workspace.getId())
                .contentType(MediaType.APPLICATION_JSON).content(body())).andExpect(status().isForbidden());
        mvc.perform(write(createPath()).content(" ".repeat(96 * 1024 + 1)))
                .andExpect(status().isPayloadTooLarge());
        mvc.perform(write(createPath()).content(body().replace("record_review_v1", "ask_member")))
                .andExpect(status().isBadRequest());
        JsonNode created = create(body());
        int id = created.path("id").asInt();
        mvc.perform(write(BASE + "/" + id + "/pause").content(" ".repeat(4097)))
                .andExpect(status().isPayloadTooLarge());
        mvc.perform(read(BASE + "?limit=51")).andExpect(status().isBadRequest());
        mvc.perform(read(BASE + "/2147483647")).andExpect(status().isNotFound());
        otherWorkspace = new Workspace();
        otherWorkspace.setOrgId(workspace.getOrgId());
        otherWorkspace.setName("Other HTTP workspace");
        otherWorkspace.setSlug("mission-other-http-" + UUID.randomUUID());
        workspaceMapper.insert(otherWorkspace);
        workspaceMapper.addMember(otherWorkspace.getId(), currentUser.getId(), "owner");
        mvc.perform(get(BASE + "/" + id).session(http).header("X-Workspace-Id", otherWorkspace.getId()))
                .andExpect(status().isNotFound());
        workspaceMapper.removeMember(workspace.getId(), currentUser.getId());
        mvc.perform(read(BASE + "/" + id)).andExpect(status().isForbidden());
    }

    @Test
    void canonicalLifecycleOutputAndPrivacyAreReadThroughHttp() throws Exception {
        String body = body();
        JsonNode created = create(body);
        int id = created.path("id").asInt();
        mvc.perform(read(BASE)).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(id));
        mvc.perform(read(BASE + "/" + id)).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("queued"));
        mvc.perform(read(BASE + "/" + id + "/output")).andExpect(status().isConflict());
        JsonNode paused = control(id, "pause", created.path("stateVersion").asString());
        mvc.perform(write(BASE + "/" + id + "/cancel")
                .content(json.writeValueAsString(Map.of("expectedStateVersion", created.path("stateVersion").asString()))))
                .andExpect(status().isConflict());
        JsonNode resumed = control(id, "resume", paused.path("stateVersion").asString());
        control(id, "cancel", resumed.path("stateVersion").asString());
        mvc.perform(write(createPath()).content(body)).andExpect(status().isAccepted()).andExpect(jsonPath("$.id").value(id));

        int reportRun = create(body()).path("id").asInt();
        authenticateAs(currentUser, workspace.getId());
        AiAgentExecutionPolicy policy = persistence.claim(runs.getRun(workspace.getId(), reportRun), new byte[32],
                new AiRunLeaseGuard(properties.getRunLeaseTtl()), capability);
        persistence.resolve(policy, "Reviewed the selected record.", "{\"citations\":[],\"resources\":[]}");
        clearAuthentication();
        mvc.perform(read(BASE + "/" + reportRun + "/output")).andExpect(status().isOk())
                .andExpect(jsonPath("$.report.markdown").value("Reviewed the selected record."))
                .andExpect(jsonPath("$.proposalSet.items").isEmpty());
        authenticateAs(currentUser, workspace.getId());
        finish(invalidate());
        clearAuthentication();
        mvc.perform(read(BASE + "/" + reportRun)).andExpect(status().isOk())
                .andExpect(jsonPath("$.contentWithheld").value(true));
        mvc.perform(read(BASE + "/" + reportRun + "/output")).andExpect(status().isGone());
        mvc.perform(write(createPath()).content(body)).andExpect(status().isGone());
    }

    @Test
    void explicitPauseOverInterruptedIsDurableMemberPauseThroughHttp() throws Exception {
        int id = create(body()).path("id").asInt();
        authenticateAs(currentUser, workspace.getId());
        persistence.claim(runs.getRun(workspace.getId(), id), new byte[32],
                new AiRunLeaseGuard(properties.getRunLeaseTtl()), capability);
        persistence.interrupt(runs.getRun(workspace.getId(), id), "interrupted");
        var interrupted = runs.getRun(workspace.getId(), id);
        clearAuthentication();
        JsonNode paused = control(id, "pause", Long.toString(interrupted.getStateVersion()));
        org.junit.jupiter.api.Assertions.assertEquals(interrupted.getStateVersion() + 1,
                Long.parseLong(paused.path("stateVersion").asString()));
        mvc.perform(read(BASE + "/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("paused")).andExpect(jsonPath("$.reasonCode").value("member"));
        authenticateAs(currentUser, workspace.getId());
        org.junit.jupiter.api.Assertions.assertFalse(persistence.requeueInterrupted(interrupted));
        org.junit.jupiter.api.Assertions.assertTrue(runs.interruptedReadRuns(workspace.getId(), 25).isEmpty());
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () -> persistence.claim(
                runs.getRun(workspace.getId(), id), new byte[32], new AiRunLeaseGuard(properties.getRunLeaseTtl()), capability));
        clearAuthentication();
    }

    @Test
    void resumeUsesLogicalSegmentAttemptsAndRetainsOwnerTenantGuards() throws Exception {
        int id = create(body()).path("id").asInt();
        authenticateAs(currentUser, workspace.getId());
        for (int attempt = 1; attempt <= 3; attempt++) {
            var policy = persistence.claim(runs.getRun(workspace.getId(), id), new byte[32],
                    new AiRunLeaseGuard(properties.getRunLeaseTtl()), capability);
            if (attempt < 3) {
                var live = runs.getRun(workspace.getId(), id);
                var paused = persistence.control(live, live.getStateVersion(), "pause");
                persistence.control(paused, paused.getStateVersion(), "resume");
            } else {
                var snapshot = persistence.snapshot(policy);
                var args = json.createObjectNode().put("expectedPlanRevision", snapshot.plan().revision())
                        .put("expectedCheckpointRevision", snapshot.checkpoint().revision()).putNull("nextPlanItemKey")
                        .put("phase", "reporting");
                args.putObject("memory").put("summary", "Ready for report").putArray("citations");
                var prepared = new AiAgentPlanService(new AiAgentContinuationCodec()).prepareCheckpoint(snapshot, args,
                        policy.segmentNo(), text -> new AiAgentMemory.DurableText(text, java.util.List.of()),
                        ignored -> java.util.List.of());
                persistence.commitCheckpoint(policy, 1, 0, json.writeValueAsString(prepared.arguments()),
                        json.writeValueAsString(prepared.result()), prepared.snapshot());
            }
        }
        var next = persistence.claim(runs.getRun(workspace.getId(), id), new byte[32],
                new AiRunLeaseGuard(properties.getRunLeaseTtl()), capability);
        org.junit.jupiter.api.Assertions.assertEquals(2, next.segmentNo());
        persistence.interrupt(runs.getRun(workspace.getId(), id), "interrupted");
        var stopped = runs.getRun(workspace.getId(), id);
        org.junit.jupiter.api.Assertions.assertEquals(4, runs.listSegments(workspace.getId(), id).size());
        clearAuthentication();
        var resumed = control(id, "resume", Long.toString(stopped.getStateVersion()));
        org.junit.jupiter.api.Assertions.assertEquals("queued", resumed.path("state").asString());
        mvc.perform(post(BASE + "/" + id + "/pause").session(http).header("X-Workspace-Id", workspace.getId())
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        otherWorkspace = new Workspace();
        otherWorkspace.setOrgId(workspace.getOrgId());
        otherWorkspace.setName("Continuation foreign workspace");
        otherWorkspace.setSlug("continuation-foreign-" + UUID.randomUUID());
        workspaceMapper.insert(otherWorkspace);
        workspaceMapper.addMember(otherWorkspace.getId(), currentUser.getId(), "owner");
        mvc.perform(post(BASE + "/" + id + "/resume").session(http).header("X-Workspace-Id", otherWorkspace.getId())
                .header(csrfHeader, csrfToken).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("expectedStateVersion", resumed.path("stateVersion").asString()))))
                .andExpect(status().isNotFound());
    }

    private JsonNode create(String body) throws Exception {
        var result = mvc.perform(write(createPath()).content(body)).andExpect(status().isAccepted()).andReturn();
        assertNotNull(result.getResponse().getHeader("Location"));
        return json.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode control(int id, String action, String version) throws Exception {
        return json.readTree(mvc.perform(write(BASE + "/" + id + "/" + action)
                .content(json.writeValueAsString(Map.of("expectedStateVersion", version))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private String createPath() {
        return "/api/ai/assistant/sessions/" + sessionId + "/missions";
    }

    private String body() {
        return json.writeValueAsString(Map.of("clientRequestKey", UUID.randomUUID().toString(),
                "missionType", "record_review_v1", "goal", "Review the selected record",
                "scope", Map.of("kind", "record", "recordKind", "company", "recordId", primary.getId())));
    }

    private MockHttpServletRequestBuilder write(String path) {
        return post(path).session(http).header(csrfHeader, csrfToken)
                .header("X-Workspace-Id", workspace.getId()).contentType(MediaType.APPLICATION_JSON);
    }

    private MockHttpServletRequestBuilder read(String path) {
        return get(path).session(http).header("X-Workspace-Id", workspace.getId());
    }
}
