package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.Filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.request.RequestContextHolder;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.PipelineMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;

/** Full-stack HTTP coverage for the canonical deal member-scope contract. */
@SpringBootTest
@Transactional
class DealMemberScopeIntegrationTest {
    private static final String PASSWORD = "Member-Scope-Test-Pw1!";

    @Autowired private WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") private Filter springSecurityFilterChain;
    @Autowired private UserMapper userMapper;
    @Autowired private WorkspaceMapper workspaceMapper;
    @Autowired private PipelineMapper pipelineMapper;
    @Autowired private DealMapper dealMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private ObjectMapper objectMapper;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .addFilters(springSecurityFilterChain)
            .build();
    }

    @Test
    void scopeModesKeepPageMetricsFacetsAndBoardOnTheSameSet() throws Exception {
        Fixture fixture = fixture();

        assertScopedCounts(fixture, null, null, 5);
        assertScopedCounts(fixture, "me", List.of(fixture.foreignMember().getId()), 1);
        assertScopedCounts(fixture, "members", List.of(fixture.firstMember().getId()), 2);
        assertScopedCounts(fixture, "members",
            List.of(fixture.firstMember().getId(), fixture.secondMember().getId()), 3);
        assertScopedCounts(fixture, "unassigned", null, 1);
        assertScopedCounts(fixture, "members", List.of(fixture.emptyMember().getId()), 0);

        JsonNode repeated = json(fixture, "/api/deals/page",
            "scope", "members",
            "memberIds", Integer.toString(fixture.firstMember().getId()),
            "memberIds", Integer.toString(fixture.secondMember().getId()));
        assertEquals(3, repeated.path("total").asInt());

        JsonNode commaSeparated = json(fixture, "/api/deals/page",
            "scope", "members",
            "memberIds", fixture.firstMember().getId() + "," + fixture.secondMember().getId());
        assertEquals(3, commaSeparated.path("total").asInt());

        JsonNode facets = json(fixture, "/api/deals/facets",
            "scope", "members",
            "memberIds", fixture.firstMember().getId() + "," + fixture.secondMember().getId());
        assertEquals(2, facetCount(facets.path("owners"), fixture.firstMember().getId()));
        assertEquals(1, facetCount(facets.path("owners"), fixture.secondMember().getId()));

        JsonNode allTeamFacets = json(fixture, "/api/deals/facets");
        assertEquals(1, facetCount(allTeamFacets.path("owners"), "__empty__"));
    }

    @Test
    void invalidSelectedMembersReturnBadRequest() throws Exception {
        Fixture fixture = fixture();

        assertBadRequest(fixture, "memberIds are required when scope=members",
            "scope", "members");
        assertBadRequest(fixture,
            "memberIds must contain only active members of the current workspace",
            "scope", "members", "memberIds", Integer.toString(fixture.foreignMember().getId()));
        assertBadRequest(fixture,
            "memberIds must contain only active members of the current workspace",
            "scope", "members", "memberIds", Integer.toString(fixture.nonMember().getId()));
        assertBadRequest(fixture,
            "memberIds must contain only active members of the current workspace",
            "scope", "members", "memberIds", Integer.toString(fixture.pendingMember().getId()));

        List<String> oversized = new ArrayList<>();
        oversized.add("scope");
        oversized.add("members");
        for (int id = 1; id <= 51; id++) {
            oversized.add("memberIds");
            oversized.add(Integer.toString(id));
        }
        assertBadRequest(fixture, "memberIds accepts at most 50 values",
            oversized.toArray(String[]::new));
    }

    private void assertScopedCounts(Fixture fixture, String scope, List<Integer> memberIds,
            int expected) throws Exception {
        List<String> parameters = new ArrayList<>();
        if (scope != null) {
            parameters.add("scope");
            parameters.add(scope);
        }
        if (memberIds != null) {
            for (Integer memberId : memberIds) {
                parameters.add("memberIds");
                parameters.add(Integer.toString(memberId));
            }
        }
        String[] params = parameters.toArray(String[]::new);
        assertEquals(expected, json(fixture, "/api/deals/page", params).path("total").asInt());
        assertEquals(expected, json(fixture, "/api/deals/metrics", params).path("totalCount").asInt());
        assertEquals(expected, facetTotal(json(fixture, "/api/deals/facets", params).path("owners")));

        List<String> boardParameters = new ArrayList<>(parameters);
        boardParameters.add("pipelineId");
        boardParameters.add(Integer.toString(fixture.pipeline().getId()));
        assertEquals(expected,
            json(fixture, "/api/deals/board", boardParameters.toArray(String[]::new)).size());
    }

    private JsonNode json(Fixture fixture, String path, String... parameters) throws Exception {
        MockHttpServletRequestBuilder request = get(path)
            .header("X-Workspace-Id", fixture.workspace().getId())
            .session(fixture.session());
        for (int index = 0; index < parameters.length; index += 2) {
            request.param(parameters[index], parameters[index + 1]);
        }
        String body = mockMvc.perform(request)
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
        return objectMapper.readTree(body);
    }

    private void assertBadRequest(Fixture fixture, String message, String... parameters) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/deals/page")
            .header("X-Workspace-Id", fixture.workspace().getId())
            .session(fixture.session());
        for (int index = 0; index < parameters.length; index += 2) {
            request.param(parameters[index], parameters[index + 1]);
        }
        mockMvc.perform(request)
            .andExpect(status().isBadRequest())
            .andExpect(content().string(message));
    }

    private long facetTotal(JsonNode facets) {
        long total = 0;
        for (JsonNode facet : facets) {
            total += facet.path("count").asLong();
        }
        return total;
    }

    private long facetCount(JsonNode facets, int memberId) {
        return facetCount(facets, Integer.toString(memberId));
    }

    private long facetCount(JsonNode facets, String key) {
        for (JsonNode facet : facets) {
            if (key.equals(facet.path("key").asText())) {
                return facet.path("count").asLong();
            }
        }
        return 0;
    }

    private Fixture fixture() throws Exception {
        RequestContextHolder.resetRequestAttributes();
        Workspace workspace = newWorkspace("scope");
        User currentUser = newUser();
        User firstMember = newUser();
        User secondMember = newUser();
        User emptyMember = newUser();
        User pendingMember = newUser();
        workspaceMapper.addMember(workspace.getId(), currentUser.getId(), "owner");
        workspaceMapper.addMember(workspace.getId(), firstMember.getId(), "member");
        workspaceMapper.addMember(workspace.getId(), secondMember.getId(), "member");
        workspaceMapper.addMember(workspace.getId(), emptyMember.getId(), "member");
        workspaceMapper.addPendingMember(workspace.getId(), pendingMember.getId(), "member");

        Workspace foreignWorkspace = newWorkspace("foreign");
        User foreignMember = newUser();
        workspaceMapper.addMember(foreignWorkspace.getId(), foreignMember.getId(), "member");
        User nonMember = newUser();

        Pipeline pipeline = newPipeline(workspace);
        Stage stage = newStage(workspace, pipeline);
        newDeal(workspace, pipeline, stage, currentUser.getId(), "Mine");
        newDeal(workspace, pipeline, stage, firstMember.getId(), "First A");
        newDeal(workspace, pipeline, stage, firstMember.getId(), "First B");
        newDeal(workspace, pipeline, stage, secondMember.getId(), "Second");
        newDeal(workspace, pipeline, stage, null, "Unassigned");

        return new Fixture(workspace, pipeline, currentUser, firstMember, secondMember,
            emptyMember, pendingMember, foreignMember, nonMember, login(currentUser.getUsername()));
    }

    private MockHttpSession login(String username) throws Exception {
        String body = "{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}";
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andReturn();
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertNotNull(session, "login did not establish a session for " + username);
        return session;
    }

    private Workspace newWorkspace(String prefix) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Workspace workspace = new Workspace();
        workspace.setName(prefix + " " + suffix);
        workspace.setSlug(prefix + "-" + suffix);
        workspaceMapper.insert(workspace);
        return workspace;
    }

    private User newUser() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User user = new User();
        user.setUsername("member_scope_" + suffix);
        user.setDisplayName("Member Scope " + suffix);
        user.setEmail(suffix + "@example.com");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setTimezone("UTC");
        userMapper.insert(user);
        return user;
    }

    private Pipeline newPipeline(Workspace workspace) {
        Pipeline pipeline = new Pipeline();
        pipeline.setWorkspaceId(workspace.getId());
        pipeline.setName("Scope Pipeline " + UUID.randomUUID().toString().substring(0, 8));
        pipelineMapper.insertPipeline(pipeline);
        return pipeline;
    }

    private Stage newStage(Workspace workspace, Pipeline pipeline) {
        Stage stage = new Stage();
        stage.setWorkspaceId(workspace.getId());
        stage.setPipeline(pipeline);
        stage.setName("Scope Stage");
        stage.setPosition(0);
        pipelineMapper.insertStage(stage);
        return stage;
    }

    private Deal newDeal(Workspace workspace, Pipeline pipeline, Stage stage,
            Integer ownerId, String name) {
        Deal deal = new Deal();
        deal.setWorkspaceId(workspace.getId());
        deal.setOwnerId(ownerId);
        deal.setName(name);
        deal.setValue(100);
        deal.setCurrency("USD");
        deal.setPipelineId(pipeline.getId());
        deal.setStageId(stage.getId());
        dealMapper.insert(deal);
        return deal;
    }

    private record Fixture(
            Workspace workspace,
            Pipeline pipeline,
            User currentUser,
            User firstMember,
            User secondMember,
            User emptyMember,
            User pendingMember,
            User foreignMember,
            User nonMember,
            MockHttpSession session) {
    }
}
