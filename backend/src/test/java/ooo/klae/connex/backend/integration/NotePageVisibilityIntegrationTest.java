package ooo.klae.connex.backend.integration;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.request.RequestContextHolder;

import ooo.klae.connex.backend.beans.Note;
import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.mappers.NoteMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;

/** Covers the unchanged Note page HTTP contract through authentication and tenant enforcement. */
@SpringBootTest
@Transactional
class NotePageVisibilityIntegrationTest {
    private static final String PASSWORD = "Note-Page-Pw1!";

    @Autowired private WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") private Filter springSecurityFilterChain;
    @Autowired private NoteMapper noteMapper;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private PersonMapper personMapper;
    @Autowired private UserMapper userMapper;
    @Autowired private WorkspaceMapper workspaceMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;
    private Workspace workspace;
    private Workspace sibling;
    private Workspace foreign;
    private User actor;
    private User colleague;
    private Note publicNote;
    private Note secondPublicNote;
    private Note privateNote;
    private Note siblingNote;

    @BeforeEach
    void setUp() {
        RequestContextHolder.resetRequestAttributes();
        mockMvc = MockMvcBuilders.webAppContextSetup(context).addFilters(springSecurityFilterChain).build();
        Organization organization = organization();
        workspace = workspace(organization);
        sibling = workspace(organization);
        foreign = workspace(organization());
        actor = member("Page actor " + unique());
        colleague = member("Page colleague " + unique());
        workspaceMapper.addMember(workspace.getId(), actor.getId(), "member");
        workspaceMapper.addMember(sibling.getId(), actor.getId(), "member");
        workspaceMapper.addMember(workspace.getId(), colleague.getId(), "member");
        publicNote = note(workspace, actor, "workspace", "Alpha", null);
        secondPublicNote = note(workspace, actor, "workspace", "Zulu", null);
        privateNote = note(workspace, actor, "private", "Private", null);
        note(workspace, colleague, "private", "Colleague", null);
        siblingNote = note(sibling, actor, "workspace", "Sibling", null);
        note(foreign, actor, "workspace", "Foreign", null);
    }

