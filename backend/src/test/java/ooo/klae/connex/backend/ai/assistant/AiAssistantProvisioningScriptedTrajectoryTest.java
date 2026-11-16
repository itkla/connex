package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import ooo.klae.connex.backend.ai.mission.AiAgentPrivacyInitializer;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.controllers.AiAssistantController;
import ooo.klae.connex.backend.dto.WorkspaceMembershipDto;
import ooo.klae.connex.backend.exceptions.GlobalExceptionHandler;
import ooo.klae.connex.backend.exceptions.ServiceUnavailableException;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.tenant.TenantContext;
import tools.jackson.databind.ObjectMapper;

/** Provisions on an unresolved request thread, then exercises real chat services and the scripted worker. */
class AiAssistantProvisioningScriptedTrajectoryTest extends AbstractScriptedTrajectoryTest {
    @Autowired private AiAssistantController controller;
    @Autowired private GlobalExceptionHandler errors;
    @Autowired private AiAgentPrivacyInitializer initializer;
    @Autowired private TenantContext tenantContext;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;
    private MockMvc mvc;

    @Override
    protected WorkspaceMembershipDto provisionWorkspace(WorkspaceService service, String name, User owner) {
        var previous = RequestContextHolder.getRequestAttributes();
        var request = new ServletRequestAttributes(new MockHttpServletRequest());
        RequestContextHolder.setRequestAttributes(request);
        try {
            assertFalse(tenantContext.isResolved());
            WorkspaceMembershipDto created = super.provisionWorkspace(service, name, owner);
            assertSame(request, RequestContextHolder.getRequestAttributes());
            assertFalse(tenantContext.isResolved());
            return created;
        } finally {
            RequestContextHolder.setRequestAttributes(previous);
        }
    }

    @BeforeEach
    void prepareHttp() {
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(errors).build();
        authenticate();
    }

    @Test
    void newlyProvisionedWorkspaceCanCreateReadAndCompleteAnOrdinaryTurn() throws Exception {
        assertEquals(1L, generation());
        assertEquals(Boolean.FALSE, jdbc.queryForObject(
                "SELECT blocked FROM ai_agent_privacy_barrier WHERE workspace_id=?", Boolean.class, workspaceId()));
        int sessionId = createSession();
        read(sessionId);
        completeTurn(sessionId);
    }

    @Test
    void missingBarrierRefusesReadAndTurnUntilReconciliationThenRetrySucceeds() throws Exception {
        int sessionId = createSession();
        ooo.klae.connex.backend.support.TestEntityFixtures.deleteChatPrivacy(jdbc, workspaceId());
        mvc.perform(get(path(sessionId)).param("page", "1").param("size", "50"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "60"))
                .andExpect(jsonPath("$.code").value("ai_privacy_initialization_pending"));
        mvc.perform(post(path(sessionId) + "/turns").contentType(MediaType.APPLICATION_JSON)
                .content(turnBody())).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ai_privacy_initialization_pending"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_chat_turn WHERE workspace_id=?",
                Integer.class, workspaceId()));
        assertNull(generation());

        initializer.initialize(workspaceId());

        assertEquals(1L, generation());
        read(sessionId);
        completeTurn(sessionId);
    }

    @Test
    void reconciliationNeverResetsMissingAuthorityOverTaintedHistory() throws Exception {
        int sessionId = createSession();
        jdbc.update("UPDATE ai_chat_session SET message_seq_high_water=1, mission_taint_seq=1,"
                + " mission_taint_generation=7 WHERE workspace_id=? AND id=?", workspaceId(), sessionId);
        ooo.klae.connex.backend.support.TestEntityFixtures.deleteChatPrivacy(jdbc, workspaceId());

        assertThrows(ServiceUnavailableException.class, () -> initializer.initialize(workspaceId()));

        assertNull(generation());
        mvc.perform(get(path(sessionId))).andExpect(status().isServiceUnavailable());
        mvc.perform(post(path(sessionId) + "/turns").contentType(MediaType.APPLICATION_JSON)
                .content(turnBody())).andExpect(status().isServiceUnavailable());
    }

    private int createSession() throws Exception {
        var result = mvc.perform(post("/api/ai/assistant/sessions").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("title", "Provisioned chat"))))
                .andExpect(status().isCreated()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("id").asInt();
    }

    private void read(int sessionId) throws Exception {
        mvc.perform(get(path(sessionId)).param("page", "1").param("size", "50"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.session.id").value(sessionId));
    }

    private void completeTurn(int sessionId) throws Exception {
        person("Isadora Quillfeather", "isadora.quillfeather@example.invalid", null);
        var result = mvc.perform(post(path(sessionId) + "/turns").contentType(MediaType.APPLICATION_JSON)
                .content(turnBody())).andExpect(status().isAccepted()).andReturn();
        int turnId = json.readTree(result.getResponse().getContentAsString()).path("turnId").asInt();
        var settled = awaitTerminal(sessionId, turnId);
        assertEquals("resolved", settled.getStatus(), settled.getTerminalReason());
        assertFalse(journal().recorded().isEmpty());
        read(sessionId);
    }

    private String turnBody() {
        return json.writeValueAsString(Map.of("content", "connex_script_masked_egress check this contact for me"));
    }

    private Long generation() {
        return jdbc.query("SELECT generation FROM ai_agent_privacy_barrier WHERE workspace_id=?",
                result -> result.next() ? result.getLong(1) : null, workspaceId());
    }

    private static String path(int sessionId) {
        return "/api/ai/assistant/sessions/" + sessionId;
    }
}
