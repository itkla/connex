package ooo.klae.connex.backend.integration;

import static org.hamcrest.Matchers.contains;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.request.RequestContextHolder;

import ooo.klae.connex.backend.beans.Attachment;
import ooo.klae.connex.backend.beans.Note;
import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.mappers.AttachmentMapper;
import ooo.klae.connex.backend.mappers.NoteMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;

/** Proves private-note attachment visibility for two active members of one workspace. */
@SpringBootTest
@Transactional
@UnenrolledPrivilegedFixture
class AttachmentPrivateNoteVisibilityIntegrationTest {

    private static final String PASSWORD = "Attachment-Note-Pw1!";

    @Autowired private WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") private Filter springSecurityFilterChain;
    @Autowired private AttachmentMapper attachmentMapper;
    @Autowired private NoteMapper noteMapper;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private UserMapper userMapper;
    @Autowired private WorkspaceMapper workspaceMapper;
    @Autowired private PasswordEncoder passwordEncoder;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(springSecurityFilterChain)
                .build();
    }

    @Test
    void privateNoteAttachmentsAreVisibleOnlyToTheirAuthorAcrossReadSurfaces()
            throws Exception {
        RequestContextHolder.resetRequestAttributes();
        Organization organization = newOrganization();
        Workspace workspace = newWorkspace(organization);
        User author = newMember(workspace);
        User reader = newMember(workspace);
        Note privateNote = newNote(workspace, author, "private");
        Note workspaceNote = newNote(workspace, author, "workspace");
        String privateMarker = "PrivateAttachment" + unique();
        String workspaceMarker = "WorkspaceAttachment" + unique();
        Attachment privateAttachment = newAttachment(
            workspace, privateNote, privateMarker + ".zip", "application/zip", 100L);
        Attachment workspaceAttachment = newAttachment(
            workspace, workspaceNote, workspaceMarker + ".pdf", "application/pdf", 20L);
        MockHttpSession authorSession = login(author.getUsername());
        MockHttpSession readerSession = login(reader.getUsername());

        mockMvc.perform(get("/api/attachments")
                        .header("X-Workspace-Id", workspace.getId())
                        .session(authorSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.id == " + privateAttachment.getId() + ")]").isNotEmpty())
                .andExpect(jsonPath("$[?(@.id == " + workspaceAttachment.getId() + ")]").isNotEmpty());
        mockMvc.perform(get("/api/attachments/page")
                        .header("X-Workspace-Id", workspace.getId())
                        .session(authorSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[?(@.id == " + privateAttachment.getId() + ")]").isNotEmpty())
                .andExpect(jsonPath("$.items[?(@.id == " + workspaceAttachment.getId() + ")]").isNotEmpty());
        mockMvc.perform(get("/api/attachments/facets")
                        .header("X-Workspace-Id", workspace.getId())
                        .session(authorSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sources[?(@.key == 'note')].count").value(contains(2)))
                .andExpect(jsonPath("$.kinds[?(@.key == 'archive')].count").value(contains(1)))
                .andExpect(jsonPath("$.kinds[?(@.key == 'pdf')].count").value(contains(1)))
                .andExpect(jsonPath("$.orphaned").value(2))
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.totalSize").value(120));
        mockMvc.perform(get("/api/attachments/{id}", privateAttachment.getId())
                        .header("X-Workspace-Id", workspace.getId())
                        .session(authorSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(privateAttachment.getId()));
        mockMvc.perform(get("/api/attachments/by-url")
                        .queryParam("url", privateAttachment.getUrl())
                        .header("X-Workspace-Id", workspace.getId())
                        .session(authorSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(privateAttachment.getId()));
        mockMvc.perform(get("/api/search")
                        .queryParam("query", privateMarker)
                        .header("X-Workspace-Id", workspace.getId())
                        .session(authorSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attachments[?(@.id == " + privateAttachment.getId() + ")]")
                    .isNotEmpty());

        mockMvc.perform(get("/api/attachments")
                        .header("X-Workspace-Id", workspace.getId())
                        .session(readerSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[?(@.id == " + privateAttachment.getId() + ")]").isEmpty())
                .andExpect(jsonPath("$[?(@.id == " + workspaceAttachment.getId() + ")]").isNotEmpty());
        mockMvc.perform(get("/api/attachments/page")
                        .header("X-Workspace-Id", workspace.getId())
                        .session(readerSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[?(@.id == " + privateAttachment.getId() + ")]").isEmpty())
                .andExpect(jsonPath("$.items[?(@.id == " + workspaceAttachment.getId() + ")]").isNotEmpty());
        mockMvc.perform(get("/api/attachments/facets")
                        .header("X-Workspace-Id", workspace.getId())
                        .session(readerSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sources[?(@.key == 'note')].count").value(contains(1)))
                .andExpect(jsonPath("$.kinds[?(@.key == 'archive')]").isEmpty())
                .andExpect(jsonPath("$.kinds[?(@.key == 'pdf')].count").value(contains(1)))
                .andExpect(jsonPath("$.orphaned").value(1))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.totalSize").value(20));
        mockMvc.perform(get("/api/attachments/{id}", privateAttachment.getId())
                        .header("X-Workspace-Id", workspace.getId())
                        .session(readerSession))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/attachments/by-url")
                        .queryParam("url", privateAttachment.getUrl())
                        .header("X-Workspace-Id", workspace.getId())
                        .session(readerSession))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/search")
                        .queryParam("query", privateMarker)
                        .header("X-Workspace-Id", workspace.getId())
                        .session(readerSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attachments[?(@.id == " + privateAttachment.getId() + ")]")
                    .isEmpty());
        mockMvc.perform(get("/api/attachments/{id}", workspaceAttachment.getId())
                        .header("X-Workspace-Id", workspace.getId())
                        .session(readerSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(workspaceAttachment.getId()));
        mockMvc.perform(get("/api/attachments/by-url")
                        .queryParam("url", workspaceAttachment.getUrl())
                        .header("X-Workspace-Id", workspace.getId())
                        .session(readerSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(workspaceAttachment.getId()));
        mockMvc.perform(get("/api/search")
                        .queryParam("query", workspaceMarker)
                        .header("X-Workspace-Id", workspace.getId())
                        .session(readerSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attachments[?(@.id == " + workspaceAttachment.getId() + ")]")
                    .isNotEmpty());
    }

    private Organization newOrganization() {
        Organization organization = new Organization();
        organization.setName("Attachment note " + unique());
        organization.setSlug("attachment-note-org-" + unique());
        organizationMapper.insert(organization);
        return organization;
    }

    private Workspace newWorkspace(Organization organization) {
        Workspace workspace = new Workspace();
        workspace.setName("Attachment note " + unique());
        workspace.setSlug("attachment-note-" + unique());
        workspace.setOrgId(organization.getId());
        workspaceMapper.insert(workspace);
        return workspace;
    }

    private User newMember(Workspace workspace) {
        String suffix = unique();
        User user = new User();
        user.setUsername("attachment_note_" + suffix);
        user.setDisplayName("Attachment note member " + suffix);
        user.setEmail(suffix + "@example.com");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setTimezone("UTC");
        userMapper.insert(user);
        workspaceMapper.addMember(workspace.getId(), user.getId(), "member");
        return user;
    }

    private Note newNote(Workspace workspace, User author, String visibility) {
        Note note = new Note();
        note.setWorkspaceId(workspace.getId());
        note.setContent("Attachment visibility note " + unique());
        note.setVisibility(visibility);
        note.setAuthor(author);
        noteMapper.insert(note);
        return note;
    }

    private Attachment newAttachment(
            Workspace workspace,
            Note note,
            String fileName,
            String contentType,
            long size) {
        Attachment attachment = new Attachment();
        attachment.setWorkspaceId(workspace.getId());
        attachment.setEntityType("note");
        attachment.setEntityId(note.getId());
        attachment.setFileName(fileName);
        attachment.setUrl("https://files.example.com/" + unique());
        attachment.setContentType(contentType);
        attachment.setSize(size);
        attachmentMapper.insert(attachment);
        return attachment;
    }

    private MockHttpSession login(String username) throws Exception {
        String body = "{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}";
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertNotNull(session, "login did not establish an attachment note session");
        return session;
    }

    private static String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
