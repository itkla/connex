package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.CsrfBootstrapDto;
import ooo.klae.connex.backend.session.AccountSessionIndex;
import tools.jackson.databind.JsonNode;

/** Exercises unchanged assistant HTTP routes with real authentication, tenant resolution and SQL. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"server.address=127.0.0.1", "server.servlet.session.cookie.secure=false"})
class AiProposalExpiryHttpIntegrationTest extends AbstractAiProposalExpiryIntegrationTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    @LocalServerPort int port;
    @Autowired FindByIndexNameSessionRepository<? extends Session> indexedSessions;
    @Autowired SessionRepository<? extends Session> sessions;
    final List<Browser> browsers = new ArrayList<>();

    /** Uses ordinary authenticated reviewers, retaining real MFA confinement for privileged accounts. */
    @BeforeEach
    void grantHttpReviewerPermissions() {
        jdbc.update("INSERT INTO workspace_role (workspace_id, name) VALUES (?, 'Expiry HTTP reviewer')", workspace.getId());
        Integer roleId = jdbc.queryForObject(
                "SELECT id FROM workspace_role WHERE workspace_id = ? AND name = 'Expiry HTTP reviewer'",
                Integer.class, workspace.getId());
        assertNotNull(roleId);
        for (String permission : List.of("AI_USE", "AI_SESSION_ADMIN", "AI_SESSION_SHARE", "PERSON_UPDATE",
                "COMPANY_UPDATE", "DEAL_UPDATE", "TASK_UPDATE", "NOTE_CREATE", "NOTE_DELETE")) {
            jdbc.update("INSERT INTO workspace_role_permission (workspace_role_id, permission) VALUES (?, ?)", roleId, permission);
        }
        jdbc.update("UPDATE workspace_member SET role = 'member', role_id = ? WHERE workspace_id = ?", roleId, workspace.getId());
    }

    @AfterEach
    void closeBrowsers() {
        for (Browser browser : browsers) {
            for (String sessionId : indexedSessions.findByPrincipalName(
                    new AccountSessionIndex(browser.user().getId()).getName()).keySet()) {
                sessions.deleteById(sessionId);
            }
            browser.client().close();
        }
        browsers.clear();
    }

    @Test
    void legacyKnownIdApplyExpiresWithoutMutation() throws Exception {
        Proposal proposal = proposal("assign_owner", "person");
        Browser browser = login(actor);
        var before = domainSnapshot();
        now.set(deadline);
        assertExpired(request(browser, "POST", path(proposal) + "/approve"));
        assertEquals(before, domainSnapshot());
        assertPending(proposal);
    }

    @Test
    void legacyKnownIdDiscardExpiresWithoutRejection() throws Exception {
        Proposal proposal = proposal("assign_owner", "person");
        Browser browser = login(actor);
        var before = domainSnapshot();
        now.set(deadline);
        assertExpired(request(browser, "POST", path(proposal) + "/reject"));
        assertEquals(before, domainSnapshot());
        assertPending(proposal);
    }

    @ParameterizedTest
    @ValueSource(strings = {"person", "company", "deal", "task"})
    void discardDoesNotAcquireDomainAuthority(String kind) throws Exception {
        Proposal proposal = proposal("task".equals(kind) ? "complete_task" : "assign_owner", kind);
        Browser browser = login(actor);
        jdbc.update("INSERT INTO workspace_role (workspace_id, name) VALUES (?, 'AI review only')", workspace.getId());
        Integer role = jdbc.queryForObject("SELECT id FROM workspace_role WHERE workspace_id = ? AND name = 'AI review only'",
                Integer.class, workspace.getId());
        assertNotNull(role);
        jdbc.update("INSERT INTO workspace_role_permission (workspace_role_id, permission) VALUES (?, 'AI_USE')", role);
        jdbc.update("UPDATE workspace_member SET role_id = ? WHERE workspace_id = ? AND user_id = ?",
                role, workspace.getId(), actor.getId());
        var before = domainSnapshot();
        HttpResponse<String> response = request(browser, "POST", path(proposal) + "/reject");
        assertEquals(200, response.statusCode(), response.body());
        assertEquals("discarded", json.readTree(response.body()).path("reviewState").asString());
        assertEquals(before, domainSnapshot());
        assertEquals(200, request(browser, "POST", path(proposal) + "/reject").statusCode());
    }

    @Test
    void pendingSqlFiltersExpiryBeforeLimit() throws Exception {
        Proposal first = proposal("assign_owner", "person");
        int historyCap = 100;
        for (int sequence = 2; sequence <= historyCap + 1; sequence++) {
            proposal("assign_owner", "person", first.sessionId(), sequence);
        }
        jdbc.update("UPDATE ai_chat_tool_call tc JOIN ai_chat_message m ON m.id = tc.message_id AND m.workspace_id = tc.workspace_id"
                        + " SET tc.created_at = ? WHERE m.workspace_id = ? AND m.session_id = ?",
                dbTime(originalNow.minus(72, ChronoUnit.HOURS)), workspace.getId(), first.sessionId());
        Proposal fresh = proposal("assign_owner", "person", first.sessionId(), historyCap + 2);
        Browser browser = login(actor);
        JsonNode pending = body(request(browser, "GET", sessionPath(first) + "/tool-calls?pendingOnly=true"), 200);
        assertEquals(1, pending.size());
        assertEquals(fresh.id(), pending.get(0).path("id").asInt());
        assertEquals("open", pending.get(0).path("reviewState").asString());
        JsonNode history = body(request(browser, "GET", sessionPath(first) + "/tool-calls"), 200);
        assertEquals(historyCap, history.size());
        assertEquals("expired", history.get(0).path("reviewState").asString());
        assertEquals("expired", body(request(browser, "GET", path(first)), 200).path("reviewState").asString());
    }

    @Test
    void allLegacyReadSurfacesAgreeOnExpiry() throws Exception {
        Proposal expired = proposal("assign_owner", "person");
        Proposal fresh = proposal("assign_owner", "person", expired.sessionId(), 2);
        jdbc.update("UPDATE ai_chat_tool_call SET created_at = ? WHERE workspace_id = ? AND id = ?",
                dbTime(originalNow.minus(72, ChronoUnit.HOURS)), workspace.getId(), expired.id());
        Browser requester = login(actor);
        JsonNode individual = body(request(requester, "GET", path(expired)), 200);
        JsonNode historical = body(request(requester, "GET", sessionPath(expired) + "/tool-calls"), 200);
        JsonNode pending = body(request(requester, "GET", sessionPath(expired) + "/tool-calls?pendingOnly=true"), 200);
        assertEquals("expired", individual.path("reviewState").asString());
        assertEquals(individual.path("reviewExpiresAt"), historical.get(0).path("reviewExpiresAt"));
        assertEquals(fresh.id(), pending.get(0).path("id").asInt());
        assertEquals(1, pending.size());
        Browser admin = login(other);
        jdbc.update("DELETE FROM workspace_member WHERE workspace_id = ? AND user_id = ?", workspace.getId(), actor.getId());
        int auditsBefore = readAudits(expired.sessionId());
        JsonNode retained = body(request(admin, "POST", path(expired) + "/retained"), 200);
        JsonNode retainedHistory = body(request(admin, "POST", sessionPath(expired) + "/tool-calls/retained"), 200);
        JsonNode retainedPending = body(request(admin, "POST", sessionPath(expired) + "/tool-calls/retained?pendingOnly=true"), 200);
        assertEquals(individual.path("reviewState"), retained.path("reviewState"));
        assertEquals(individual.path("reviewExpiresAt"), retained.path("reviewExpiresAt"));
        assertEquals(individual.path("reviewState"), retainedHistory.get(0).path("reviewState"));
        assertEquals(fresh.id(), retainedPending.get(0).path("id").asInt());
        assertTrue(retained.path("changes").isEmpty());
        assertFalse(retained.path("undoAvailable").asBoolean());
        assertEquals(auditsBefore + 3, readAudits(expired.sessionId()));
    }

    @Test
    void expiredErrorDoesNotRevealForeignProposal() throws Exception {
        Proposal proposal = proposal("assign_owner", "person");
        Proposal another = proposal("assign_owner", "company");
        Browser requester = login(actor);
        Browser stranger = login(other);
        now.set(deadline);
        for (String action : List.of("approve", "reject")) {
            assertUnrevealing(request(stranger, "POST", path(proposal) + "/" + action));
            assertUnrevealing(request(requester, "POST", sessionPath(another) + "/tool-calls/" + proposal.id() + "/" + action));
            assertUnrevealing(request(requester, "POST", path(proposal) + "/" + action, workspace.getId() + 1));
        }
        jdbc.update("UPDATE ai_chat_session SET visibility = 'shared' WHERE workspace_id = ? AND id = ?",
                workspace.getId(), proposal.sessionId());
        jdbc.update("INSERT INTO ai_chat_session_participant (workspace_id, session_id, user_id, status) VALUES (?, ?, ?, 'joined')",
                workspace.getId(), proposal.sessionId(), other.getId());
        assertEquals("expired", body(request(stranger, "GET", path(proposal)), 200).path("reviewState").asString());
        assertUnrevealing(request(stranger, "POST", path(proposal) + "/approve"));
        assertUnrevealing(request(stranger, "POST", path(proposal) + "/reject"));
        jdbc.update("DELETE FROM workspace_member WHERE workspace_id = ? AND user_id = ?", workspace.getId(), actor.getId());
        assertUnrevealing(request(requester, "POST", path(proposal) + "/approve"));
        try (HttpClient unauthenticated = HttpClient.newHttpClient()) {
            HttpResponse<String> response = unauthenticated.send(HttpRequest.newBuilder(uri(path(proposal)))
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(401, response.statusCode());
            assertFalse(response.body().contains("AI_PROPOSAL_EXPIRED"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"proposed", "failed"})
    void falseMarkerNeverBecomesExpiredProposal(String status) throws Exception {
        Proposal proposal = proposal("assign_owner", "person");
        jdbc.update("UPDATE ai_chat_tool_call SET server_proposal = FALSE, status = ? WHERE workspace_id = ? AND id = ?",
                status, workspace.getId(), proposal.id());
        Browser browser = login(actor);
        now.set(deadline);
        assertEquals(404, request(browser, "GET", path(proposal)).statusCode());
        assertTrue(body(request(browser, "GET", sessionPath(proposal) + "/tool-calls"), 200).isEmpty());
        HttpResponse<String> response = request(browser, "POST", path(proposal) + "/approve");
        assertTrue(response.statusCode() >= 400);
        assertFalse(response.body().contains("AI_PROPOSAL_EXPIRED"));
    }

    @Test
    void strandedAutoExpiresWithoutManualApproval() throws Exception {
        Proposal proposal = proposal("create_note", "person");
        Browser browser = login(actor);
        now.set(deadline);
        JsonNode card = body(request(browser, "GET", path(proposal)), 200);
        assertEquals("expired", card.path("reviewState").asString());
        assertEquals("auto", card.path("tier").asString());
        var before = domainSnapshot();
        for (String action : List.of("approve", "reject")) {
            HttpResponse<String> response = request(browser, "POST", path(proposal) + "/" + action);
            assertEquals(409, response.statusCode());
            assertFalse(response.body().contains("AI_PROPOSAL_EXPIRED"));
        }
        org.junit.jupiter.api.Assertions.assertThrows(
                AiAssistantProposalExpiredException.class, () -> execute(proposal));
        assertEquals(before, domainSnapshot());
        assertPending(proposal);
    }

    @Test
    void terminalOutcomesSurviveReviewDeadline() throws Exception {
        Proposal applied = proposal("create_note", "person");
        Proposal discarded = proposal("assign_owner", "company");
        Proposal failed = proposal("assign_owner", "person");
        Proposal undone = proposal("create_note", "deal");
        execute(applied);
        writes.reject(discarded.sessionId(), discarded.id());
        execute(undone);
        writes.undo(undone.sessionId(), undone.id());
        jdbc.update("UPDATE ai_chat_tool_call SET status = 'failed' WHERE workspace_id = ? AND id = ?", workspace.getId(), failed.id());
        Browser browser = login(actor);
        now.set(deadline.plusSeconds(1));
        for (var expected : Map.of(applied, "applied", discarded, "discarded", failed, "failed", undone, "undone").entrySet()) {
            JsonNode card = body(request(browser, "GET", path(expected.getKey())), 200);
            assertEquals(expected.getValue(), card.path("reviewState").asString());
            assertEquals(deadline, Instant.parse(card.path("reviewExpiresAt").asString()));
        }
    }

    @Test
    void undoWindowIsIndependentOfReviewWindow() throws Exception {
        Proposal proposal = proposal("create_note", "person");
        jdbc.update("UPDATE ai_chat_tool_call SET created_at = ? WHERE workspace_id = ? AND id = ?",
                dbTime(originalNow.minus(72, ChronoUnit.HOURS).plus(1, ChronoUnit.MICROS)), workspace.getId(), proposal.id());
        execute(proposal);
        Proposal later = proposal("create_note", "deal");
        jdbc.update("UPDATE ai_chat_tool_call SET created_at = ? WHERE workspace_id = ? AND id = ?",
                dbTime(originalNow.minus(72, ChronoUnit.HOURS).plus(1, ChronoUnit.MICROS)), workspace.getId(), later.id());
        execute(later);
        Browser browser = login(actor);
        now.set(originalNow.plus(10, ChronoUnit.MINUTES));
        JsonNode card = body(request(browser, "GET", path(proposal)), 200);
        assertEquals("applied", card.path("reviewState").asString());
        assertTrue(card.path("undoAvailable").asBoolean());
        JsonNode inverse = body(request(browser, "POST", path(proposal) + "/undo"), 200);
        assertEquals("undone", inverse.path("reviewState").asString());
        now.set(originalNow.plus(10, ChronoUnit.MINUTES).plus(1, ChronoUnit.MICROS));
        HttpResponse<String> refused = request(browser, "POST", path(later) + "/undo");
        assertEquals(409, refused.statusCode(), refused.body());
        assertFalse(refused.body().contains("AI_PROPOSAL_EXPIRED"));
        assertEquals("applied", body(request(browser, "GET", path(later)), 200).path("reviewState").asString());
    }

    @Test
    void expiryDoesNotPurgeRetainedHistory() throws Exception {
        Proposal proposal = proposal("assign_owner", "person");
        Browser requester = login(actor);
        now.set(deadline);
        assertEquals("expired", body(request(requester, "GET", path(proposal)), 200).path("reviewState").asString());
        assertEquals(204, request(requester, "DELETE", sessionPath(proposal)).statusCode());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ai_chat_tool_call WHERE workspace_id = ? AND id = ?",
                Integer.class, workspace.getId(), proposal.id()));
        Browser admin = login(other);
        jdbc.update("DELETE FROM workspace_member WHERE workspace_id = ? AND user_id = ?", workspace.getId(), actor.getId());
        assertEquals("expired", body(request(admin, "POST", path(proposal) + "/retained"), 200).path("reviewState").asString());
        jdbc.update("DELETE FROM ai_chat_turn WHERE workspace_id = ? AND session_id = ?", workspace.getId(), proposal.sessionId());
        jdbc.update("DELETE FROM ai_chat_session WHERE workspace_id = ? AND id = ?", workspace.getId(), proposal.sessionId());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_chat_tool_call WHERE workspace_id = ? AND id = ?",
                Integer.class, workspace.getId(), proposal.id()));
    }

    private int readAudits(int sessionId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE workspace_id = ? AND entity_id = ? AND action = 'ai.assistant.session.read'",
                Integer.class, workspace.getId(), sessionId);
        assertNotNull(count);
        return count;
    }

    private Browser login(User user) throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT)
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(uri("/api/auth/login"))
                .timeout(TIMEOUT).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(
                        Map.of("username", user.getUsername(), "password", PASSWORD)))).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        HttpResponse<String> csrf = client.send(HttpRequest.newBuilder(uri("/api/auth/csrf"))
                .timeout(TIMEOUT).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, csrf.statusCode(), csrf.body());
        CsrfBootstrapDto token = json.readValue(csrf.body(), CsrfBootstrapDto.class);
        assertNotNull(token);
        assertNotNull(token.headerName());
        assertNotNull(token.token());
        Browser browser = new Browser(user, client, token);
        browsers.add(browser);
        return browser;
    }

    private HttpResponse<String> request(Browser browser, String method, String path) throws Exception {
        return request(browser, method, path, workspace.getId());
    }

    private HttpResponse<String> request(Browser browser, String method, String path, int workspaceId) throws Exception {
        return browser.client().send(HttpRequest.newBuilder(uri(path)).timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .header("X-Workspace-Id", Integer.toString(workspaceId))
                .header(browser.csrf().headerName(), browser.csrf().token())
                .method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode body(HttpResponse<String> response, int status) {
        assertEquals(status, response.statusCode(), response.body());
        return json.readTree(response.body());
    }

    private void assertExpired(HttpResponse<String> response) {
        JsonNode error = body(response, 409);
        assertEquals("AI_PROPOSAL_EXPIRED", error.path("code").asString());
        assertEquals("Assistant proposal review window has expired", error.path("message").asString());
        assertFalse(error.has("target"));
        assertFalse(error.has("arguments"));
        assertFalse(response.body().contains(person.getName()));
    }

    private void assertUnrevealing(HttpResponse<String> response) {
        assertTrue(response.statusCode() == 401 || response.statusCode() == 403 || response.statusCode() == 404,
                response.statusCode() + ": " + response.body());
        assertFalse(response.body().contains("AI_PROPOSAL_EXPIRED"));
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private static String sessionPath(Proposal proposal) {
        return "/api/ai/assistant/sessions/" + proposal.sessionId();
    }

    private static String path(Proposal proposal) {
        return sessionPath(proposal) + "/tool-calls/" + proposal.id();
    }

    private record Browser(User user, HttpClient client, CsrfBootstrapDto csrf) {
    }
}
