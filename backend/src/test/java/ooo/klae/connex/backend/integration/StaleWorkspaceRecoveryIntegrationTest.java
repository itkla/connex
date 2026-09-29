package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.request.RequestContextHolder;

import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.beans.WorkspaceInvite;
import ooo.klae.connex.backend.beans.WorkspaceInviteLink;
import ooo.klae.connex.backend.config.OneTimeLinkFlowCookie;
import ooo.klae.connex.backend.mappers.InviteLinkMapper;
import ooo.klae.connex.backend.mappers.InviteMapper;
import ooo.klae.connex.backend.mappers.NoteMapper;
import ooo.klae.connex.backend.mappers.OrgAllowedDomainMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.services.OneTimeLinkFlowService;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.WorkspaceCookie;
import ooo.klae.connex.backend.util.OneTimeTokenDigest;
import tools.jackson.databind.ObjectMapper;

/** Proves stale browser selections cannot redirect note writes across organizations after revocation. */
@SpringBootTest(properties = "connex.signature.enabled=false")
class StaleWorkspaceRecoveryIntegrationTest {
    private static final String PASSWORD = "Stale-Workspace-Pw1!";

    @Autowired private WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") private Filter springSecurityFilterChain;
    @Autowired private UserMapper userMapper;
    @Autowired private WorkspaceMapper workspaceMapper;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private NoteMapper noteMapper;
    @Autowired private InviteMapper inviteMapper;
    @Autowired private InviteLinkMapper inviteLinkMapper;
    @Autowired private OrgAllowedDomainMapper orgAllowedDomainMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private TenantContext tenantContext;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ObjectMapper objectMapper;

