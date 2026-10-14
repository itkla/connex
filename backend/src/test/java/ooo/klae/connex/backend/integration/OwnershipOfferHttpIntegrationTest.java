package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doAnswer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.config.PrivilegedMfaProperties;
import ooo.klae.connex.backend.mappers.OrgMemberMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.services.SessionSecurityService;
import ooo.klae.connex.backend.services.PrivilegedAccountService;
import ooo.klae.connex.backend.support.AuthenticatedSessions;
import ooo.klae.connex.backend.support.OwnershipOfferTestFixtures;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Exercises the real HTTP, persisted-session, CSRF, scope and response contracts. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"server.address=127.0.0.1", "server.servlet.context-path="})
class OwnershipOfferHttpIntegrationTest {
    @Autowired private JdbcTemplate jdbc;
    @MockitoSpyBean private UserMapper users;
    @MockitoSpyBean private PrivilegedAccountService privilegedAccountService;
    @Autowired private WorkspaceMapper workspaces;
    @Autowired private OrganizationMapper organizations;
    @Autowired private OrgMemberMapper members;
    @Autowired private SessionRepository<? extends Session> sessions;
    @Autowired private CookieSerializer cookies;
    @Autowired private ObjectMapper json;
    @Autowired private PrivilegedMfaProperties mfa;
    @LocalServerPort private int port;
    private final List<String> sessionIds = new ArrayList<>();
    private final List<Integer> userIds = new ArrayList<>();
    private Organization org;
    private Workspace workspace;
    private User owner;
    private User target;
    private String ownerSession;
    private String targetSession;

    @BeforeEach void fixtures() {
        mfa.setAttestationEnforced("false");
        String suffix = UUID.randomUUID().toString();
        org = new Organization(); org.setName("Ownership organization HTTP"); org.setSlug("ownership-http-" + suffix); organizations.insert(org);
        workspace = new Workspace(); workspace.setOrgId(org.getId()); workspace.setName("Ownership workspace HTTP"); workspace.setSlug("ownership-http-" + suffix); workspaces.insert(workspace);
        owner = account("ownership-owner"); target = account("ownership-offeree");
        workspaces.addMember(workspace.getId(), owner.getId(), "owner");
        workspaces.addMember(workspace.getId(), target.getId(), "member");
        members.addMember(org.getId(), owner.getId(), "owner");
        ownerSession = session(owner, true); targetSession = session(target, true);
    }

    @AfterEach void cleanup() {
        mfa.setAttestationEnforced("false");
        sessionIds.forEach(sessions::deleteById);
        jdbc.update("DELETE FROM workspace WHERE id = ?", workspace.getId());
        jdbc.update("DELETE FROM organization WHERE id = ?", org.getId());
        userIds.forEach(id -> jdbc.update("DELETE FROM app_user WHERE id = ?", id));
    }

