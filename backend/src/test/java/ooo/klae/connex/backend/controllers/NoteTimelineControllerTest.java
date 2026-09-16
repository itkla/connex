package ooo.klae.connex.backend.controllers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.request.RequestContextHolder;

import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Note;
import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.NoteMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.PipelineMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.tenant.TenantContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Exercises cursor admission and body-free pulse aggregation through authenticated HTTP reads. */
@SpringBootTest
class NoteTimelineControllerTest {
    private static final String PASSWORD = "Timeline-boundary-Pw1!";

    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private WorkspaceMapper workspaceMapper;
    @Autowired private UserMapper userMapper;
    @Autowired private PersonMapper personMapper;
    @Autowired private DealMapper dealMapper;
    @Autowired private PipelineMapper pipelineMapper;
    @Autowired private SqlSessionTemplate sqlSessionTemplate;
    @Autowired private TenantContext tenantContext;
    @MockitoSpyBean private NoteMapper noteMapper;

    private MockMvc mockMvc;
    private Workspace workspace;
    private User member;
    private MockHttpSession session;

    @BeforeEach
    void setUp() throws Exception {
        clearContext();
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        workspace = newWorkspace();
        member = newMember(workspace);
        session = login(member);
    }

    @AfterEach
    void clearContext() {
        RequestContextHolder.resetRequestAttributes();
        SecurityContextHolder.clearContext();
        tenantContext.clear();
    }

    @Test
    void everyTimelineAcceptsCursorsPastTheOffsetBoundaryWhileCappingSize() throws Exception {
        Person person = new Person();
        person.setWorkspaceId(workspace.getId());
        person.setName("Timeline contact");
        personMapper.insert(person);
        Deal deal = newDeal();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        for (int index = 0; index < 101; index++) {
            newNote(workspace, member, "workspace", today, person, deal);
        }
        for (String route : List.of("/api/persons/" + person.getId() + "/notes",
                "/api/deals/" + deal.getId() + "/notes", "/api/users/" + member.getId() + "/notes")) {
            JsonNode firstPage = read(route, session);
            assertEquals(25, firstPage.size());
            JsonNode boundary = firstPage.path(24);
            String beforeAt = boundary.path("updatedAt").asString();
            String beforeId = Integer.toString(boundary.path("id").asInt());
            mockMvc.perform(get(route).session(session).header("X-Workspace-Id", workspace.getId())
                    .param("page", "4002").param("size", "25")
                    .param("beforeAt", beforeAt).param("beforeId", beforeId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(25));
            mockMvc.perform(get(route).session(session).header("X-Workspace-Id", workspace.getId())
                    .param("page", "2147483647").param("size", "500")
                    .param("beforeAt", today.plusDays(1) + "T00:00:00Z").param("beforeId", "2147483647"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(100));
            mockMvc.perform(get(route).session(session).header("X-Workspace-Id", workspace.getId())
                    .param("page", "4001").param("size", "25"))
                .andExpect(status().isOk());
            mockMvc.perform(get(route).session(session).header("X-Workspace-Id", workspace.getId())
                    .param("page", "4002").param("size", "25"))
                .andExpect(status().isBadRequest());
            mockMvc.perform(get(route).session(session).header("X-Workspace-Id", workspace.getId())
                    .param("page", "4002").param("beforeAt", beforeAt))
                .andExpect(status().isBadRequest());
        }
    }

    @Test
    void pulseCountsAllRecentCreationsAndExcludesRecentlyEditedOldNotesAndInvisibleRows() throws Exception {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        for (int index = 0; index < 101; index++) {
            newNote(workspace, member, "workspace", today.minusDays(1), null, null);
        }
        for (int index = 0; index < 100; index++) {
            newNote(workspace, member, "workspace", today.minusDays(84), null, null);
        }
        newNote(workspace, member, "workspace", today.minusDays(83), null, null);
        newNote(workspace, member, "private", today, null, null);
        newNote(workspace, member, "workspace", today.plusDays(1), null, null);
        User other = newMember(workspace);
        newNote(workspace, other, "workspace", today, null, null);
        Workspace foreign = newWorkspace();
        newNote(foreign, member, "workspace", today, null, null);

        String route = "/api/users/" + member.getId() + "/notes/pulse";
        JsonNode ownPulse = read(route, session);
        assertEquals(objectMapper.valueToTree(List.of(
            Map.of("date", today.minusDays(83).toString(), "count", 1),
            Map.of("date", today.minusDays(1).toString(), "count", 101),
            Map.of("date", today.toString(), "count", 1))), ownPulse);
        JsonNode visiblePulse = read(route, login(other));
        assertEquals(2, visiblePulse.size());
        assertEquals(101, visiblePulse.get(1).path("count").asInt());

        verify(noteMapper, never()).getVisibleNotesByAuthorId(anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), any());
        String sql = sqlSessionTemplate.getConfiguration()
            .getMappedStatement(NoteMapper.class.getName() + ".getVisibleNoteActivityByAuthorId")
            .getBoundSql(Map.of("workspaceId", workspace.getId(), "authorId", member.getId(),
                "currentUserId", member.getId(), "from", today.minusDays(83).atStartOfDay(),
                "until", today.plusDays(1).atStartOfDay())).getSql().toLowerCase();
        assertFalse(sql.contains("content"));
        assertFalse(sql.contains("title"));
        assertTrue(sql.contains("limit 84"));

        User outsider = newMember(foreign);
        mockMvc.perform(get(route).session(login(outsider)).header("X-Workspace-Id", workspace.getId()))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/users/" + outsider.getId() + "/notes/pulse")
                .session(session).header("X-Workspace-Id", workspace.getId()))
            .andExpect(status().isNotFound());
        mockMvc.perform(get(route).header("X-Workspace-Id", workspace.getId()))
            .andExpect(status().isUnauthorized());
    }