    private final List<Integer> workspaceIds = new ArrayList<>();
    private final List<Integer> organizationIds = new ArrayList<>();
    private final List<Integer> userIds = new ArrayList<>();
    private final List<String> inviteTokenHashes = new ArrayList<>();

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        clearContexts();
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .addFilters(springSecurityFilterChain)
            .build();
    }

    /**
     * The class deliberately commits its fixtures (the revocation must be visible to the request
     * thread), so it also removes them: a shared schema that accumulates workspaces breaks
     * suites that page over them.
     */
    @AfterEach
    void clearContexts() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
        tenantContext.clear();
        for (String tokenHash : inviteTokenHashes) {
            jdbcTemplate.update("DELETE FROM one_time_link_flow WHERE source_token_hash = ?", tokenHash);
        }
        for (int userId : userIds) {
            jdbcTemplate.update("UPDATE app_user SET last_active_workspace_id = NULL WHERE id = ?", userId);
        }
        for (int workspaceId : workspaceIds) {
            jdbcTemplate.update("DELETE FROM note WHERE workspace_id = ?", workspaceId);
            jdbcTemplate.update("DELETE FROM workspace_member WHERE workspace_id = ?", workspaceId);
            jdbcTemplate.update("DELETE FROM workspace WHERE id = ?", workspaceId);
        }
        for (int organizationId : organizationIds) {
            jdbcTemplate.update("DELETE FROM organization WHERE id = ?", organizationId);
        }
        for (int userId : userIds) {
            jdbcTemplate.update("DELETE FROM notification_recipient_state WHERE recipient_id = ?", userId);
            jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", userId);
        }
        workspaceIds.clear();
        organizationIds.clear();
        userIds.clear();
        inviteTokenHashes.clear();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void revokedWorkspaceNoteWriteIsRejectedBeforeRecovery(boolean matchingHeader) throws Exception {
        Workspace workspaceA = newWorkspaceInNewOrganization();
        Workspace workspaceB = newWorkspaceInNewOrganization();
        User author = newUser();
        User reader = newUser();
        workspaceMapper.addMember(workspaceA.getId(), author.getId(), "member");
        workspaceMapper.addMember(workspaceB.getId(), author.getId(), "member");
        workspaceMapper.addMember(workspaceA.getId(), reader.getId(), "member");
        workspaceMapper.setLastActiveWorkspaceId(author.getId(), workspaceB.getId());
        MockHttpSession authorSession = login(author);
        MockHttpSession readerSession = login(reader);

        RequestContextHolder.resetRequestAttributes();
        assertEquals(1, workspaceMapper.removeMember(workspaceB.getId(), author.getId()));
        assertNull(workspaceMapper.getRole(workspaceB.getId(), author.getId()));

        Cookie staleCookie = new Cookie(WorkspaceCookie.NAME, Integer.toString(workspaceB.getId()));
        MockHttpServletRequestBuilder request = post("/api/notes")
            .cookie(staleCookie)
            .session(authorSession)
            .with(csrf().asHeader())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"content\":\"B-confidential\",\"visibility\":\"workspace\"}");
        if (matchingHeader) {
            request.header("X-Workspace-Id", workspaceB.getId());
        }
        mockMvc.perform(request)
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.message").value("Not a member of workspace " + workspaceB.getId()))
            .andExpect(cookie().doesNotExist(WorkspaceCookie.NAME));

        RequestContextHolder.resetRequestAttributes();
        assertTrue(noteMapper.getAllNotes(workspaceA.getId()).isEmpty());
        assertTrue(noteMapper.getAllNotes(workspaceB.getId()).isEmpty());
        assertEquals(workspaceB.getId(), workspaceMapper.getLastActiveWorkspaceId(author.getId()));

        mockMvc.perform(get("/api/notes/page")
                .header("X-Workspace-Id", workspaceA.getId())
                .param("q", "B-confidential")
                .session(readerSession))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items").isEmpty())
            .andExpect(jsonPath("$.total").value(0));

        mockMvc.perform(get("/api/auth/me")
                .cookie(staleCookie)
                .header("X-Workspace-Id", workspaceB.getId())
                .session(authorSession))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(author.getId()));

        mockMvc.perform(get("/api/workspaces")
                .cookie(staleCookie)
                .header("X-Workspace-Id", workspaceB.getId())
                .session(authorSession))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.activeWorkspaceId").value(workspaceA.getId()))
            .andExpect(cookie().value(WorkspaceCookie.NAME, Integer.toString(workspaceA.getId())));

        mockMvc.perform(post("/api/notes")
                .cookie(new Cookie(WorkspaceCookie.NAME, Integer.toString(workspaceA.getId())))
                .header("X-Workspace-Id", workspaceA.getId())
                .session(authorSession)
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"Explicitly selected A\",\"visibility\":\"workspace\"}"))
            .andExpect(status().isOk());

        mockMvc.perform(get("/api/notes/page")
                .header("X-Workspace-Id", workspaceA.getId())
                .param("q", "Explicitly selected A")
                .session(readerSession))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void pinlessRequestsAfterRevocationRejectNoteWritesAndRecoverOnGet() throws Exception {
        Workspace workspaceA = newWorkspaceInNewOrganization();
        Workspace workspaceB = newWorkspaceInNewOrganization();
        User author = newUser();
        workspaceMapper.addMember(workspaceA.getId(), author.getId(), "member");
        workspaceMapper.addMember(workspaceB.getId(), author.getId(), "member");
        workspaceMapper.setLastActiveWorkspaceId(author.getId(), workspaceB.getId());
        MockHttpSession authorSession = login(author);

        RequestContextHolder.resetRequestAttributes();
        assertEquals(1, workspaceMapper.removeMember(workspaceB.getId(), author.getId()));
        assertNull(workspaceMapper.getRole(workspaceB.getId(), author.getId()));
        assertEquals(workspaceB.getId(), workspaceMapper.getLastActiveWorkspaceId(author.getId()));

        mockMvc.perform(post("/api/notes")
                .session(authorSession)
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"B-confidential\",\"visibility\":\"workspace\"}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.message").value("Not a member of workspace " + workspaceB.getId()))
            .andExpect(cookie().doesNotExist(WorkspaceCookie.NAME));

        assertFalse(tenantContext.isResolved());
        RequestContextHolder.resetRequestAttributes();
        assertTrue(noteMapper.getAllNotes(workspaceA.getId()).isEmpty());
        assertTrue(noteMapper.getAllNotes(workspaceB.getId()).isEmpty());
        assertEquals(workspaceB.getId(), workspaceMapper.getLastActiveWorkspaceId(author.getId()));

        mockMvc.perform(get("/api/workspaces").session(authorSession))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.activeWorkspaceId").value(workspaceA.getId()))
            .andExpect(cookie().value(WorkspaceCookie.NAME, Integer.toString(workspaceA.getId())));

        RequestContextHolder.resetRequestAttributes();
        assertEquals(workspaceA.getId(), workspaceMapper.getLastActiveWorkspaceId(author.getId()));
    }

    /**
     * Leaving the only workspace must not strand the caller. The remembered selection is dropped,
     * so the pinless creation endpoint that would otherwise resolve the workspace just left stays
     * reachable.
     */
    @Test
    void leavingTheOnlyWorkspaceClearsTheSelectionAndKeepsCreationReachable() throws Exception {
        Workspace workspace = newWorkspaceInNewOrganization();
        User owner = newUser();
        User leaver = newUser();
        workspaceMapper.addMember(workspace.getId(), owner.getId(), "owner");
        workspaceMapper.addMember(workspace.getId(), leaver.getId(), "member");
        workspaceMapper.setLastActiveWorkspaceId(leaver.getId(), workspace.getId());
        MockHttpSession leaverSession = login(leaver);

        mockMvc.perform(post("/api/workspaces/" + workspace.getId() + "/leave")
                .session(leaverSession)
                .with(csrf().asHeader()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.activeWorkspaceId").isEmpty());

        RequestContextHolder.resetRequestAttributes();
        assertNull(workspaceMapper.getLastActiveWorkspaceId(leaver.getId()));

        mockMvc.perform(post("/api/workspaces")
                .session(leaverSession)
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Recovered after leaving\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.role").value("owner"));

        RequestContextHolder.resetRequestAttributes();
        Integer recovered = workspaceMapper.getLastActiveWorkspaceId(leaver.getId());
        assertNotNull(recovered);
        trackProvisionedWorkspace(recovered);
        assertNotEquals(workspace.getId(), recovered.intValue());

        mockMvc.perform(get("/api/workspaces").session(leaverSession))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.activeWorkspaceId").value(recovered));
    }

    /**
     * Removal from the only workspace leaves a remembered id nothing can resolve. The request must
     * fall through unresolved instead of 403, or the caller could never create or join a workspace
     * again, and the dead selection must be forgotten rather than retried forever.
     */
    @Test
    void removalFromTheOnlyWorkspaceLeavesCreationReachableAndForgetsTheDeadSelection()
            throws Exception {
        Workspace workspace = newWorkspaceInNewOrganization();
        User member = newUser();
        workspaceMapper.addMember(workspace.getId(), member.getId(), "member");
        workspaceMapper.setLastActiveWorkspaceId(member.getId(), workspace.getId());
        MockHttpSession memberSession = login(member);

        RequestContextHolder.resetRequestAttributes();
        assertEquals(1, workspaceMapper.removeMember(workspace.getId(), member.getId()));
        assertEquals(workspace.getId(), workspaceMapper.getLastActiveWorkspaceId(member.getId()));

        mockMvc.perform(post("/api/workspaces")
                .cookie(new Cookie(WorkspaceCookie.NAME, Integer.toString(workspace.getId())))
                .session(memberSession)
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Recovered after removal\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.role").value("owner"));

        RequestContextHolder.resetRequestAttributes();
        Integer recovered = workspaceMapper.getLastActiveWorkspaceId(member.getId());
        assertNotNull(recovered);
        trackProvisionedWorkspace(recovered);
        assertNotEquals(workspace.getId(), recovered.intValue());

        mockMvc.perform(get("/api/workspaces").session(memberSession))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.activeWorkspaceId").value(recovered));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void staleSelectionAllowsInviteExchangeAndAcceptanceWithoutAuthorizingForeignSwitch(
            boolean shareable) throws Exception {
        StaleBrowser browser = staleBrowser();
        Workspace target = newWorkspaceInNewOrganization();
        InviteFlow invite = inviteFlow(target, browser.user(), shareable);

        Cookie grant = exchangeInvite(browser, invite);

        mockMvc.perform(post("/api/workspaces/" + target.getId() + "/switch")
                .session(browser.session())
                .cookie(browser.staleCookie())
                .with(csrf().asHeader()))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.message").value(
                "User " + browser.user().getId() + " is not a member of this workspace"))
            .andExpect(cookie().doesNotExist(WorkspaceCookie.NAME));

        RequestContextHolder.resetRequestAttributes();
        assertFalse(workspaceMapper.isMember(target.getId(), browser.user().getId()));
        assertEquals(browser.revoked().getId(),
            workspaceMapper.getLastActiveWorkspaceId(browser.user().getId()));
        String flowId = previewInvite(browser, invite, grant, target);

        mockMvc.perform(post(invite.path() + "/accept")
                .session(browser.session())
                .cookie(browser.staleCookie(), browser.bindingCookie(), grant)
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"flowId\":\"" + flowId + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(target.getId()))
            .andExpect(jsonPath("$.role").value("member"))
            .andExpect(cookie().value(WorkspaceCookie.NAME, Integer.toString(target.getId())));

        RequestContextHolder.resetRequestAttributes();
        assertEquals("member", workspaceMapper.getRole(target.getId(), browser.user().getId()));
        assertTrue(workspaceMapper.isMember(browser.remaining().getId(), browser.user().getId()));
        assertFalse(workspaceMapper.isMember(browser.revoked().getId(), browser.user().getId()));
        assertEquals(target.getId(), workspaceMapper.getLastActiveWorkspaceId(browser.user().getId()));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void staleSelectionInviteExchangePreservesRecipientAndDomainChecks(boolean shareable)
            throws Exception {
        StaleBrowser browser = staleBrowser();
        Workspace target = newWorkspaceInNewOrganization();
        InviteFlow invite = inviteFlow(target, shareable ? browser.user() : newUser(), shareable);
        if (shareable) {
            orgAllowedDomainMapper.add(target.getOrgId(), "allowed.example");
        }

        Cookie grant = exchangeInvite(browser, invite);
        String flowId = previewInvite(browser, invite, grant, target);

        mockMvc.perform(post(invite.path() + "/accept")
                .session(browser.session())
                .cookie(browser.staleCookie(), browser.bindingCookie(), grant)
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"flowId\":\"" + flowId + "\"}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.message").value(shareable
                ? "Your email domain isn't permitted to join this workspace"
                : "This invite was sent to a different email address"))
            .andExpect(cookie().doesNotExist(WorkspaceCookie.NAME));

        RequestContextHolder.resetRequestAttributes();
        assertFalse(workspaceMapper.isMember(target.getId(), browser.user().getId()));
        assertEquals(browser.remaining().getId(),
            workspaceMapper.getLastActiveWorkspaceId(browser.user().getId()));
        if (shareable) {
            WorkspaceInviteLink stored = inviteLinkMapper.findByTokenHash(
                OneTimeTokenDigest.sha256(invite.token()));
            assertNotNull(stored);
            assertEquals(0, stored.getUsedCount());
        } else {
            WorkspaceInvite stored = inviteMapper.findByTokenHash(
                OneTimeTokenDigest.sha256(invite.token()));
            assertNotNull(stored);
            assertEquals("pending", stored.getStatus());
        }
    }

    @ParameterizedTest
    @CsvSource({
        "/api/invites/exchange, 400",
        "/api/invite-links/exchange, 400",
        "/api/delivery/unsubscribe/exchange, 404",
        "/api/document-acceptance/exchange, 503"
    })
    void staleSelectionExchangeStillEnforcesEndpointGuards(String path, int expectedStatus)
            throws Exception {
        StaleBrowser browser = staleBrowser();
        String unknownToken = UUID.randomUUID().toString().replace("-", "")
            + UUID.randomUUID().toString().replace("-", "");

        mockMvc.perform(post(path)
                .session(browser.session())
                .cookie(browser.staleCookie(), browser.bindingCookie())
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + unknownToken + "\"}"))
            .andExpect(status().is(expectedStatus))
            .andExpect(cookie().doesNotExist(WorkspaceCookie.NAME));

        RequestContextHolder.resetRequestAttributes();
        assertEquals(browser.revoked().getId(),
            workspaceMapper.getLastActiveWorkspaceId(browser.user().getId()));
        assertFalse(tenantContext.isResolved());
    }

    private StaleBrowser staleBrowser() throws Exception {
        Workspace remaining = newWorkspaceInNewOrganization();
        Workspace revoked = newWorkspaceInNewOrganization();
        User user = newUser();
        workspaceMapper.addMember(remaining.getId(), user.getId(), "member");
        workspaceMapper.addMember(revoked.getId(), user.getId(), "member");
        workspaceMapper.setLastActiveWorkspaceId(user.getId(), revoked.getId());
        MockHttpSession session = login(user);
        MvcResult bootstrap = mockMvc.perform(get("/api/auth/csrf").session(session))
            .andExpect(status().isOk())
            .andReturn();
        Cookie binding = responseCookie(bootstrap, OneTimeLinkFlowService.BROWSER_BINDING_COOKIE);
        RequestContextHolder.resetRequestAttributes();
        assertEquals(1, workspaceMapper.removeMember(revoked.getId(), user.getId()));
        return new StaleBrowser(user, remaining, revoked, session,
            new Cookie(WorkspaceCookie.NAME, Integer.toString(revoked.getId())), binding);
    }

    private InviteFlow inviteFlow(Workspace target, User recipient, boolean shareable) {
        User inviter = newUser();
        workspaceMapper.addMember(target.getId(), inviter.getId(), "owner");
        String token = OneTimeTokenDigest.generate();
        inviteTokenHashes.add(OneTimeTokenDigest.sha256(token));
        if (shareable) {
            inviteLinkMapper.insertHashed(target.getId(), OneTimeTokenDigest.sha256(token),
                "member", 14, null, inviter.getId());
            return new InviteFlow("/api/invite-links", OneTimeLinkFlowCookie.WORKSPACE_INVITE_LINK, token);
        }
        WorkspaceInvite invite = new WorkspaceInvite();
        invite.setWorkspaceId(target.getId());
        invite.setEmail(recipient.getEmail());
        invite.setRole("member");
        invite.setToken(token);
        invite.setInvitedById(inviter.getId());
        inviteMapper.insert(invite);
        return new InviteFlow("/api/invites", OneTimeLinkFlowCookie.WORKSPACE_INVITE, token);
    }

    private Cookie exchangeInvite(StaleBrowser browser, InviteFlow invite) throws Exception {
        MvcResult exchanged = mockMvc.perform(post(invite.path() + "/exchange")
                .session(browser.session())
                .cookie(browser.staleCookie(), browser.bindingCookie())
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + invite.token() + "\"}"))
            .andExpect(status().isSeeOther())
            .andExpect(cookie().doesNotExist(WorkspaceCookie.NAME))
            .andReturn();
        RequestContextHolder.resetRequestAttributes();
        assertEquals(browser.revoked().getId(),
            workspaceMapper.getLastActiveWorkspaceId(browser.user().getId()));
        assertFalse(tenantContext.isResolved());
        return responseCookie(exchanged, invite.cookieName());
    }

    private String previewInvite(StaleBrowser browser, InviteFlow invite, Cookie grant, Workspace target)
            throws Exception {
        MvcResult preview = mockMvc.perform(get(invite.path())
                .session(browser.session())
                .cookie(browser.staleCookie(), browser.bindingCookie(), grant))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.workspaceId").value(target.getId()))
            .andExpect(jsonPath("$.valid").value(true))
            .andExpect(cookie().value(WorkspaceCookie.NAME,
                Integer.toString(browser.remaining().getId())))
            .andReturn();
        String flowId = objectMapper.readTree(preview.getResponse().getContentAsString())
            .path("flowId").stringValueOpt().orElseThrow();
        assertFalse(flowId.isBlank());
        return flowId;
    }

    private static Cookie responseCookie(MvcResult result, String name) {
        Cookie cookie = result.getResponse().getCookie(name);
        assertNotNull(cookie);
        return cookie;
    }

    private record StaleBrowser(User user, Workspace remaining, Workspace revoked,
            MockHttpSession session, Cookie staleCookie, Cookie bindingCookie) {}

    private record InviteFlow(String path, String cookieName, String token) {}

    private MockHttpSession login(User user) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + user.getUsername()
                    + "\",\"password\":\"" + PASSWORD + "\"}"))
            .andExpect(status().isOk())
            .andReturn();
        return assertInstanceOf(MockHttpSession.class, result.getRequest().getSession(false));
    }

    private Workspace newWorkspaceInNewOrganization() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Organization organization = new Organization();
        organization.setName("Stale workspace org " + suffix);
        organization.setSlug("stale-workspace-org-" + suffix);
        organizationMapper.insert(organization);
        organizationIds.add(organization.getId());
        Workspace workspace = new Workspace();
        workspace.setName("Stale workspace " + suffix);
        workspace.setSlug("stale-workspace-" + suffix);
        workspace.setOrgId(organization.getId());
        workspaceMapper.insert(workspace);
        workspaceIds.add(workspace.getId());
        return workspace;
    }

    private User newUser() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User user = new User();
        user.setUsername("stale_workspace_" + suffix);
        user.setDisplayName("Stale workspace user " + suffix);
        user.setEmail(suffix + "@example.com");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setTimezone("UTC");
        userMapper.insert(user);
        userIds.add(user.getId());
        return user;
    }

    private void trackProvisionedWorkspace(int workspaceId) {
        workspaceIds.add(workspaceId);
        Integer orgId = workspaceMapper.getOrgId(workspaceId);
        if (orgId != null) {
            organizationIds.add(orgId);
        }
    }
}