    @Test
    void notePagesRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/notes/page").header("X-Workspace-Id", workspace.getId()))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void pagesAndTotalsIncludeOnlyActiveWorkspacePublicNotesAndCallerPrivateNotes() throws Exception {
        MockHttpSession session = login();
        mockMvc.perform(get("/api/notes/page").session(session).header("X-Workspace-Id", workspace.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(3))
            .andExpect(jsonPath("$.items[*].id", containsInAnyOrder(
                publicNote.getId(), secondPublicNote.getId(), privateNote.getId())))
            .andExpect(jsonPath("$.items[0].author").value(actor.getId()))
            .andExpect(jsonPath("$.items[0].references").isArray());
        mockMvc.perform(get("/api/notes/page").session(session).header("X-Workspace-Id", workspace.getId())
                .queryParam("workspaceOnly", "true"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(2))
            .andExpect(jsonPath("$.items[*].id", containsInAnyOrder(publicNote.getId(), secondPublicNote.getId())));
        mockMvc.perform(get("/api/notes/page").session(session).header("X-Workspace-Id", sibling.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.items[0].id").value(siblingNote.getId()));
        mockMvc.perform(get("/api/notes/page").session(session).header("X-Workspace-Id", foreign.getId()))
            .andExpect(status().isForbidden());
    }

    @Test
    void linkedNameQueryAndCountRejectUnsharedAndForgedForeignEvidence() throws Exception {
        List<Person> people = new ArrayList<>();
        for (Workspace owner : List.of(workspace, sibling, sibling, foreign)) {
            Person person = new Person();
            person.setWorkspaceId(owner.getId());
            person.setName("LinkedOracle" + unique());
            personMapper.insert(person);
            people.add(person);
            note(workspace, actor, "workspace", "Matrix " + people.size(), person);
        }
        for (int index : List.of(1, 3)) {
            jdbcTemplate.update("INSERT INTO person_share (person_id, workspace_id, granted_by) VALUES (?, ?, ?)",
                people.get(index).getId(), workspace.getId(), actor.getId());
        }
        Note ownPrivate = note(workspace, actor, "private", "Own private matrix", people.get(1));
        note(workspace, colleague, "private", "Colleague matrix", people.get(1));
        MockHttpSession session = login();
        for (int index = 0; index < people.size(); index++) {
            int expected = index == 0 ? 1 : index == 1 ? 2 : 0;
            mockMvc.perform(get("/api/notes/page").session(session).header("X-Workspace-Id", workspace.getId())
                    .queryParam("q", people.get(index).getName()).queryParam("size", "1")
                    .queryParam("sort", "title").queryParam("dir", "asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(expected))
                .andExpect(jsonPath("$.items.length()").value(Math.min(expected, 1)));
        }
        mockMvc.perform(get("/api/notes/page").session(session).header("X-Workspace-Id", workspace.getId())
                .queryParam("q", people.get(1).getName()).queryParam("size", "1").queryParam("page", "2")
                .queryParam("sort", "title").queryParam("dir", "asc"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(2))
            .andExpect(jsonPath("$.items[0].id").value(ownPrivate.getId()));
        mockMvc.perform(get("/api/notes/page").session(session).header("X-Workspace-Id", workspace.getId())
                .queryParam("q", people.get(1).getName()).queryParam("workspaceOnly", "true"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.items.length()").value(1));
    }

    @Test
    void titleAndAuthorQueriesKeepPageCountParityAndEscapeLiteralWildcards() throws Exception {
        Note literal = note(workspace, actor, "workspace", "Literal 100%_", null);
        note(workspace, actor, "workspace", "Literal 100XX", null);
        MockHttpSession session = login();
        mockMvc.perform(get("/api/notes/page").session(session).header("X-Workspace-Id", workspace.getId())
                .queryParam("q", " 100%_ "))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.items[0].id").value(literal.getId()));
        mockMvc.perform(get("/api/notes/page").session(session).header("X-Workspace-Id", workspace.getId())
                .queryParam("q", actor.getDisplayName()).queryParam("size", "2")
                .queryParam("sort", "title").queryParam("dir", "asc"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(5))
            .andExpect(jsonPath("$.items.length()").value(2));
        mockMvc.perform(get("/api/notes/page").session(session).header("X-Workspace-Id", workspace.getId())
                .queryParam("q", "missing page query"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(0))
            .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void pageBoundsClampSizesAndRejectOffsetsBeyondTheExistingWindow() throws Exception {
        for (int i = 0; i < 105; i++) {
            note(workspace, actor, "workspace", "Bound " + i, null);
        }
        MockHttpSession session = login();
        mockMvc.perform(get("/api/notes/page").session(session).header("X-Workspace-Id", workspace.getId())
                .queryParam("page", "0").queryParam("size", "500"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(108))
            .andExpect(jsonPath("$.items.length()").value(100));
        mockMvc.perform(get("/api/notes/page").session(session).header("X-Workspace-Id", workspace.getId())
                .queryParam("page", "2").queryParam("size", "100"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(108))
            .andExpect(jsonPath("$.items.length()").value(8));
        mockMvc.perform(get("/api/notes/page").session(session).header("X-Workspace-Id", workspace.getId())
                .queryParam("page", "-1").queryParam("size", "0"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1));
        mockMvc.perform(get("/api/notes/page").session(session).header("X-Workspace-Id", workspace.getId())
                .queryParam("page", "1001").queryParam("size", "100"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(108))
            .andExpect(jsonPath("$.items").isEmpty());
        mockMvc.perform(get("/api/notes/page").session(session).header("X-Workspace-Id", workspace.getId())
                .queryParam("page", "1002").queryParam("size", "100"))
            .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/notes/page").session(session).header("X-Workspace-Id", workspace.getId())
                .queryParam("page", String.valueOf(Integer.MAX_VALUE)).queryParam("size", "100"))
            .andExpect(status().isBadRequest());
    }

    private Note note(Workspace owner, User author, String visibility, String title, Person person) {
        Note note = new Note();
        note.setWorkspaceId(owner.getId());
        note.setAuthor(author);
        note.setVisibility(visibility);
        note.setTitle(title);
        note.setContent("Plain note body");
        note.setPerson(person);
        noteMapper.insert(note);
        return note;
    }

    private Organization organization() {
        Organization organization = new Organization();
        organization.setName("Note page " + unique());
        organization.setSlug("note-page-org-" + unique());
        organizationMapper.insert(organization);
        return organization;
    }

    private Workspace workspace(Organization organization) {
        Workspace workspace = new Workspace();
        workspace.setOrgId(organization.getId());
        workspace.setName("Note page " + unique());
        workspace.setSlug("note-page-" + unique());
        workspaceMapper.insert(workspace);
        return workspace;
    }

    private User member(String displayName) {
        String suffix = unique();
        User user = new User();
        user.setUsername("note_page_" + suffix);
        user.setDisplayName(displayName);
        user.setEmail(suffix + "@example.com");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setTimezone("UTC");
        userMapper.insert(user);
        return user;
    }

    private MockHttpSession login() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + actor.getUsername() + "\",\"password\":\"" + PASSWORD + "\"}"))
            .andExpect(status().isOk()).andReturn();
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertNotNull(session);
        return session;
    }

    private static String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