    @Test void workspaceConsentHas202AndAcceptanceIsSelfOnlyAndIdempotent() throws Exception {
        var response = request("PATCH", "/api/workspaces/" + workspace.getId() + "/members/" + target.getId(), ownerSession, "{\"role\":\"owner\"}", false);
        assertEquals(202, response.statusCode(), response.body());
        JsonNode offered = json.readTree(response.body());
        assertEquals("ownership_offer", offered.path("outcome").asString());
        assertEquals("member", offered.path("currentMember").path("builtInRole").asString());
        assertTrue(offered.path("offer").path("id").isString());
        assertLabels(offered.path("offer"), workspace.getName());
        String id = offered.path("offer").path("id").asString();
        assertEquals("member", workspaces.getRole(workspace.getId(), target.getId()));
        assertEquals(404, request("POST", "/api/ownership-offers/" + id + "/accept", ownerSession, "{}", true).statusCode());
        var self = request("GET", "/api/ownership-offers", targetSession, null, true);
        assertEquals(200, self.statusCode(), self.body()); assertEquals(id, json.readTree(self.body()).get(0).path("id").asString());
        assertOffereeLabels(json.readTree(self.body()).get(0), workspace.getName(), self.body());
        var accepted = request("POST", "/api/ownership-offers/" + id + "/accept", targetSession, "{}", true);
        assertEquals(200, accepted.statusCode(), accepted.body());
        JsonNode result = json.readTree(accepted.body());
        assertEquals("ACCEPTED", result.path("offer").path("state").asString());
        assertOffereeLabels(result.path("offer"), workspace.getName(), accepted.body());
        assertEquals("owner", result.path("membership").path("builtInRole").asString());
        assertFalse(result.path("standing").isMissingNode()); assertFalse(result.path("enforcedStanding").isMissingNode());
        assertEquals(200, request("POST", "/api/ownership-offers/" + id + "/accept", targetSession, "{}", true).statusCode());
        assertEquals(200, request("PATCH", "/api/workspaces/" + workspace.getId() + "/members/" + target.getId(), ownerSession, "{\"role\":\"owner\"}", false).statusCode());
        assertEquals(0, json.readTree(request("GET", "/api/ownership-offers", targetSession, null, true).body()).size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"WORKSPACE", "ORGANIZATION"})
    void postCommitStandingFailureDoesNotRefuseOrRepeatTheCommittedGrant(String scope) throws Exception {
        boolean workspaceOffer = "WORKSPACE".equals(scope);
        var offered = workspaceOffer
            ? request("PATCH", "/api/workspaces/" + workspace.getId() + "/members/" + target.getId(),
                ownerSession, "{\"role\":\"owner\"}", false)
            : request("PUT", "/api/orgs/" + org.getId() + "/members/" + target.getId(),
                ownerSession, "{\"orgRole\":\"owner\"}", false);
        assertEquals(202, offered.statusCode(), offered.body());
        String id = json.readTree(offered.body()).path("offer").path("id").asString();
        AtomicBoolean failRefresh = new AtomicBoolean(true);
        doAnswer(invocation -> {
            String role = workspaceOffer ? workspaces.getRole(workspace.getId(), target.getId())
                : members.getRole(org.getId(), target.getId());
            if (!TransactionSynchronizationManager.isActualTransactionActive()
                    && "owner".equals(role) && failRefresh.compareAndSet(true, false)) {
                throw new RuntimeException("Post-commit standing read failed");
            }
            return invocation.callRealMethod();
        }).when(privilegedAccountService).standing(target.getId());

        var failed = request("POST", "/api/ownership-offers/" + id + "/accept", targetSession, "{}", true);
        assertEquals(500, failed.statusCode(), failed.body());
        assertFalse(failRefresh.get());
        assertEquals("owner", workspaceOffer ? workspaces.getRole(workspace.getId(), target.getId())
            : members.getRole(org.getId(), target.getId()));
        assertEquals("ACCEPTED", jdbc.queryForObject(
            "SELECT state FROM ownership_offer WHERE id = ?", String.class, id));
        assertEquals(0, consentAudits("ownership.offer.refused"));
        assertEquals(1, consentAudits("ownership.offer.accepted"));
        JsonNode committedMember = workspaceOffer ? json.valueToTree(workspaces.getMember(workspace.getId(), target.getId()))
            : json.readTree(request("GET", "/api/orgs/" + org.getId() + "/members", ownerSession, null, false).body())
                .valueStream().filter(member -> member.path("id").asInt() == target.getId()).findFirst().orElseThrow();

        var retried = request("POST", "/api/ownership-offers/" + id + "/accept", targetSession, "{}", true);
        assertEquals(200, retried.statusCode(), retried.body());
        JsonNode accepted = json.readTree(retried.body());
        assertEquals(committedMember, accepted.path("membership"));
        assertEquals("ACCEPTED", accepted.path("offer").path("state").asString());
        assertEquals(privilegedAccountService.standing(target.getId()).name(), accepted.path("standing").asString());
        assertEquals(privilegedAccountService.enforcedStanding(target.getId()).name(), accepted.path("enforcedStanding").asString());
        assertEquals(0, consentAudits("ownership.offer.refused"));
        assertEquals(1, consentAudits("ownership.offer.accepted"));
        assertEquals(1, consentAudits(workspaceOffer ? "workspace.member.role" : "org.member.set"));
    }