    private JsonNode read(String route, MockHttpSession reader) throws Exception {
        MvcResult result = mockMvc.perform(get(route).session(reader).header("X-Workspace-Id", workspace.getId()))
            .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private void newNote(Workspace owner, User author, String visibility, LocalDate created,
            Person person, Deal deal) {
        Note note = new Note();
        note.setWorkspaceId(owner.getId());
        note.setAuthor(author);
        note.setVisibility(visibility);
        note.setContent("Timeline body");
        note.setCreatedAt(created + " 00:00:00");
        note.setPerson(person);
        note.setDeal(deal);
        noteMapper.insert(note);
    }

    private Workspace newWorkspace() {
        Organization organization = new Organization();
        organization.setName("Timeline " + UUID.randomUUID());
        organization.setSlug("timeline-" + UUID.randomUUID());
        organizationMapper.insert(organization);
        Workspace created = new Workspace();
        created.setOrgId(organization.getId());
        created.setName("Timeline workspace");
        created.setSlug("timeline-" + UUID.randomUUID());
        workspaceMapper.insert(created);
        return created;
    }

    private User newMember(Workspace owner) {
        User user = new User();
        user.setUsername("timeline_" + UUID.randomUUID().toString().replace("-", ""));
        user.setDisplayName("Timeline member");
        user.setEmail(UUID.randomUUID() + "@example.com");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setTimezone("UTC");
        userMapper.insert(user);
        workspaceMapper.addMember(owner.getId(), user.getId(), "member");
        return user;
    }

    private MockHttpSession login(User user) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("username", user.getUsername(), "password", PASSWORD))))
            .andExpect(status().isOk()).andReturn();
        if (!(result.getRequest().getSession(false) instanceof MockHttpSession authenticated)) {
            throw new IllegalStateException("Login did not establish a session");
        }
        return authenticated;
    }

    private Deal newDeal() {
        Pipeline pipeline = new Pipeline();
        pipeline.setWorkspaceId(workspace.getId());
        pipeline.setName("Timeline pipeline");
        pipelineMapper.insertPipeline(pipeline);
        Stage stage = new Stage();
        stage.setWorkspaceId(workspace.getId());
        stage.setPipeline(pipeline);
        stage.setName("Timeline stage");
        pipelineMapper.insertStage(stage);
        Deal deal = new Deal();
        deal.setWorkspaceId(workspace.getId());
        deal.setPipelineId(pipeline.getId());
        deal.setStageId(stage.getId());
        deal.setName("Timeline deal");
        deal.setCurrency("JPY");
        dealMapper.insert(deal);
        return deal;
    }
}
