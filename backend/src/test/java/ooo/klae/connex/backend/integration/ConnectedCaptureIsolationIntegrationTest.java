package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
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

import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.ProviderCapturedInteraction;
import ooo.klae.connex.backend.beans.ProviderCapturedParticipant;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.ProviderCaptureMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.services.SessionSecurityService;

/**
 * Exercises capture review and purge through the real security, tenant, service, and SQL layers.
 */
@SpringBootTest(properties = {
    "spring.task.scheduling.enabled=false",
    "connex.connected-accounts.google.enabled=true",
    "connex.connected-accounts.google.client-id=capture-isolation-client",
    "connex.connected-accounts.google.client-secret=capture-isolation-secret",
    "connex.connected-capture.scheduling-enabled=true",
    "connex.connected-capture.google.enabled=true"
})
@Transactional
class ConnectedCaptureIsolationIntegrationTest {
    private static final String PASSWORD = "Capture-Isolation-Pw1!";
    private static final DateTimeFormatter MYSQL_DATETIME =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Autowired private WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") private Filter springSecurityFilterChain;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private ProviderCaptureMapper captureMapper;
    @Autowired private UserMapper userMapper;
    @Autowired private WorkspaceMapper workspaceMapper;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        RequestContextHolder.resetRequestAttributes();
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .addFilters(springSecurityFilterChain)
            .build();
    }

    @Test
    void reviewAndPurgeAreCurrentUserAndWorkspaceScoped() throws Exception {
        Workspace ownerWorkspace = newWorkspace();
        Workspace otherWorkspace = newWorkspace();
        User owner = newMember(ownerWorkspace);
        User outsider = newMember(otherWorkspace);
        ProviderCapturedInteraction interaction =
            capturedInteraction(ownerWorkspace, owner);
        capturedParticipant(ownerWorkspace, interaction);

        mockMvc.perform(get("/api/account/connections/google/reviews"))
            .andExpect(status().isUnauthorized());

        MockHttpSession ownerSession = login(owner.getUsername());
        mockMvc.perform(get("/api/account/connections/google/reviews")
                .header("X-Workspace-Id", ownerWorkspace.getId())
                .session(ownerSession))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.items[0].interactionId")
                .value(interaction.getId()));

        MockHttpSession outsiderSession = login(outsider.getUsername());
        mockMvc.perform(get("/api/account/connections/google/reviews")
                .header("X-Workspace-Id", otherWorkspace.getId())
                .session(outsiderSession))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(0));
        mockMvc.perform(get("/api/account/connections/google/reviews")
                .header("X-Workspace-Id", ownerWorkspace.getId())
                .session(outsiderSession))
            .andExpect(status().isForbidden());

        mockMvc.perform(delete(
                "/api/account/connections/google/captured-data")
                .header("X-Workspace-Id", ownerWorkspace.getId())
                .session(ownerSession)
                .with(csrf()))
            .andExpect(status().isForbidden());

        ownerSession.setAttribute(
            SessionSecurityService.WEBAUTHN_STEP_UP_AT_ATTR,
            System.currentTimeMillis());
        ownerSession.setAttribute(
            SessionSecurityService.WEBAUTHN_STEP_UP_USER_ATTR,
            owner.getId());
        mockMvc.perform(delete(
                "/api/account/connections/google/captured-data")
                .header("X-Workspace-Id", ownerWorkspace.getId())
                .session(ownerSession)
                .with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.active").value(false));

        assertEquals(
            0,
            captureMapper.countUserProviderResiduals(
                ownerWorkspace.getId(), owner.getId(), "google"));
    }

    private MockHttpSession login(String username) throws Exception {
        String body = "{\"username\":\"" + username
            + "\",\"password\":\"" + PASSWORD + "\"}";
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andReturn();
        MockHttpSession session =
            (MockHttpSession) result.getRequest().getSession(false);
        assertNotNull(session);
        return session;
    }

    private Workspace newWorkspace() {
        String suffix = suffix();
        Organization organization = new Organization();
        organization.setName("Capture " + suffix);
        organization.setSlug("capture-" + suffix);
        organizationMapper.insert(organization);

        Workspace workspace = new Workspace();
        workspace.setOrgId(organization.getId());
        workspace.setName("Capture " + suffix);
        workspace.setSlug("capture-workspace-" + suffix);
        workspaceMapper.insert(workspace);
        return workspace;
    }

    private User newMember(Workspace workspace) {
        String suffix = suffix();
        User user = new User();
        user.setUsername("capture_" + suffix);
        user.setDisplayName("Capture " + suffix);
        user.setEmail("capture_" + suffix + "@example.com");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setTimezone("UTC");
        userMapper.insert(user);
        workspaceMapper.addMember(
            workspace.getId(), user.getId(), "member");
        return user;
    }

    private ProviderCapturedInteraction capturedInteraction(
            Workspace workspace, User user) {
        ProviderCapturedInteraction interaction =
            new ProviderCapturedInteraction();
        interaction.setWorkspaceId(workspace.getId());
        interaction.setUserId(user.getId());
        interaction.setProvider("google");
        interaction.setStream("mail_inbox");
        interaction.setProviderSourceId("source-" + suffix());
        interaction.setProviderConversationId("thread-" + suffix());
        interaction.setSourceKeyHash(randomHash());
        interaction.setSourceVersion("version-1");
        interaction.setPayloadHash(randomHash());
        interaction.setInteractionType("email");
        interaction.setSubject("Held capture");
        interaction.setOccurredAt(
            LocalDateTime.now(ZoneOffset.UTC).format(MYSQL_DATETIME));
        interaction.setVisibility("workspace");
        interaction.setAdmissionStatus("held");
        interaction.setAdmittedFieldsJson(
            "[\"provider_source_id\",\"subject\",\"occurred_at\",\"participants\"]");
        interaction.setMaterialExclusionsJson(
            "[\"attachments\",\"raw_mime\",\"remote_images\",\"body\"]");
        interaction.setPolicyVersion(1);
        captureMapper.insertInteraction(interaction);
        return interaction;
    }

    private void capturedParticipant(
            Workspace workspace,
            ProviderCapturedInteraction interaction) {
        ProviderCapturedParticipant participant =
            new ProviderCapturedParticipant();
        participant.setWorkspaceId(workspace.getId());
        participant.setInteractionId(interaction.getId());
        participant.setParticipantRole("to");
        participant.setDisplayName("Ambiguous");
        participant.setEmail("ambiguous@example.net");
        participant.setNormalizedEmail("ambiguous@example.net");
        participant.setMatchState("ambiguous");
        participant.setHeldReason("multiple_matches");
        captureMapper.insertParticipant(participant);
    }

    private static byte[] randomHash() {
        byte[] hash = new byte[32];
        java.util.concurrent.ThreadLocalRandom.current().nextBytes(hash);
        return hash;
    }

    private static String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
