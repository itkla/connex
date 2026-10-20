package ooo.klae.connex.backend.ai.mission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import ooo.klae.connex.backend.ai.assistant.AiChatResourceRegistry;
import ooo.klae.connex.backend.ai.lease.AiRunLeaseGuard;
import ooo.klae.connex.backend.beans.AiChatSession;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.AiAgentProposalDecisionRequest;
import ooo.klae.connex.backend.dto.AiAgentRunCreateRequest;
import ooo.klae.connex.backend.dto.CsrfBootstrapDto;
import ooo.klae.connex.backend.session.AccountSessionIndex;
import tools.jackson.databind.JsonNode;

/** Real cookie, CSRF, workspace and SQL effects through the additive mission review endpoints. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"server.address=127.0.0.1", "server.servlet.session.cookie.secure=false"})
class AiAgentProposalHttpIntegrationTest extends AbstractAiAgentProposalIntegrationTest {
    private static final String PASSWORD = "Mission-Proposal-Http9!";
    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    @LocalServerPort private int port;
    @Autowired private PasswordEncoder passwords;
    @MockitoSpyBean private AiAgentProposalDecisionService decisionTransactions;
    @Autowired private FindByIndexNameSessionRepository<? extends Session> indexedSessions;
    @Autowired private SessionRepository<? extends Session> sessions;
    private final List<Browser> browsers = new ArrayList<>();
    private final List<User> extraUsers = new ArrayList<>();
    private final List<Person> extraPeople = new ArrayList<>();
    private final List<Company> extraCompanies = new ArrayList<>();
    private Workspace foreign;

    @BeforeEach
    void allowOrdinaryHttpReviewer() {
        jdbc.update("INSERT INTO workspace_role (workspace_id,name) VALUES (?, 'Mission HTTP reviewer')", workspace.getId());
        Integer role = jdbc.queryForObject("SELECT id FROM workspace_role WHERE workspace_id=? AND name='Mission HTTP reviewer'", Integer.class, workspace.getId());
        assertNotNull(role);
        for (String permission : List.of("AI_USE", "AI_SESSION_ADMIN", "AI_SESSION_SHARE", "COMPANY_UPDATE")) {
            jdbc.update("INSERT INTO workspace_role_permission (workspace_role_id,permission) VALUES (?,?)", role, permission);
        }
        jdbc.update("UPDATE workspace_member SET role='member',role_id=? WHERE workspace_id=? AND user_id=?", role, workspace.getId(), currentUser.getId());
        jdbc.update("UPDATE app_user SET password_hash=? WHERE id=?", passwords.encode(PASSWORD), currentUser.getId());
    }

    @AfterEach
    void closeHttpFixtures() {
        for (Browser browser : browsers) {
            indexedSessions.findByPrincipalName(new AccountSessionIndex(browser.user().getId()).getName()).keySet()
                    .forEach(sessions::deleteById);
            browser.client().close();
        }
        for (Person person : extraPeople) {
            jdbc.update("DELETE FROM note WHERE workspace_id=? AND person_id=?", workspace.getId(), person.getId());
            jdbc.update("DELETE FROM person WHERE workspace_id=? AND id=?", workspace.getId(), person.getId());
        }
        for (Company company : extraCompanies) {
            jdbc.update("DELETE FROM company WHERE workspace_id=? AND id=?", workspace.getId(), company.getId());
        }
        for (User user : extraUsers) {
            jdbc.update("DELETE FROM ai_chat_session_participant WHERE user_id=?", user.getId());
            jdbc.update("DELETE FROM workspace_member WHERE user_id=?", user.getId());
            jdbc.update("DELETE FROM app_user WHERE id=?", user.getId());
        }
        if (foreign != null) {
            jdbc.update("DELETE FROM workspace_member WHERE workspace_id=?", foreign.getId());
            jdbc.update("DELETE FROM workspace WHERE id=?", foreign.getId());
        }
    }

    @Test
    void proposalDecisionsRequireAuthenticationCsrfAndPinnedWorkspace() throws Exception {
        var fixture = frozen("assign_owner");
        var body = json.writeValueAsString(request(fixture, "approve", fixture.selected().getFirst()));
        try (HttpClient anonymous = HttpClient.newBuilder()
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build()) {
            var bootstrap = anonymous.send(HttpRequest.newBuilder(uri("/api/auth/csrf")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            CsrfBootstrapDto csrf = json.readValue(body(bootstrap, 200).toString(), CsrfBootstrapDto.class);
            var response = anonymous.send(HttpRequest.newBuilder(uri(path(fixture))).header("Content-Type", "application/json")
                    .header(csrf.headerName(), csrf.token())
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(401, response.statusCode(), response.body());
        }
        Browser browser = login(currentUser);
        var noCsrf = browser.client().send(HttpRequest.newBuilder(uri(path(fixture)))
                .header("X-Workspace-Id", Integer.toString(workspace.getId())).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(403, noCsrf.statusCode(), noCsrf.body());
        foreign = new Workspace();
        foreign.setOrgId(workspace.getOrgId());
        foreign.setName("Foreign mission proposal workspace");
        foreign.setSlug("proposal-http-" + UUID.randomUUID());
        workspaceMapper.insert(foreign);
        workspaceMapper.addMember(foreign.getId(), currentUser.getId(), "member");
        var withoutAiPermission = send(browser, "POST", path(fixture), body, foreign.getId());
        assertEquals(403, withoutAiPermission.statusCode(), withoutAiPermission.body());
        assertTrue(withoutAiPermission.body().contains("AI_USE"), withoutAiPermission.body());
        jdbc.update("INSERT INTO workspace_role (workspace_id,name) VALUES (?, 'Mission HTTP reviewer')", foreign.getId());
        Integer role = jdbc.queryForObject("SELECT id FROM workspace_role WHERE workspace_id=? AND name='Mission HTTP reviewer'",
                Integer.class, foreign.getId());
        assertNotNull(role);
        jdbc.update("INSERT INTO workspace_role_permission (workspace_role_id,permission) VALUES (?, 'AI_USE')", role);
        jdbc.update("UPDATE workspace_member SET role_id=? WHERE workspace_id=? AND user_id=?",
                role, foreign.getId(), currentUser.getId());
        var foreignMission = send(browser, "POST", path(fixture), body, foreign.getId());
        assertEquals(404, foreignMission.statusCode(), foreignMission.body());
        workspaceMapper.removeMember(foreign.getId(), currentUser.getId());
        var unavailablePin = send(browser, "POST", path(fixture), body, foreign.getId());
        assertEquals(403, unavailablePin.statusCode(), unavailablePin.body());
        authenticateAs(currentUser, workspace.getId());
        assertPending(fixture);
    }

    @Test
    void decisionBodyRejectsUnknownFieldsDuplicatesAndOversizeRequests() throws Exception {
        var fixture = frozen("assign_owner");
        Browser browser = login(currentUser);
        String body = json.writeValueAsString(request(fixture, "discard", fixture.selected().getFirst()));
        assertEquals(400, send(browser, "POST", path(fixture), body.replace("\"items\":", "\"unknown\":true,\"items\":"), workspace.getId()).statusCode());
        var duplicate = request(fixture, "discard", fixture.selected().getFirst(), fixture.selected().getFirst());
        assertEquals(400, send(browser, "POST", path(fixture), json.writeValueAsString(duplicate), workspace.getId()).statusCode());
        assertEquals(413, send(browser, "POST", path(fixture), " ".repeat(16385), workspace.getId()).statusCode());
        var overflow = new AiAgentProposalDecisionRequest("9223372036854775808", request(fixture, "discard", fixture.selected().getFirst()).items());
        assertEquals(400, send(browser, "POST", path(fixture), json.writeValueAsString(overflow), workspace.getId()).statusCode());
        authenticateAs(currentUser, workspace.getId());
        assertPending(fixture);
    }

    @Test
    void otherParticipantAdminAndForeignTenantCannotReadOrDecide() throws Exception {
        var fixture = frozen("assign_owner");
        User other = newUser();
        extraUsers.add(other);
        jdbc.update("UPDATE app_user SET password_hash=? WHERE id=?", passwords.encode(PASSWORD), other.getId());
        jdbc.update("UPDATE workspace_member SET role_id=(SELECT id FROM workspace_role WHERE workspace_id=? AND name='Mission HTTP reviewer') WHERE workspace_id=? AND user_id=?",
                workspace.getId(), workspace.getId(), other.getId());
        jdbc.update("UPDATE ai_chat_session SET visibility='shared' WHERE workspace_id=? AND id=?", workspace.getId(), fixture.sessionId());
        chatMapper.insertParticipant(workspace.getId(), fixture.sessionId(), other.getId());
        Browser browser = login(other);
        String output = "/api/ai/assistant/missions/" + fixture.runId() + "/output";
        assertEquals(404, send(browser, "GET", output, null, workspace.getId()).statusCode());
        assertEquals(404, send(browser, "GET", output.replace("/output", "/proposals/" + fixture.selected().getFirst().getActionSlot()), null, workspace.getId()).statusCode());
        assertEquals(404, send(browser, "POST", path(fixture), json.writeValueAsString(request(fixture, "discard", fixture.selected().getFirst())), workspace.getId()).statusCode());
        assertEquals(404, send(browser, "GET", "/api/ai/assistant/sessions/" + fixture.sessionId() + "/tool-calls/"
                + chatMapper.getMissionToolCallForMaintenance(workspace.getId(), fixture.runId(), fixture.selected().getFirst().getId()).getId(), null, workspace.getId()).statusCode());
    }

    @Test
    void legacyKnownIdApproveRejectAndReadRefuseMissionOrigin() throws Exception {
        var fixture = frozen("assign_owner");
        Browser browser = login(currentUser);
        var call = chatMapper.getMissionToolCallForMaintenance(workspace.getId(), fixture.runId(), fixture.selected().getFirst().getId());
        String base = "/api/ai/assistant/sessions/" + fixture.sessionId() + "/tool-calls/" + call.getId();
        assertEquals(404, send(browser, "GET", base, null, workspace.getId()).statusCode());
        assertEquals(409, send(browser, "POST", base + "/approve", null, workspace.getId()).statusCode());
        assertEquals(409, send(browser, "POST", base + "/reject", null, workspace.getId()).statusCode());
        authenticateAs(currentUser, workspace.getId());
        assertPending(fixture);
    }

    @Test
    void retainedAdministratorCannotReadKnownMissionSingletonOrDiscoverItInRetainedHistory() throws Exception {
        var fixture = frozen("assign_owner");
        User administrator = newUser();
        extraUsers.add(administrator);
        jdbc.update("UPDATE app_user SET password_hash=? WHERE id=?", passwords.encode(PASSWORD), administrator.getId());
        jdbc.update("UPDATE workspace_member SET role_id=(SELECT id FROM workspace_role WHERE workspace_id=? AND name='Mission HTTP reviewer') WHERE workspace_id=? AND user_id=?",
                workspace.getId(), workspace.getId(), administrator.getId());
        jdbc.update("UPDATE ai_chat_session SET created_by_user_id=NULL WHERE workspace_id=? AND id=?",
                workspace.getId(), fixture.sessionId());
        var call = chatMapper.getMissionToolCallForMaintenance(workspace.getId(), fixture.runId(), fixture.selected().getFirst().getId());
        Browser browser = login(administrator);
        String base = "/api/ai/assistant/sessions/" + fixture.sessionId() + "/tool-calls";

        assertEquals(404, send(browser, "POST", base + "/" + call.getId() + "/retained", null, workspace.getId()).statusCode());
        assertTrue(body(send(browser, "POST", base + "/retained", null, workspace.getId()), 200).isEmpty());
        assertEquals(404, send(browser, "GET", "/api/ai/assistant/missions/" + fixture.runId() + "/output", null, workspace.getId()).statusCode());
        authenticateAs(currentUser, workspace.getId());
        assertPending(fixture);
    }

    @Test
    void narrowerReviewerCanReadButCannotApproveWithoutDomainPermission() throws Exception {
        var fixture = frozen("assign_owner");
        Browser browser = login(currentUser);
        jdbc.update("DELETE p FROM workspace_role_permission p JOIN workspace_role r ON r.id=p.workspace_role_id WHERE r.workspace_id=? AND r.name='Mission HTTP reviewer' AND p.permission='COMPANY_UPDATE'",
                workspace.getId());

        JsonNode output = body(send(browser, "GET", "/api/ai/assistant/missions/" + fixture.runId() + "/output", null, workspace.getId()), 200);
        assertEquals("permissionLost", output.path("proposalSet").path("items").get(0).path("card").path("change").path("state").asString());
        JsonNode refused = body(send(browser, "POST", path(fixture), json.writeValueAsString(request(fixture, "approve", fixture.selected().getFirst())), workspace.getId()), 200);
        assertEquals("refused", refused.path("items").get(0).path("outcome").asString());
        authenticateAs(currentUser, workspace.getId());
        assertPending(fixture);
        assertEquals(primary.getOwnerId(), companyMapper.getCompanyById(workspace.getId(), primary.getId()).getOwnerId());
        JsonNode discarded = body(send(browser, "POST", path(fixture), json.writeValueAsString(request(fixture, "discard", fixture.selected().getFirst())), workspace.getId()), 200);
        assertEquals("rejected", discarded.path("items").get(0).path("outcome").asString());
    }

    @Test
    void accessibleOutOfScopeRecordCannotSupplyAnotherRootsActionSlot() throws Exception {
        var first = frozen("assign_owner");
        Company original = primary;
        Company other = newCompany();
        extraCompanies.add(other);
        jdbc.update("UPDATE company SET updated_at=DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 1 DAY) WHERE workspace_id=? AND id=?",
                workspace.getId(), other.getId());
        primary = other;
        Fixture second;
        try {
            second = frozen("assign_owner");
        } finally {
            primary = original;
        }
        Browser browser = login(currentUser);
        assertEquals(200, send(browser, "GET", "/api/ai/assistant/missions/" + second.runId() + "/output", null, workspace.getId()).statusCode());
        var crossRoot = new AiAgentProposalDecisionRequest(request(first, "approve", first.selected().getFirst()).expectedProposalRevision(),
                request(second, "approve", second.selected().getFirst()).items());

        assertEquals(404, send(browser, "POST", path(first), json.writeValueAsString(crossRoot), workspace.getId()).statusCode());
        authenticateAs(currentUser, workspace.getId());
        assertPending(first);
        assertPending(second);
        assertEquals(original.getOwnerId(), companyMapper.getCompanyById(workspace.getId(), original.getId()).getOwnerId());
        assertEquals(other.getOwnerId(), companyMapper.getCompanyById(workspace.getId(), other.getId()).getOwnerId());
    }

    @Test
    void personMissionExcludesPrivateNotesAndWithholdsAfterPrimaryVisibilityLoss() throws Exception {
        Person person = newPerson(null);
        extraPeople.add(person);
        jdbc.update("UPDATE person SET updated_at=DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 1 DAY) WHERE workspace_id=? AND id=?",
                workspace.getId(), person.getId());
        var note = newNote(currentUser, person, null);
        String secret = "private-mission-note-" + UUID.randomUUID();
        jdbc.update("UPDATE note SET visibility='private',content=? WHERE workspace_id=? AND id=?", secret, workspace.getId(), note.getId());
        AiChatSession session = new AiChatSession();
        session.setWorkspaceId(workspace.getId());
        session.setCreatedByUserId(currentUser.getId());
        session.setTitle("Person preparation without private notes");
        session.setVisibility("private");
        session.setStatus("active");
        chatMapper.insertSession(session);
        var create = new AiAgentRunCreateRequest(UUID.randomUUID().toString(), "record_preparation_v1", "Prepare contact changes",
                new AiAgentRunCreateRequest.Scope("record", "person", person.getId()), null);
        var run = persistence.create(session.getId(), create, "en", AiAgentCapabilityGate.hash(create.clientRequestKey()),
                json.writeValueAsString(create.scope()), restriction.current(workspace.getId()));
        var policy = persistence.claim(run, new byte[32], new AiRunLeaseGuard(properties.getRunLeaseTtl()), capability);
        evidence.begin(policy);
        var candidate = policy.execute(() -> {
            AiChatResourceRegistry resources = new AiChatResourceRegistry();
            String handle = resources.register("person", person.getId());
            var record = tools.execute("get_record", json.createObjectNode().put("handle", handle), resources, false, policy.turn().scope());
            assertFalse(json.writeValueAsString(record).contains(secret));
            var prepared = preparation.prepare(policy, "assign_owner", json.createObjectNode().put("handle", handle)
                    .put("owner", currentUser.getUsername()), resources, Map.of());
            persistence.publishPrepared(policy, prepared);
            return prepared;
        });
        evidence.clear();
        Fixture fixture = new Fixture(run.getId(), session.getId(), policy,
                List.of(actions.getById(workspace.getId(), run.getId(), candidate.action().getId())));
        freeze(fixture);
        Browser browser = login(currentUser);
        String base = "/api/ai/assistant/missions/" + fixture.runId();
        JsonNode output = body(send(browser, "GET", base + "/output", null, workspace.getId()), 200);
        assertFalse(output.toString().contains(secret));
        jdbc.update("UPDATE person SET archived_at=CURRENT_TIMESTAMP WHERE workspace_id=? AND id=?", workspace.getId(), person.getId());

        assertEquals(404, send(browser, "GET", base + "/output", null, workspace.getId()).statusCode());
        assertEquals(404, send(browser, "GET", base + "/proposals/" + fixture.selected().getFirst().getActionSlot(), null, workspace.getId()).statusCode());
        JsonNode metadata = body(send(browser, "GET", base, null, workspace.getId()), 200);
        assertTrue(metadata.path("contentWithheld").asBoolean());
        assertFalse(metadata.has("proposalCounts"));
        authenticateAs(currentUser, workspace.getId());
        assertPending(fixture);
        assertNull(jdbc.queryForObject("SELECT owner_id FROM person WHERE workspace_id=? AND id=?", Integer.class, workspace.getId(), person.getId()));
    }

    @Test
    void stagedRevisionAndPreparationBypassesRefuseBeforeAnyItemExecutes() throws Exception {
        var staged = staged("assign_owner");
        Browser browser = login(currentUser);
        assertEquals(409, send(browser, "POST", path(staged), json.writeValueAsString(request(staged, "approve", staged.selected().getFirst())), workspace.getId()).statusCode());
        authenticateAs(currentUser, workspace.getId());
        freeze(staged);
        var correct = request(staged, "approve", staged.selected().getFirst());
        var wrongPreparation = new AiAgentProposalDecisionRequest(correct.expectedProposalRevision(), List.of(
                new AiAgentProposalDecisionRequest.Item(correct.items().getFirst().actionSlot(), UUID.randomUUID().toString(), "approve")));
        assertEquals(409, send(browser, "POST", path(staged), json.writeValueAsString(wrongPreparation), workspace.getId()).statusCode());
        var wrongRevision = new AiAgentProposalDecisionRequest("999", correct.items());
        assertEquals(409, send(browser, "POST", path(staged), json.writeValueAsString(wrongRevision), workspace.getId()).statusCode());
        authenticateAs(currentUser, workspace.getId());
        assertPending(staged);
    }

    @Test
    void selectedItemsCommitIndependentlyAndReturnPartialResults() throws Exception {
        var fixture = frozen("update_record_fields", "assign_owner");
        Browser browser = login(currentUser);
        var response = body(send(browser, "POST", path(fixture), json.writeValueAsString(request(fixture, "approve",
                fixture.selected().getFirst(), fixture.selected().getLast())), workspace.getId()), 200);
        assertFalse(response.path("complete").asBoolean());
        assertEquals("executed", response.path("items").get(0).path("outcome").asString());
        assertEquals("refused", response.path("items").get(1).path("outcome").asString());
        authenticateAs(currentUser, workspace.getId());
        assertEquals("Prepared industry", companyMapper.getCompanyById(workspace.getId(), primary.getId()).getIndustry());
        assertEquals("proposed", chatMapper.getMissionToolCallForMaintenance(workspace.getId(), fixture.runId(), fixture.selected().getLast().getId()).getStatus());
    }

    @Test
    void revocationBetweenItemsPreservesCommittedEffectAndWithholdsPrivateOutcomes() throws Exception {
        var fixture = frozen("update_record_fields", "assign_owner");
        Browser browser = login(currentUser);
        jdbc.update("INSERT INTO workspace_role (workspace_id,name) VALUES (?, 'Revoked mission review')", workspace.getId());
        Integer revoked = jdbc.queryForObject("SELECT id FROM workspace_role WHERE workspace_id=? AND name='Revoked mission review'", Integer.class, workspace.getId());
        assertNotNull(revoked);
        AtomicInteger acquisitions = new AtomicInteger();
        doAnswer(invocation -> {
            if (acquisitions.incrementAndGet() == 2) {
                TransactionTemplate revocation = new TransactionTemplate(transactionManager);
                revocation.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                revocation.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
                revocation.executeWithoutResult(status -> jdbc.update(
                        "UPDATE workspace_member SET role_id=? WHERE workspace_id=? AND user_id=?",
                        revoked, workspace.getId(), currentUser.getId()));
            }
            return invocation.callRealMethod();
        }).when(decisionTransactions).decideOne(anyInt(), anyString(), any(), any());
        JsonNode response = body(send(browser, "POST", path(fixture), json.writeValueAsString(request(fixture, "approve",
                fixture.selected().getFirst(), fixture.selected().getLast())), workspace.getId()), 200);
        assertTrue(response.path("contentWithheld").asBoolean());
        assertTrue(response.path("items").isEmpty());
        authenticateAs(currentUser, workspace.getId());
        assertEquals("Prepared industry", jdbc.queryForObject("SELECT industry FROM company WHERE workspace_id=? AND id=?",
                String.class, workspace.getId(), primary.getId()));
        assertEquals("executed", chatMapper.getMissionToolCallForMaintenance(workspace.getId(), fixture.runId(), fixture.selected().getFirst().getId()).getStatus());
        assertEquals("proposed", chatMapper.getMissionToolCallForMaintenance(workspace.getId(), fixture.runId(), fixture.selected().getLast().getId()).getStatus());
    }

    @Test
    void expiredReviewDisarmsOutputAndMyWorkWithoutSweeper() throws Exception {
        var fixture = frozen("assign_owner");
        clock.set(runs.getRun(workspace.getId(), fixture.runId()).getReviewExpiresAt().toInstant(ZoneOffset.UTC));
        Browser browser = login(currentUser);
        JsonNode output = body(send(browser, "GET", "/api/ai/assistant/missions/" + fixture.runId() + "/output", null, workspace.getId()), 200);
        assertEquals("expired", output.path("proposalSet").path("items").get(0).path("card").path("reviewState").asString());
        assertEquals(0, output.path("proposalSet").path("derivedCounts").path("pending").asInt());
        JsonNode queue = body(send(browser, "GET", "/api/my-work", null, workspace.getId()), 200);
        assertFalse(queue.toString().contains("ai_mission:" + fixture.runId()));
    }

    @Test
    void reloadFindsExactOutputAfterMoreThanOneHundredHistoricalCalls() throws Exception {
        var fixture = frozen("assign_owner");
        for (int index = 0; index < 110; index++) {
            jdbc.update("INSERT INTO ai_chat_tool_call (workspace_id,message_id,tool_name,status,arguments_json,idempotency_key) VALUES (?,?,'get_record','executed','{}',?)",
                    workspace.getId(), fixture.policy().turn().userMessageId(), "http-history-" + fixture.runId() + "-" + index);
        }
        Browser browser = login(currentUser);
        JsonNode output = body(send(browser, "GET", "/api/ai/assistant/missions/" + fixture.runId() + "/output", null, workspace.getId()), 200);
        assertEquals(1, output.path("proposalSet").path("items").size());
        assertEquals(fixture.selected().getFirst().getActionSlot(), output.path("proposalSet").path("items").get(0).path("actionSlot").asString());
    }

    @Test
    void discardAfterTargetDeletionRequiresOnlySessionAndAiUse() throws Exception {
        var fixture = frozen("assign_owner");
        Browser browser = login(currentUser);
        jdbc.update("DELETE FROM company WHERE workspace_id=? AND id=?", workspace.getId(), primary.getId());
        JsonNode response = body(send(browser, "POST", path(fixture), json.writeValueAsString(request(fixture, "discard", fixture.selected().getFirst())), workspace.getId()), 200);
        assertTrue(response.path("contentWithheld").asBoolean());
        assertTrue(response.path("items").isEmpty());
        authenticateAs(currentUser, workspace.getId());
        assertEquals("rejected", chatMapper.getMissionToolCallForMaintenance(workspace.getId(), fixture.runId(), fixture.selected().getFirst().getId()).getStatus());
    }

    private Browser login(User user) throws Exception {
        clearAuthentication();
        HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT)
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        var login = client.send(HttpRequest.newBuilder(uri("/api/auth/login")).timeout(TIMEOUT)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(
                        Map.of("username", user.getUsername(), "password", PASSWORD)))).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, login.statusCode(), login.body());
        var csrf = client.send(HttpRequest.newBuilder(uri("/api/auth/csrf")).timeout(TIMEOUT).GET().build(), HttpResponse.BodyHandlers.ofString());
        CsrfBootstrapDto token = json.readValue(csrf.body(), CsrfBootstrapDto.class);
        assertNotNull(token);
        Browser browser = new Browser(user, client, token);
        browsers.add(browser);
        authenticateAs(currentUser, workspace.getId());
        return browser;
    }

    private HttpResponse<String> send(Browser browser, String method, String path, String content, int workspaceId) throws Exception {
        return browser.client().send(HttpRequest.newBuilder(uri(path)).timeout(TIMEOUT)
                .header("Content-Type", "application/json").header("X-Workspace-Id", Integer.toString(workspaceId))
                .header(browser.csrf().headerName(), browser.csrf().token())
                .method(method, content == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(content)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode body(HttpResponse<String> response, int expected) {
        assertEquals(expected, response.statusCode(), response.body());
        return json.readTree(response.body());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private static String path(Fixture fixture) {
        return "/api/ai/assistant/missions/" + fixture.runId() + "/proposal-decisions";
    }

    private record Browser(User user, HttpClient client, CsrfBootstrapDto csrf) {}
}
