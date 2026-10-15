package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.config.PrivilegedMfaProperties;
import ooo.klae.connex.backend.mappers.OrgMemberMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.services.SessionSecurityService;
import ooo.klae.connex.backend.support.AuthenticatedSessions;
import ooo.klae.connex.backend.support.OwnershipOfferTestFixtures;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Real HTTP contracts for self metadata, authority-scoped badges and every domain exit refusal. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"server.address=127.0.0.1", "server.servlet.context-path="})
class PasskeyApprovalMetadataHttpIntegrationTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserMapper users;
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
    private String attestationEnforced;

    @BeforeEach void fixtures() {
        attestationEnforced = mfa.getAttestationEnforced();
        mfa.setAttestationEnforced("false");
        String suffix = UUID.randomUUID().toString();
        org = new Organization(); org.setName("Metadata HTTP"); org.setSlug("metadata-http-" + suffix); organizations.insert(org);
        workspace = new Workspace(); workspace.setOrgId(org.getId()); workspace.setName("Metadata workspace");
        workspace.setSlug("metadata-http-" + suffix); workspaces.insert(workspace);
        owner = account("metadata-owner"); target = account("metadata-target");
        workspaces.addMember(workspace.getId(), owner.getId(), "owner");
        workspaces.addMember(workspace.getId(), target.getId(), "admin");
        members.addMember(org.getId(), owner.getId(), "owner");
        int credential = OwnershipOfferTestFixtures.passkey(jdbc, owner);
        jdbc.update("INSERT INTO privileged_credential_attestation (credential_row_id, org_id, source) VALUES (?, ?, 'GRANDFATHERED')",
            credential, org.getId());
        ownerSession = session(owner); targetSession = session(target);
    }

    @AfterEach void cleanup() {
        mfa.setAttestationEnforced(attestationEnforced);
        sessionIds.forEach(sessions::deleteById);
        jdbc.update("DELETE FROM workspace WHERE id = ?", workspace.getId());
        jdbc.update("DELETE FROM organization WHERE id = ?", org.getId());
        userIds.forEach(id -> jdbc.update("DELETE FROM app_user WHERE id = ?", id));
    }

    @Test void requirementsAreAuthenticatedSelfOnlyAndAvailableWhileConfined() throws Exception {
        mfa.setAttestationEnforced("true");
        var response = request("GET", "/api/auth/webauthn/register/requirements?userId=" + owner.getId(), targetSession);
        assertEquals(200, response.statusCode(), response.body());
        JsonNode result = json.readTree(response.body());
        assertEquals("ENROLLMENT_REQUIRED", result.path("standing").asString());
        JsonNode membership = result.path("privilegedMemberships").get(0);
        assertEquals(1, result.path("privilegedMemberships").size());
        assertEquals(org.getId(), membership.path("orgId").asInt());
        assertEquals(org.getName(), membership.path("orgName").asString());
        assertTrue(membership.has("orgRole")); assertTrue(membership.path("orgRole").isNull());
        assertEquals(workspace.getId(), membership.path("workspaces").get(0).path("workspaceId").asInt());
        assertEquals(workspace.getName(), membership.path("workspaces").get(0).path("workspaceName").asString());
        assertEquals(401, request("GET", "/api/auth/webauthn/register/requirements", null).statusCode());
        workspaces.updateMemberRole(workspace.getId(), target.getId(), "member");
        result = json.readTree(request("GET", "/api/auth/webauthn/register/requirements", targetSession).body());
        assertEquals(0, result.path("privilegedMemberships").size());
    }

    @Test void bothMemberEndpointsSerializeBadgesOnlyForAuthorizedCallersBeforeStepUp() throws Exception {
        OwnershipOfferTestFixtures.passkey(jdbc, target);
        members.addMember(org.getId(), target.getId(), "admin");
        for (String path : List.of(workspaceMembers(), orgMembers())) {
            var response = request("GET", path, ownerSession);
            assertEquals(200, response.statusCode(), response.body());
            assertTrue(row(json.readTree(response.body()), target.getId()).path("passkeyApprovalNeeded").asBoolean());
            response = request("GET", path, targetSession);
            assertEquals(200, response.statusCode(), response.body());
            for (JsonNode member : json.readTree(response.body())) { assertFalse(member.has("passkeyApprovalNeeded")); }
            assertEquals(401, request("GET", path, null).statusCode());
        }
        int credential = OwnershipOfferTestFixtures.passkey(jdbc, target);
        jdbc.update("UPDATE webauthn_credential SET privileged_assurance = 'BREAK_GLASS', privileged_assured_at = UTC_TIMESTAMP(6) WHERE id = ?", credential);
        for (String path : List.of(workspaceMembers(), orgMembers())) {
            JsonNode member = row(json.readTree(request("GET", path, ownerSession).body()), target.getId());
            assertTrue(member.has("passkeyApprovalNeeded")); assertFalse(member.path("passkeyApprovalNeeded").asBoolean());
        }
    }

    @Test void anotherTenantCannotReadEitherMemberList() throws Exception {
        User outsider = account("metadata-outsider");
        String outsiderSession = session(outsider);
        assertEquals(403, request("GET", workspaceMembers(), outsiderSession).statusCode());
        assertEquals(403, request("GET", orgMembers(), outsiderSession).statusCode());
    }

    @Test void lastWorkspaceOwnerRetainsStatusMessageAndGetsStableCode() throws Exception {
        assertRefusal(workspaceLeave(), ownerSession, 400, "WORKSPACE_LAST_OWNER",
            "Transfer ownership before leaving; a workspace must keep an owner");
        assertEquals("owner", workspaces.getRole(workspace.getId(), owner.getId()));
    }

    @Test void lastAuthorityRestorerRetainsStatusMessageAndGetsStableCode() throws Exception {
        workspaces.updateMemberRole(workspace.getId(), target.getId(), "owner");
        jdbc.update("INSERT INTO workspace_role (workspace_id, name) VALUES (?, 'Narrow owner')", workspace.getId());
        Integer role = jdbc.queryForObject("SELECT id FROM workspace_role WHERE workspace_id = ?", Integer.class, workspace.getId());
        jdbc.update("UPDATE workspace_member SET role_id = ? WHERE workspace_id = ? AND user_id = ?", role, workspace.getId(), target.getId());
        assertRefusal(workspaceLeave(), ownerSession, 400, "WORKSPACE_RECOVERY_OWNER_REQUIRED",
            "A workspace must keep an owner who can manage its members and roles; "
                + "assign the built-in owner role to at least one owner first");
    }

    @Test void soleNamedApproverRetainsStatusMessageAndGetsStableCode() throws Exception {
        jdbc.update("INSERT INTO approval_policy (workspace_id, name, active) VALUES (?, 'Metadata policy', TRUE)", workspace.getId());
        Integer policy = jdbc.queryForObject("SELECT id FROM approval_policy WHERE workspace_id = ?", Integer.class, workspace.getId());
        jdbc.update("INSERT INTO approval_policy_step (workspace_id, policy_id, step_order, required_count) VALUES (?, ?, 1, 1)", workspace.getId(), policy);
        jdbc.update("INSERT INTO approval_policy_step_approver (workspace_id, step_id, approver_kind, user_id) "
            + "SELECT workspace_id, id, 'user', ? FROM approval_policy_step WHERE policy_id = ?", target.getId(), policy);
        assertRefusal(workspaceLeave(), targetSession, 409, "SOLE_NAMED_DOCUMENT_APPROVER",
            "Member is the sole named approver on an active policy or pending approval; reassign first");
    }

    @Test void orgNonMemberRetains404AndGetsStableCode() throws Exception {
        assertRefusal(orgLeave(), targetSession, 404, "ORG_NOT_MEMBER", "You are not a member of this organization");
    }

    @Test void soleOrgOwnerRetainsStatusMessageAndGetsStableCode() throws Exception {
        assertRefusal(orgLeave(), ownerSession, 400, "ORG_LAST_OWNER", "An organization must keep at least one owner");
    }

    @Test void bothSuccessfulExitsWorkWhileConfinedWithoutStepUp() throws Exception {
        members.addMember(org.getId(), target.getId(), "admin");
        mfa.setAttestationEnforced("true");
        var leftWorkspace = request("POST", workspaceLeave(), targetSession);
        assertEquals(200, leftWorkspace.statusCode(), leftWorkspace.body());
        assertTrue(json.readTree(leftWorkspace.body()).path("activeWorkspaceId").isNull());
        assertNull(workspaces.getRole(workspace.getId(), target.getId()));
        var requirements = json.readTree(request("GET", "/api/auth/webauthn/register/requirements", targetSession).body());
        assertEquals("admin", requirements.path("privilegedMemberships").get(0).path("orgRole").asString());
        assertEquals(0, requirements.path("privilegedMemberships").get(0).path("workspaces").size());
        assertEquals(204, request("POST", orgLeave(), targetSession).statusCode());
        assertNull(members.getRole(org.getId(), target.getId()));
        requirements = json.readTree(request("GET", "/api/auth/webauthn/register/requirements", targetSession).body());
        assertEquals("UNPRIVILEGED", requirements.path("standing").asString());
        assertEquals(0, requirements.path("privilegedMemberships").size());
    }

    private void assertRefusal(String path, String session, int status, String code, String message) throws Exception {
        var response = request("POST", path, session);
        assertEquals(status, response.statusCode(), response.body());
        JsonNode result = json.readTree(response.body());
        assertEquals(code, result.path("code").asString()); assertEquals(message, result.path("message").asString());
    }

    private JsonNode row(JsonNode rows, int id) {
        for (JsonNode row : rows) { if (row.path("id").asInt() == id) { return row; } }
        fail("Missing member " + id); return null;
    }

    private String workspaceMembers() { return "/api/workspaces/" + workspace.getId() + "/members"; }
    private String orgMembers() { return "/api/orgs/" + org.getId() + "/members"; }
    private String workspaceLeave() { return "/api/workspaces/" + workspace.getId() + "/leave"; }
    private String orgLeave() { return "/api/orgs/" + org.getId() + "/leave"; }
    private User account(String prefix) { User user = AuthenticatedSessions.account(users, prefix); userIds.add(user.getId()); return user; }
    private String session(User user) { return session(sessions, user); }
    private <S extends Session> String session(SessionRepository<S> repository, User user) {
        S session = repository.createSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
            new SecurityContextImpl(new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities())));
        session.setAttribute(SessionSecurityService.AUTHENTICATED_AT_ATTR, System.currentTimeMillis());
        session.setAttribute(SessionSecurityService.AUTHENTICATED_USER_ATTR, user.getId());
        session.setAttribute(SessionSecurityService.SESSION_EPOCH_ATTR, user.getSessionEpoch());
        repository.save(session); sessionIds.add(session.getId()); return session.getId();
    }

    private HttpResponse<String> request(String method, String path, String session) throws Exception {
        if (method.equals("GET")) { return raw(method, path, session, null, null); }
        var bootstrap = raw("GET", "/api/auth/csrf", session, null, null);
        assertEquals(200, bootstrap.statusCode(), bootstrap.body());
        JsonNode csrf = json.readTree(bootstrap.body());
        return raw(method, path, session, csrf.path("headerName").asString(), csrf.path("token").asString());
    }

    private HttpResponse<String> raw(String method, String path, String session, String header, String token) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(45))
            .header("X-Workspace-Id", Integer.toString(workspace.getId()));
        if (session != null) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            cookies.writeCookieValue(new CookieSerializer.CookieValue(new MockHttpServletRequest(), response, session));
            builder.header("Cookie", response.getHeader("Set-Cookie").split(";", 2)[0]);
        }
        if (header != null) { builder.header(header, token); }
        builder.method(method, HttpRequest.BodyPublishers.noBody());
        try (HttpClient client = HttpClient.newHttpClient()) { return client.send(builder.build(), HttpResponse.BodyHandlers.ofString()); }
    }
}
