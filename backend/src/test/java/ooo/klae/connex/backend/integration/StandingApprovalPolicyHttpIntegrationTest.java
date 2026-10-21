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
import ooo.klae.connex.backend.mappers.AiStandingApprovalPolicyMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.OrgMemberMapper;
import ooo.klae.connex.backend.mappers.RoleMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.services.SessionSecurityService;
import ooo.klae.connex.backend.support.AuthenticatedSessions;
import ooo.klae.connex.backend.support.TestEntityFixtures;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Real HTTP and persisted sessions with application CSRF, routing and organization authorization. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"server.address=127.0.0.1", "server.servlet.context-path=",
                "connex.ai.standing-approvals.enabled=false"})
class StandingApprovalPolicyHttpIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired UserMapper users;
    @Autowired OrganizationMapper organizations;
    @Autowired WorkspaceMapper workspaces;
    @Autowired OrgMemberMapper members;
    @Autowired RoleMapper roles;
    @Autowired AiStandingApprovalPolicyMapper policies;
    @Autowired SessionRepository<? extends Session> sessions;
    @Autowired CookieSerializer cookies;
    @Autowired ObjectMapper json;
    @LocalServerPort int port;
    private final List<String> sessionIds = new ArrayList<>();
    private final List<Integer> userIds = new ArrayList<>();
    private Organization organization;
    private Workspace workspace;
    private String administrator;
    private String member;
    private String outsider;
    private Organization otherOrganization;
    private Workspace otherWorkspace;

    @BeforeEach
    void fixtures() {
        String suffix = UUID.randomUUID().toString();
        organization = new Organization();
        organization.setName("Standing policy HTTP");
        organization.setSlug("standing-policy-" + suffix);
        organizations.insert(organization);
        workspace = new Workspace();
        workspace.setOrgId(organization.getId());
        workspace.setName("Standing policy HTTP");
        workspace.setSlug("standing-policy-" + suffix);
        workspaces.insert(workspace);
        TestEntityFixtures.seedExistingWorkspacePrivacy(jdbc, workspace.getId());
        var admin = account("standing-admin");
        var ordinary = account("standing-member");
        var foreign = account("standing-outsider");
        workspaces.addMember(workspace.getId(), admin.getId(), "member");
        workspaces.addMember(workspace.getId(), ordinary.getId(), "member");
        members.addMember(organization.getId(), admin.getId(), "admin");
        var narrow = new ooo.klae.connex.backend.beans.WorkspaceRole();
        narrow.setWorkspaceId(workspace.getId());
        narrow.setName("No CRM permissions");
        roles.insertRole(narrow);
        workspaces.setMemberCustomRole(workspace.getId(), admin.getId(), narrow.getId());
        otherOrganization = new Organization();
        otherOrganization.setName("Other standing policy HTTP");
        otherOrganization.setSlug("other-standing-" + suffix);
        organizations.insert(otherOrganization);
        otherWorkspace = new Workspace();
        otherWorkspace.setOrgId(otherOrganization.getId());
        otherWorkspace.setName("Other standing policy HTTP");
        otherWorkspace.setSlug("other-standing-" + suffix);
        workspaces.insert(otherWorkspace);
        TestEntityFixtures.seedExistingWorkspacePrivacy(jdbc, otherWorkspace.getId());
        workspaces.addMember(otherWorkspace.getId(), foreign.getId(), "owner");
        members.addMember(otherOrganization.getId(), foreign.getId(), "owner");
        policies.provision(otherOrganization.getId());
        administrator = session(sessions, admin);
        member = session(sessions, ordinary);
        outsider = session(sessions, foreign);
        policies.provision(organization.getId());
    }

    private User account(String prefix) {
        User user = AuthenticatedSessions.account(users, prefix);
        userIds.add(user.getId());
        jdbc.update("UPDATE app_user SET email_verified=TRUE WHERE id=?", user.getId());
        return users.getUserById(user.getId());
    }

    private <S extends Session> String session(SessionRepository<S> repository, User user) {
        S session = repository.createSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities())));
        session.setAttribute(SessionSecurityService.SESSION_EPOCH_ATTR, user.getSessionEpoch());
        session.setAttribute(SessionSecurityService.AUTHENTICATED_AT_ATTR, System.currentTimeMillis());
        session.setAttribute(SessionSecurityService.AUTHENTICATED_USER_ATTR, user.getId());
        repository.save(session);
        sessionIds.add(session.getId());
        return session.getId();
    }

    @AfterEach
    void cleanup() {
        sessionIds.forEach(sessions::deleteById);
        if (workspace != null) {
            TestEntityFixtures.deleteChatPrivacy(jdbc, workspace.getId());
            jdbc.update("DELETE FROM workspace WHERE id=?", workspace.getId());
        }
        if (organization != null) {
            TestEntityFixtures.deleteOrganizationPrivacy(jdbc, organization.getId());
            jdbc.update("DELETE FROM organization WHERE id=?", organization.getId());
        }
        if (otherWorkspace != null) {
            TestEntityFixtures.deleteChatPrivacy(jdbc, otherWorkspace.getId());
            jdbc.update("DELETE FROM workspace WHERE id=?", otherWorkspace.getId());
        }
        if (otherOrganization != null) {
            TestEntityFixtures.deleteOrganizationPrivacy(jdbc, otherOrganization.getId());
            jdbc.update("DELETE FROM organization WHERE id=?", otherOrganization.getId());
        }
        userIds.forEach(id -> jdbc.update("DELETE FROM app_user WHERE id=?", id));
    }

    @Test
    void narrowOrganizationAdministratorCanReadAndKillWithAuthoringDisabled() throws Exception {
        var read = request("GET", administrator, null, false, workspace.getId());
        assertEquals(200, read.statusCode(), read.body());
        JsonNode before = json.readTree(read.body());
        assertFalse(before.path("enabled").asBoolean());
        assertFalse(before.path("authoringEnabled").asBoolean());
        var killed = request("PUT", administrator, replacement(before.path("stateVersion").asString()), true, workspace.getId());
        assertEquals(200, killed.statusCode(), killed.body());
        JsonNode after = json.readTree(killed.body());
        assertEquals(Long.parseLong(before.path("killEpoch").asString()) + 1,
                Long.parseLong(after.path("killEpoch").asString()));
        assertEquals(409, request("PUT", administrator, replacement(before.path("stateVersion").asString()), true,
                workspace.getId()).statusCode());
    }

    @Test
    void csrfOrdinaryMembersOutsidersAndWorkspaceSubstitutionAreRefused() throws Exception {
        assertEquals(403, request("PUT", administrator, replacement("1"), false, workspace.getId()).statusCode());
        assertEquals(403, request("GET", member, null, false, workspace.getId()).statusCode());
        assertEquals(403, request("PUT", member, replacement("1"), true, workspace.getId()).statusCode());
        assertTrue(List.of(403, 409).contains(request("GET", outsider, null, false, workspace.getId()).statusCode()));
        assertTrue(List.of(403, 409).contains(request("GET", administrator, null, false, 2147483646).statusCode()));
        var own = request("GET", outsider, null, false, otherWorkspace.getId());
        assertEquals(200, own.statusCode(), own.body());
        assertTrue(List.of(403, 409).contains(request("PUT", outsider, replacement("1"), true,
                workspace.getId()).statusCode()));
        assertEquals(1L, policies.get(organization.getId()).getStateVersion());
        assertEquals(1L, policies.get(otherOrganization.getId()).getStateVersion());
    }

    private String replacement(String version) {
        return "{\"enabled\":false,\"allowedTools\":[],\"maximumDailyCap\":100,\"maximumPerRunCap\":10,\"expectedStateVersion\":\"" + version + "\"}";
    }

    private HttpResponse<String> request(String method, String session, String body, boolean csrf, int workspaceId) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        cookies.writeCookieValue(new CookieSerializer.CookieValue(new MockHttpServletRequest(), response, session));
        String cookie = response.getHeader("Set-Cookie").split(";", 2)[0];
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                + "/api/ai/standing-approval-policy?workspaceId=" + workspaceId))
                .timeout(Duration.ofSeconds(45)).header("Cookie", cookie)
                .header("X-Workspace-Id", Integer.toString(workspaceId));
        try (HttpClient client = HttpClient.newHttpClient()) {
            if (csrf) {
                var bootstrap = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/auth/csrf"))
                        .header("Cookie", cookie).GET().build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, bootstrap.statusCode(), bootstrap.body());
                JsonNode token = json.readTree(bootstrap.body());
                builder.header(token.path("headerName").asString(), token.path("token").asString());
            }
            if (body != null) builder.header("Content-Type", "application/json");
            builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