    private int consentAudits(String action) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE org_id = ? AND entity_id = ? AND action = ?",
            Integer.class, org.getId(), target.getId(), action);
    }

    @Test void byEmailOfferConflictAuditsTheResolvedOffereeRatherThanTheActor() throws Exception {
        User otherOwner = account("ownership-other-owner");
        members.addMember(org.getId(), otherOwner.getId(), "owner");
        workspaces.addMember(workspace.getId(), otherOwner.getId(), "member");
        String otherSession = session(otherOwner, true);
        var created = request("PUT", "/api/orgs/" + org.getId() + "/members/" + target.getId(), otherSession,
            "{\"orgRole\":\"owner\"}", false);
        assertEquals(202, created.statusCode(), created.body());
        String id = json.readTree(created.body()).path("offer").path("id").asString();

        var refused = request("POST", "/api/orgs/" + org.getId() + "/members", ownerSession,
            "{\"email\":\"" + target.getEmail() + "\",\"orgRole\":\"owner\"}", false);
        assertEquals(409, refused.statusCode(), refused.body());
        assertEquals(1, jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE org_id = ? AND action = 'ownership.offer.refused'",
            Integer.class, org.getId()));
        assertEquals(target.getId(), jdbc.queryForObject(
            "SELECT entity_id FROM audit_log WHERE org_id = ? AND action = 'ownership.offer.refused'",
            Integer.class, org.getId()));
        assertEquals("user", jdbc.queryForObject(
            "SELECT entity_type FROM audit_log WHERE org_id = ? AND action = 'ownership.offer.refused'",
            String.class, org.getId()));
        assertEquals(owner.getId(), jdbc.queryForObject(
            "SELECT actor_id FROM audit_log WHERE org_id = ? AND action = 'ownership.offer.refused'",
            Integer.class, org.getId()));
        assertEquals(otherOwner.getId(), jdbc.queryForObject(
            "SELECT offeror_user_id FROM ownership_offer WHERE id = ? AND state = 'OPEN'", Integer.class, id));
        assertNull(members.getRole(org.getId(), target.getId()));
    }

    @Test void byEmailFailureBeforeResolutionAuditsNoSubject() throws Exception {
        String unknown = "ownership-unknown-" + UUID.randomUUID() + "@example.invalid";
        var refused = request("POST", "/api/orgs/" + org.getId() + "/members", ownerSession,
            "{\"email\":\"" + unknown + "\",\"orgRole\":\"owner\"}", false);
        assertEquals(400, refused.statusCode(), refused.body());
        assertEquals(1, jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE org_id = ? AND action = 'ownership.offer.refused'",
            Integer.class, org.getId()));
        assertNull(jdbc.queryForObject(
            "SELECT entity_id FROM audit_log WHERE org_id = ? AND action = 'ownership.offer.refused'",
            Integer.class, org.getId()));
        assertNull(jdbc.queryForObject(
            "SELECT target_label FROM audit_log WHERE org_id = ? AND action = 'ownership.offer.refused'",
            String.class, org.getId()));
        assertEquals("user", jdbc.queryForObject(
            "SELECT entity_type FROM audit_log WHERE org_id = ? AND action = 'ownership.offer.refused'",
            String.class, org.getId()));
        assertEquals(owner.getId(), jdbc.queryForObject(
            "SELECT actor_id FROM audit_log WHERE org_id = ? AND action = 'ownership.offer.refused'",
            Integer.class, org.getId()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ownership_offer WHERE org_id = ?",
            Integer.class, org.getId()));
    }

    @Test void orgAbsentMembershipAndAdminChangesKeepTheirExactContracts() throws Exception {
        var direct = request("PUT", "/api/orgs/" + org.getId() + "/members/" + target.getId(), ownerSession, "{\"orgRole\":\"admin\"}", false);
        assertEquals(204, direct.statusCode(), direct.body());
        jdbc.update("DELETE FROM org_member WHERE org_id = ? AND user_id = ?", org.getId(), target.getId());
        var offered = request("POST", "/api/orgs/" + org.getId() + "/members", ownerSession,
                json.writeValueAsString(java.util.Map.of("email", target.getEmail(), "orgRole", "owner")), false);
        assertEquals(202, offered.statusCode(), offered.body());
        JsonNode body = json.readTree(offered.body()); assertTrue(body.path("currentMember").isNull());
        assertLabels(body.path("offer"), org.getName());
        assertNull(members.getRole(org.getId(), target.getId()));
        String id = body.path("offer").path("id").asString();
        var scope = request("GET", "/api/orgs/" + org.getId() + "/ownership-offers", ownerSession, null, false);
        assertEquals(200, scope.statusCode(), scope.body());
        assertLabels(json.readTree(scope.body()).get(0), org.getName());
        var self = request("GET", "/api/ownership-offers", targetSession, null, true);
        assertEquals(200, self.statusCode(), self.body());
        assertOffereeLabels(json.readTree(self.body()).get(0), org.getName(), self.body());
        var accepted = request("POST", "/api/ownership-offers/" + id + "/accept", targetSession, "{}", true);
        assertEquals(200, accepted.statusCode(), accepted.body());
        assertEquals("owner", json.readTree(accepted.body()).path("membership").path("orgRole").asString());
        assertOffereeLabels(json.readTree(accepted.body()).path("offer"), org.getName(), accepted.body());
        assertEquals(200, request("GET", "/api/orgs/" + org.getId() + "/ownership-offers", ownerSession, null, false).statusCode());
    }

    @Test void organizationPutOffersExposeOwnerLabelsAndRetainTheAdminMembership() throws Exception {
        members.addMember(org.getId(), target.getId(), "admin");
        var response = request("PUT", "/api/orgs/" + org.getId() + "/members/" + target.getId(), ownerSession,
                "{\"orgRole\":\"owner\"}", false);
        assertEquals(202, response.statusCode(), response.body());
        assertLabels(json.readTree(response.body()).path("offer"), org.getName());
        assertEquals("admin", json.readTree(response.body()).path("currentMember").path("orgRole").asString());
        assertEquals("admin", members.getRole(org.getId(), target.getId()));
    }

    @Test void absentDisplayNamesStayPresentAndDoNotFailCreationOrAcceptance() throws Exception {
        User ownerWithoutLabel = users.getUserById(owner.getId()); ownerWithoutLabel.setDisplayName(null);
        User targetWithoutLabel = users.getUserById(target.getId()); targetWithoutLabel.setDisplayName(null);
        doReturn(ownerWithoutLabel).when(users).getUserById(owner.getId());
        doReturn(targetWithoutLabel).when(users).getUserById(target.getId());
        var offered = request("PATCH", "/api/workspaces/" + workspace.getId() + "/members/" + target.getId(), ownerSession,
                "{\"role\":\"owner\"}", false);
        assertEquals(202, offered.statusCode(), offered.body());
        JsonNode offer = json.readTree(offered.body()).path("offer");
        assertMissingDisplayNames(offer);
        String id = offer.path("id").asString();
        var accepted = request("POST", "/api/ownership-offers/" + id + "/accept", targetSession, "{}", true);
        assertEquals(200, accepted.statusCode(), accepted.body());
        assertMissingDisplayNames(json.readTree(accepted.body()).path("offer"));
        assertEquals("owner", workspaces.getRole(workspace.getId(), target.getId()));
    }

    @Test void confinedOffereeCanListAndDeclineButCannotAcceptAndCsrfStillApplies() throws Exception {
        var offered = request("PATCH", "/api/workspaces/" + workspace.getId() + "/members/" + target.getId(), ownerSession, "{\"role\":\"owner\"}", false);
        assertEquals(202, offered.statusCode(), offered.body()); String id = json.readTree(offered.body()).path("offer").path("id").asString();
        workspaces.updateMemberRole(workspace.getId(), target.getId(), "admin");
        jdbc.update("DELETE c FROM webauthn_credential c JOIN webauthn_user_entity e ON e.id = c.user_entity_user_id WHERE e.user_id = ?", target.getId());
        String confinedSession = session(target, false);
        assertEquals(200, request("GET", "/api/ownership-offers", confinedSession, null, true).statusCode());
        assertEquals(403, request("POST", "/api/ownership-offers/" + id + "/accept", confinedSession, "{}", true).statusCode());
        assertEquals(403, raw("POST", "/api/ownership-offers/" + id + "/decline", confinedSession, "{}", true, null, null).statusCode());
        assertEquals(204, request("POST", "/api/ownership-offers/" + id + "/decline", confinedSession, null, true).statusCode());
        assertEquals(204, request("POST", "/api/ownership-offers/" + id + "/decline", confinedSession, null, true).statusCode());
    }

    @Test void revokeAndScopeListsHideInaccessibleIdsAndTerminalAcceptanceConflicts() throws Exception {
        var offered = request("PATCH", "/api/workspaces/" + workspace.getId() + "/members/" + target.getId(), ownerSession, "{\"role\":\"owner\"}", false);
        String id = json.readTree(offered.body()).path("offer").path("id").asString();
        var scope = request("GET", "/api/workspaces/" + workspace.getId() + "/ownership-offers", ownerSession, null, false);
        assertEquals(200, scope.statusCode(), scope.body());
        assertLabels(json.readTree(scope.body()).get(0), workspace.getName());
        assertEquals(404, request("GET", "/api/workspaces/" + workspace.getId() + "/ownership-offers", targetSession, null, false).statusCode());
        assertEquals(404, request("DELETE", "/api/workspaces/" + workspace.getId() + "/ownership-offers/" + id, targetSession, null, false).statusCode());
        assertEquals(204, request("DELETE", "/api/workspaces/" + workspace.getId() + "/ownership-offers/" + id, ownerSession, null, false).statusCode());
        assertEquals(204, request("DELETE", "/api/workspaces/" + workspace.getId() + "/ownership-offers/" + id, ownerSession, null, false).statusCode());
        assertEquals(409, request("POST", "/api/ownership-offers/" + id + "/accept", targetSession, "{}", true).statusCode());
    }

    @Test void organizationRevokeUsesTheAuthenticatedOwnerAndResolvesConsent() throws Exception {
        var offered = request("PUT", "/api/orgs/" + org.getId() + "/members/" + target.getId(),
            ownerSession, "{\"orgRole\":\"owner\"}", false);
        assertEquals(202, offered.statusCode(), offered.body());
        String id = json.readTree(offered.body()).path("offer").path("id").asString();
        String path = "/api/orgs/" + org.getId() + "/ownership-offers/" + id;
        assertEquals(404, request("DELETE", path, targetSession, null, false).statusCode());
        assertEquals(204, request("DELETE", path, ownerSession, null, false).statusCode());
        assertEquals(204, request("DELETE", path, ownerSession, null, false).statusCode());
        assertEquals("REVOKED", jdbc.queryForObject(
            "SELECT state FROM ownership_offer WHERE id = ?", String.class, id));
        assertNull(members.getRole(org.getId(), target.getId()));
        assertEquals(409, request("POST", "/api/ownership-offers/" + id + "/accept", targetSession, "{}", true).statusCode());
    }

    private void assertLabels(JsonNode offer, String scopeName) {
        assertEquals(scopeName, offer.path("scopeName").asString());
        assertEquals(owner.getDisplayName(), offer.path("offerorDisplayName").asString());
        assertEquals(target.getDisplayName(), offer.path("offereeDisplayName").asString());
        assertEquals(target.getEmail(), offer.path("offereeEmail").asString());
        assertTrue(offer.path("offerorEmail").isMissingNode());
    }

    private void assertOffereeLabels(JsonNode offer, String scopeName, String response) {
        assertLabels(offer, scopeName);
        assertFalse(response.contains(owner.getEmail()));
    }

    private void assertMissingDisplayNames(JsonNode offer) {
        assertEquals(workspace.getName(), offer.path("scopeName").asString());
        assertEquals(target.getEmail(), offer.path("offereeEmail").asString());
        for (String field : List.of("offerorDisplayName", "offereeDisplayName")) {
            assertFalse(offer.path(field).isMissingNode(), field);
            assertTrue(offer.path(field).isNull(), field);
        }
    }

    private User account(String prefix) { User user = AuthenticatedSessions.account(users, prefix); userIds.add(user.getId()); jdbc.update("UPDATE app_user SET email_verified = TRUE, display_name = ? WHERE id = ?", prefix + " Display", user.getId()); return users.getUserById(user.getId()); }

    private String session(User user, boolean fresh) { return session(sessions, user, fresh); }
    private <S extends Session> String session(SessionRepository<S> repository, User user, boolean fresh) {
        S session = repository.createSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities())));
        long now = System.currentTimeMillis();
        session.setAttribute(SessionSecurityService.AUTHENTICATED_AT_ATTR, now);
        session.setAttribute(SessionSecurityService.AUTHENTICATED_USER_ATTR, user.getId());
        session.setAttribute(SessionSecurityService.SESSION_EPOCH_ATTR, user.getSessionEpoch());
        if (fresh) {
            int credential = OwnershipOfferTestFixtures.passkey(jdbc, user);
            session.setAttribute(SessionSecurityService.WEBAUTHN_STEP_UP_AT_ATTR, now);
            session.setAttribute(SessionSecurityService.WEBAUTHN_STEP_UP_USER_ATTR, user.getId());
            session.setAttribute(SessionSecurityService.WEBAUTHN_STEP_UP_PROOF_ATTR, user.getId() + ":" + now + ":" + credential);
        }
        repository.save(session); sessionIds.add(session.getId()); return session.getId();
    }

    private HttpResponse<String> request(String method, String path, String session, String body, boolean staleWorkspace) throws Exception {
        if (method.equals("GET")) { return raw(method, path, session, body, staleWorkspace, null, null); }
        var bootstrap = raw("GET", "/api/auth/csrf", session, null, false, null, null);
        assertEquals(200, bootstrap.statusCode(), bootstrap.body()); JsonNode csrf = json.readTree(bootstrap.body());
        return raw(method, path, session, body, staleWorkspace, csrf.path("headerName").asString(), csrf.path("token").asString());
    }

    private HttpResponse<String> raw(String method, String path, String session, String body, boolean staleWorkspace, String header, String token) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        cookies.writeCookieValue(new CookieSerializer.CookieValue(new MockHttpServletRequest(), response, session));
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(45))
                .header("Cookie", response.getHeader("Set-Cookie").split(";", 2)[0])
                .header("X-Workspace-Id", staleWorkspace ? "2147483646" : Integer.toString(workspace.getId()));
        if (header != null && !header.isBlank()) { builder.header(header, token); }
        if (body != null) { builder.header("Content-Type", "application/json"); }
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        try (HttpClient client = HttpClient.newHttpClient()) { return client.send(builder.build(), HttpResponse.BodyHandlers.ofString()); }
    }
}
