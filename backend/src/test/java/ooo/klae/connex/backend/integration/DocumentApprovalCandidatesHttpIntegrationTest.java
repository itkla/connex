package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
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
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.services.SessionSecurityService;
import ooo.klae.connex.backend.support.AuthenticatedSessions;
import ooo.klae.connex.backend.support.OwnershipOfferTestFixtures;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Exercises management candidate authorization and tenant isolation over the real HTTP stack. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"server.address=127.0.0.1", "server.servlet.context-path="})
class DocumentApprovalCandidatesHttpIntegrationTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserMapper users;
    @Autowired private WorkspaceMapper workspaces;
    @Autowired private OrganizationMapper organizations;
    @Autowired private SessionRepository<? extends Session> sessions;
    @Autowired private CookieSerializer cookies;
    @Autowired private ObjectMapper json;
    @LocalServerPort private int port;

    private final List<String> sessionIds = new ArrayList<>();
    private final List<Integer> userIds = new ArrayList<>();
    private final List<Workspace> workspaceFixtures = new ArrayList<>();
    private final List<Organization> organizationFixtures = new ArrayList<>();
    private Workspace workspaceA;
    private Workspace workspaceB;
    private User managerA;
    private User managerB;
    private User approverA;
    private User approverB;
    private String managerSessionA;
    private String managerSessionB;
    private String approverSession;
    private Graph graphA;
    private Graph graphB;

    @BeforeEach
    void fixtures() {
        workspaceA = workspace();
        workspaceB = workspace();
        managerA = member(workspaceA, "DOCUMENT_MANAGE");
        managerB = member(workspaceB, "DOCUMENT_MANAGE");
        approverA = member(workspaceA, "DOCUMENT_APPROVE");
        approverB = member(workspaceB, "DOCUMENT_APPROVE");
        graphA = graph(workspaceA, managerA);
        graphB = graph(workspaceB, managerB);
        managerSessionA = session(sessions, managerA);
        managerSessionB = session(sessions, managerB);
        approverSession = session(sessions, approverA);
    }

    @AfterEach
    void cleanup() {
        sessionIds.forEach(sessions::deleteById);
        for (Workspace workspace : workspaceFixtures) {
            jdbc.update("DELETE FROM deal WHERE workspace_id = ?", workspace.getId());
            jdbc.update("DELETE FROM stage WHERE workspace_id = ?", workspace.getId());
            jdbc.update("DELETE FROM pipeline WHERE workspace_id = ?", workspace.getId());
            jdbc.update("DELETE FROM workspace WHERE id = ?", workspace.getId());
        }
        organizationFixtures.forEach(org -> jdbc.update("DELETE FROM organization WHERE id = ?", org.getId()));
        userIds.forEach(id -> jdbc.update("DELETE FROM app_user WHERE id = ?", id));
    }

    @Test
    void anonymousIsUnauthorizedAndApproveOnlyIsForbidden() throws Exception {
        assertEquals(401, request(graphA.path(), workspaceA, null).statusCode());
        var response = request(graphA.path(), workspaceA, approverSession);
        assertEquals(403, response.statusCode(), response.body());
    }

    @Test
    void manageOnlyCustomRoleGetsAnAllowlistedTenantScopedResponse() throws Exception {
        var response = request(graphA.path(), workspaceA, managerSessionA);
        assertEquals(200, response.statusCode(), response.body());
        JsonNode result = json.readTree(response.body());
        assertEquals(Set.of("candidates", "requiredCount", "approvedCount", "remainingRequiredCount",
            "decidedByUserIds", "effectiveApproverIds"), fields(result));
        assertEquals(1, result.path("requiredCount").asInt());
        assertEquals(0, result.path("approvedCount").asInt());
        assertEquals(1, result.path("remainingRequiredCount").asInt());
        assertEquals(0, result.path("decidedByUserIds").size());
        assertEquals(1, result.path("effectiveApproverIds").size());
        assertEquals(approverA.getId(), result.path("effectiveApproverIds").get(0).asInt());
        assertEquals(1, result.path("candidates").size());
        JsonNode candidate = result.path("candidates").get(0);
        assertEquals(Set.of("id", "username", "displayName", "email"), fields(candidate));
        assertEquals(approverA.getId(), candidate.path("id").asInt());
        assertEquals(approverA.getUsername(), candidate.path("username").asString());
        assertEquals(approverA.getDisplayName(), candidate.path("displayName").asString());
        assertEquals(approverA.getEmail(), candidate.path("email").asString());
        assertFalse(candidate.has("passwordHash"));
        assertFalse(response.body().contains(approverB.getEmail()));
        assertFalse(response.body().contains(managerB.getEmail()));
    }

    @Test
    void foreignWorkspacePinsAreForbiddenAndForeignResourceIdsAreNotFound() throws Exception {
        var pin = request(graphA.path(), workspaceB, managerSessionA);
        assertEquals(403, pin.statusCode(), pin.body());
        var foreign = request(graphA.path(), workspaceB, managerSessionB);
        assertEquals(404, foreign.statusCode(), foreign.body());
        var own = request(graphB.path(), workspaceB, managerSessionB);
        assertEquals(200, own.statusCode(), own.body());
        assertEquals(approverB.getId(), json.readTree(own.body()).path("candidates").get(0).path("id").asInt());
        assertFalse(own.body().contains(approverA.getEmail()));
    }

    @Test
    void mismatchedParentsAndNonPendingApprovalsAreNotFoundButInactiveStepsAreBadRequests() throws Exception {
        Graph other = graph(workspaceA, managerA);
        String mismatch = new Graph(other.dealId(), graphA.documentId(), graphA.stepId()).path();
        assertEquals(404, request(mismatch, workspaceA, managerSessionA).statusCode());
        mismatch = new Graph(graphA.dealId(), graphA.documentId(), other.stepId()).path();
        assertEquals(404, request(mismatch, workspaceA, managerSessionA).statusCode());
        jdbc.update("UPDATE document_approval_step SET status = 'pending' WHERE id = ?", graphA.stepId());
        assertEquals(400, request(graphA.path(), workspaceA, managerSessionA).statusCode());
        jdbc.update("UPDATE deal_document SET status = 'draft' WHERE id = ?", graphA.documentId());
        assertEquals(404, request(graphA.path(), workspaceA, managerSessionA).statusCode());
    }

    private Set<String> fields(JsonNode node) {
        Set<String> names = new HashSet<>();
        node.properties().forEach(entry -> names.add(entry.getKey()));
        return names;
    }

    private Workspace workspace() {
        String suffix = UUID.randomUUID().toString();
        Organization org = new Organization();
        org.setName("Candidates HTTP");
        org.setSlug("candidates-" + suffix);
        organizations.insert(org);
        organizationFixtures.add(org);
        Workspace workspace = new Workspace();
        workspace.setOrgId(org.getId());
        workspace.setName("Candidates workspace");
        workspace.setSlug("candidates-" + suffix);
        workspaces.insert(workspace);
        workspaceFixtures.add(workspace);
        return workspace;
    }

    private User member(Workspace workspace, String permission) {
        User user = AuthenticatedSessions.account(users, "candidates");
        userIds.add(user.getId());
        workspaces.addMember(workspace.getId(), user.getId(), "member");
        int role = insert("INSERT INTO workspace_role (workspace_id, name) VALUES (?, ?)",
            workspace.getId(), "Candidates " + user.getId());
        jdbc.update("INSERT INTO workspace_role_permission (workspace_role_id, permission) VALUES (?, ?)", role, permission);
        workspaces.setMemberCustomRole(workspace.getId(), user.getId(), role);
        OwnershipOfferTestFixtures.approvedPasskey(jdbc, user);
        return user;
    }

    private Graph graph(Workspace workspace, User requester) {
        int workspaceId = workspace.getId();
        int pipeline = insert("INSERT INTO pipeline (workspace_id, name) VALUES (?, 'Candidates')", workspaceId);
        int stage = insert("INSERT INTO stage (workspace_id, pipeline_id, name, position) VALUES (?, ?, 'Candidates', 0)",
            workspaceId, pipeline);
        int deal = insert("INSERT INTO deal (workspace_id, name, pipeline_id, stage_id) VALUES (?, 'Candidates', ?, ?)",
            workspaceId, pipeline, stage);
        int document = insert("INSERT INTO deal_document (workspace_id, deal_id, type, status, version, content, currency, created_by) "
            + "VALUES (?, ?, 'quote', 'pending_approval', 1, '{}', 'JPY', ?)", workspaceId, deal, requester.getId());
        int approval = insert("INSERT INTO document_approval (workspace_id, deal_id, document_id, requested_by) VALUES (?, ?, ?, ?)",
            workspaceId, deal, document, requester.getId());
        int step = insert("INSERT INTO document_approval_step (workspace_id, approval_id, step_order, required_count, status) "
            + "VALUES (?, ?, 1, 1, 'active')", workspaceId, approval);
        jdbc.update("INSERT INTO document_approval_step_approver (workspace_id, step_id, approver_kind) VALUES (?, ?, 'any_approver')",
            workspaceId, step);
        return new Graph(deal, document, step);
    }

    private int insert(String sql, Object... arguments) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int index = 0; index < arguments.length; index++) {
                statement.setObject(index + 1, arguments[index]);
            }
            return statement;
        }, key);
        return java.util.Objects.requireNonNull(key.getKey()).intValue();
    }

    private <S extends Session> String session(SessionRepository<S> repository, User user) {
        S session = repository.createSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
            new SecurityContextImpl(new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities())));
        session.setAttribute(SessionSecurityService.AUTHENTICATED_AT_ATTR, System.currentTimeMillis());
        session.setAttribute(SessionSecurityService.AUTHENTICATED_USER_ATTR, user.getId());
        session.setAttribute(SessionSecurityService.SESSION_EPOCH_ATTR, user.getSessionEpoch());
        repository.save(session);
        sessionIds.add(session.getId());
        return session.getId();
    }

    private HttpResponse<String> request(String path, Workspace workspace, String session) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(45)).header("X-Workspace-Id", Integer.toString(workspace.getId()));
        if (session != null) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            cookies.writeCookieValue(new CookieSerializer.CookieValue(new MockHttpServletRequest(), response, session));
            request.header("Cookie", response.getHeader("Set-Cookie").split(";", 2)[0]);
        }
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private record Graph(int dealId, int documentId, int stepId) {
        String path() {
            return "/api/deals/" + dealId + "/documents/" + documentId
                + "/approval/steps/" + stepId + "/approver-candidates";
        }
    }
}
