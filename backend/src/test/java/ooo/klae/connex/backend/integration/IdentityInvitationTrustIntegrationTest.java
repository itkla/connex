package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.ObjectMapper;

import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.beans.WorkspaceRole;
import ooo.klae.connex.backend.config.OneTimeLinkFlowCookie;
import ooo.klae.connex.backend.dto.MemberDto;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.mappers.AllowedDomainMapper;
import ooo.klae.connex.backend.mappers.EmailChangeTokenMapper;
import ooo.klae.connex.backend.mappers.InviteLinkMapper;
import ooo.klae.connex.backend.mappers.OrgAllowedDomainMapper;
import ooo.klae.connex.backend.mappers.OrgMemberMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.RoleMapper;
import ooo.klae.connex.backend.mappers.TenantLifecycleControlMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.services.InviteEmailService;
import ooo.klae.connex.backend.services.InviteLinkService;
import ooo.klae.connex.backend.services.InviteService;
import ooo.klae.connex.backend.services.EmailChangeEmailService;
import ooo.klae.connex.backend.services.OneTimeLinkFlowService;
import ooo.klae.connex.backend.services.RegistrationVerificationEmailService;
import ooo.klae.connex.backend.services.RegistrationVerificationService;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.util.OneTimeTokenDigest;

/** Exercises mailbox ownership across tenant-created identities and both invitation endpoints. */
@SpringBootTest(properties = {
    "connex.registration-verification.enabled=true",
    "connex.workspaces.allow-self-service-creation=false",
    "connex.signup.mode=open"
})
class IdentityInvitationTrustIntegrationTest {
    private static final String PASSWORD = "IdentityProof9!";

    @Autowired private WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") private Filter springSecurityFilterChain;
    @Autowired private ObjectMapper objectMapper;
    @MockitoSpyBean private UserMapper userMapper;
    @MockitoSpyBean private EmailChangeTokenMapper emailChangeTokenMapper;
    @Autowired private SqlSessionTemplate sqlSessionTemplate;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private WorkspaceMapper workspaceMapper;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private RoleMapper roleMapper;
    @Autowired private AllowedDomainMapper allowedDomainMapper;
    @Autowired private OrgAllowedDomainMapper orgAllowedDomainMapper;
    @Autowired private OrgMemberMapper orgMemberMapper;
    @Autowired private TenantLifecycleControlMapper lifecycleMapper;
    @Autowired private InviteLinkMapper inviteLinkMapper;
    @Autowired private InviteLinkService inviteLinkService;
    @Autowired private InviteService inviteService;
    @Autowired private WorkspaceService workspaceService;
    @Autowired private RegistrationVerificationService verificationService;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private TenantContext tenantContext;
    @Autowired private JdbcTemplate jdbcTemplate;
    @MockitoBean private RegistrationVerificationEmailService verificationEmail;
    @MockitoBean private InviteEmailService inviteEmail;
    @MockitoBean private EmailChangeEmailService emailChangeEmail;

    private MockMvc mockMvc;
    private Workspace target;
    private User manager;
    private MockHttpSession managerSession;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .addFilters(springSecurityFilterChain)
            .build();
        target = newWorkspace();
        manager = memberManager(target);
        managerSession = login(manager);
        PublicApiTestSecuritySupport.stepUp(managerSession, manager.getId());
        orgAllowedDomainMapper.add(target.getOrgId(), "victim.example");
    }

    @Test
    void tenantCreatedAccountCannotRedeemAnotherOrganizationsDomainRestrictedLink() throws Exception {
        Workspace source = newWorkspace();
        User sourceManager = memberManager(source);
        MockHttpSession sourceSession = login(sourceManager);
        PublicApiTestSecuritySupport.stepUp(sourceSession, sourceManager.getId());
        String username = "forged_" + unique();
        String address = unique() + "@victim.example";

        mockMvc.perform(post("/api/users")
                .session(sourceSession)
                .header("X-Workspace-Id", source.getId())
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(registration(username, address)))
            .andExpect(status().isOk());

        User forged = userMapper.getUserByUsername(username);
        assertNotNull(forged);
        assertFalse(forged.isEmailVerified());
        assertTrue(workspaceMapper.getMembershipsForUser(forged.getId()).isEmpty());
        assertTrue(verificationService.validateToken(verificationTokenFor(forged)));
        assertFalse(workspaceMapper.isMember(target.getId(), sourceManager.getId()));

        allowedDomainMapper.add(target.getId(), "victim.example");
        String link = OneTimeTokenDigest.generate();
        inviteLinkMapper.insertHashed(
            target.getId(), OneTimeTokenDigest.sha256(link), "member", 14, null, manager.getId());
        Browser browser = bootstrap(login(forged));
        Flow flow = exchange("/api/invite-links", OneTimeLinkFlowCookie.WORKSPACE_INVITE_LINK, link, browser);

        mockMvc.perform(post("/api/invite-links/accept")
                .session(browser.session())
                .cookie(browser.bindingCookie(), flow.cookie())
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("flowId", flow.id()))))
            .andExpect(result -> assertEquals(403, result.getResponse().getStatus(),
                () -> String.valueOf(result.getResolvedException())))
            .andExpect(jsonPath("$.message").value("Verify your email address before joining this workspace"));

        assertNoMembership(forged);
        assertCrmDenied(browser.session());

        verificationService.confirm(verificationTokenFor(forged));
        assertTrue(workspaceMapper.getMembershipsForUser(forged.getId()).isEmpty());
        mockMvc.perform(post("/api/invite-links/accept")
                .session(browser.session())
                .cookie(browser.bindingCookie(), flow.cookie())
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("flowId", flow.id()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(target.getId()));
        assertTrue(workspaceMapper.isMember(target.getId(), forged.getId()));
    }

    @Test
    void creatorVisibleInviteTokenCannotVerifyAccountForAnotherOrganizationsGrants() throws Exception {
        Workspace source = newWorkspace();
        User sourceManager = memberManager(source);
        MockHttpSession sourceSession = login(sourceManager);
        PublicApiTestSecuritySupport.stepUp(sourceSession, sourceManager.getId());
        String username = "forged_" + unique();
        String address = unique() + "@victim.example";

        mockMvc.perform(post("/api/users")
                .session(sourceSession)
                .header("X-Workspace-Id", source.getId())
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(registration(username, address)))
            .andExpect(status().isOk());

        User forged = userMapper.getUserByUsername(username);
        assertNotNull(forged);
        assertFalse(forged.isEmailVerified());
        assertTrue(workspaceMapper.getMembershipsForUser(forged.getId()).isEmpty());
        assertFalse(workspaceMapper.isMember(target.getId(), sourceManager.getId()));

        MvcResult created = mockMvc.perform(post("/api/workspaces/{id}/invites", source.getId())
                .session(sourceSession).header("X-Workspace-Id", source.getId())
                .with(csrf().asHeader()).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("email", address, "role", "member"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.member").doesNotExist())
            .andReturn();
        String token = objectMapper.readTree(created.getResponse().getContentAsString())
            .get("invite").get("token").asString();
        Browser browser = bootstrap(login(forged));
        Flow invitation = exchange("/api/invites", OneTimeLinkFlowCookie.WORKSPACE_INVITE, token, browser);
        mockMvc.perform(post("/api/invites/accept")
                .session(browser.session())
                .cookie(browser.bindingCookie(), invitation.cookie())
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("flowId", invitation.id()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(source.getId()));
        assertTrue(workspaceMapper.isMember(source.getId(), forged.getId()));

        String link = OneTimeTokenDigest.generate();
        inviteLinkMapper.insertHashed(
            target.getId(), OneTimeTokenDigest.sha256(link), "member", 14, null, manager.getId());
        Flow restrictedLink = exchange(
            "/api/invite-links", OneTimeLinkFlowCookie.WORKSPACE_INVITE_LINK, link, browser);
        mockMvc.perform(post("/api/invite-links/accept")
                .session(browser.session())
                .cookie(browser.bindingCookie(), restrictedLink.cookie())
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("flowId", restrictedLink.id()))))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.message").value("Verify your email address before joining this workspace"));
        assertNoMembership(forged);

        invite("members", address)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("This account's email is unverified; send an invite instead"));
        assertNoMembership(forged);
        assertCrmDenied(browser.session());
        User persisted = userMapper.getUserById(forged.getId());
        assertNotNull(persisted);
        assertFalse(persisted.isEmailVerified());
    }

    @Test
    void unverifiedPreregistrationReceivesOnlyEmailTokenInvite() throws Exception {
        Account account = preregister();

        MvcResult created = invite("invites", account.user().getEmail())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.member").doesNotExist())
            .andExpect(jsonPath("$.invite.email").value(account.user().getEmail()))
            .andReturn();
        String token = objectMapper.readTree(created.getResponse().getContentAsString())
            .get("invite").get("token").asString();
        verify(inviteEmail).sendInvite(
            target.getId(), target.getName(), account.user().getEmail(), manager.getDisplayName(), "member", token);
        assertNoMembership(account.user());
        mockMvc.perform(get("/api/workspaces/pending").session(account.session()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(post("/api/workspaces/{id}/accept", target.getId())
                .session(account.session()).with(csrf().asHeader()))
            .andExpect(status().isNotFound());
        assertCrmDenied(account.session());

        Browser browser = bootstrap(account.session());
        Flow flow = exchange("/api/invites", OneTimeLinkFlowCookie.WORKSPACE_INVITE, token, browser);
        mockMvc.perform(post("/api/invites/accept")
                .session(browser.session())
                .cookie(browser.bindingCookie(), flow.cookie())
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("flowId", flow.id()))))
            .andExpect(result -> assertEquals(200, result.getResponse().getStatus(),
                () -> String.valueOf(result.getResolvedException())));
        assertTrue(workspaceMapper.isMember(target.getId(), account.user().getId()));
        User accepted = userMapper.getUserById(account.user().getId());
        assertNotNull(accepted);
        assertFalse(accepted.isEmailVerified());

        Workspace second = newWorkspace();
        User secondManager = memberManager(second);
        MockHttpSession secondSession = login(secondManager);
        PublicApiTestSecuritySupport.stepUp(secondSession, secondManager.getId());
        mockMvc.perform(post("/api/workspaces/{id}/members", second.getId())
                .session(secondSession).header("X-Workspace-Id", second.getId())
                .with(csrf().asHeader()).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    Map.of("email", account.user().getEmail(), "role", "member"))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("This account's email is unverified; send an invite instead"));
        assertFalse(workspaceMapper.isMemberIncludingPending(second.getId(), account.user().getId()));

        verificationService.confirm(verificationTokenFor(account.user()));
        User verified = userMapper.getUserById(account.user().getId());
        assertNotNull(verified);
        assertTrue(verified.isEmailVerified());
        mockMvc.perform(post("/api/workspaces/{id}/members", second.getId())
                .session(secondSession).header("X-Workspace-Id", second.getId())
                .with(csrf().asHeader()).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    Map.of("email", account.user().getEmail(), "role", "member"))))
            .andExpect(result -> assertEquals(200, result.getResponse().getStatus(),
                () -> String.valueOf(result.getResolvedException())));
        assertTrue(workspaceMapper.isMemberIncludingPending(second.getId(), account.user().getId()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/invites", "/api/invite-links"})
    void workspaceLessRecipientCannotAcceptAfterCreatorLosesPermission(String path) throws Exception {
        Account account = preregister();
        String token;
        String cookieName;
        if ("/api/invites".equals(path)) {
            MvcResult created = invite("invites", account.user().getEmail())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.member").doesNotExist())
                .andReturn();
            token = objectMapper.readTree(created.getResponse().getContentAsString())
                .get("invite").get("token").asString();
            cookieName = OneTimeLinkFlowCookie.WORKSPACE_INVITE;
        } else {
            token = OneTimeTokenDigest.generate();
            inviteLinkMapper.insertHashed(target.getId(), OneTimeTokenDigest.sha256(token),
                "member", 14, null, manager.getId());
            cookieName = OneTimeLinkFlowCookie.WORKSPACE_INVITE_LINK;
        }
        verificationService.confirm(verificationTokenFor(account.user()));
        Browser browser = bootstrap(account.session());
        Flow flow = exchange(path, cookieName, token, browser);

        MemberDto managerMembership = workspaceMapper.getMember(target.getId(), manager.getId());
        assertNotNull(managerMembership);
        Integer roleId = managerMembership.getRoleId();
        assertNotNull(roleId);
        tenantContext.set(target.getId(), target.getOrgId(), manager.getId(), "member", null);
        try {
            List<String> permissions = roleMapper.findPermissions(target.getId(), roleId).stream()
                .filter(permission -> !Permission.MEMBER_MANAGE.name().equals(permission)).toList();
            roleMapper.clearPermissions(target.getId(), roleId);
            roleMapper.insertPermissions(target.getId(), roleId, permissions);
        } finally {
            tenantContext.clear();
        }

        mockMvc.perform(post(path + "/accept")
                .session(browser.session())
                .cookie(browser.bindingCookie(), flow.cookie())
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("flowId", flow.id()))))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.message").value(
                "Requires the MEMBER_MANAGE permission in this workspace"));
        assertNoMembership(account.user());
        assertCrmDenied(browser.session());
    }

    @Test
    void membersEndpointRejectsUnverifiedPreregistration() throws Exception {
        Account account = preregister();

        invite("members", account.user().getEmail())
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("This account's email is unverified; send an invite instead"));

        assertNoMembership(account.user());
        mockMvc.perform(post("/api/workspaces/{id}/accept", target.getId())
                .session(account.session()).with(csrf().asHeader()))
            .andExpect(status().isNotFound());
        assertCrmDenied(account.session());
    }

    @Test
    void legacyPendingMembershipCannotActivateUntilEmailIsVerified() throws Exception {
        Account account = preregister();
        workspaceMapper.addPendingMember(target.getId(), account.user().getId(), "member");

        mockMvc.perform(post("/api/workspaces/{id}/accept", target.getId())
                .session(account.session()).with(csrf().asHeader()))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.message").value("Verify your email address before joining this workspace"));
        assertEquals("pending", workspaceMapper.getMember(target.getId(), account.user().getId()).getStatus());
        assertFalse(workspaceMapper.isMember(target.getId(), account.user().getId()));
        assertCrmDenied(account.session());

        verificationService.confirm(verificationTokenFor(account.user()));
        mockMvc.perform(post("/api/workspaces/{id}/accept", target.getId())
                .session(account.session()).with(csrf().asHeader()))
            .andExpect(status().isOk());
        assertTrue(workspaceMapper.isMember(target.getId(), account.user().getId()));
    }

    @Test
    void inviteLinkChecksCurrentVerifiedAddressAgainstOrgDomain() throws Exception {
        assertStaleInviteLinkIdentityRejected();
    }

    @Test
    void inviteLinkChecksCurrentVerifiedAddressAgainstWorkspaceDomain() throws Exception {
        orgAllowedDomainMapper.remove(target.getOrgId(), "victim.example");
        allowedDomainMapper.add(target.getId(), "victim.example");
        assertStaleInviteLinkIdentityRejected();
    }

    @Test
    void profileMapperCannotRestoreUnverifiedEmailAfterConfirmation() throws Exception {
        Account account = preregister();
        User staleProfile = userMapper.getUserById(account.user().getId());
        assertNotNull(staleProfile);
        String ownedAddress = unique() + "@attacker.example";
        confirmEmailChange(prepareEmailChange(account, ownedAddress));

        staleProfile.setDisplayName("Updated profile");
        userMapper.update(staleProfile);

        User persisted = userMapper.getUserById(account.user().getId());
        assertNotNull(persisted);
        assertEquals(ownedAddress, persisted.getEmail());
        assertTrue(persisted.isEmailVerified());
        assertEquals("Updated profile", persisted.getDisplayName());
    }

    @Test
    void emailChangeRevokesLegacyPendingGrantsAcrossOrganizations() throws Exception {
        Account account = preregister();
        orgAllowedDomainMapper.remove(target.getOrgId(), "victim.example");
        Workspace other = newWorkspace();
        Workspace active = newWorkspace();
        workspaceMapper.addPendingMember(target.getId(), account.user().getId(), "member");
        workspaceMapper.addPendingMember(other.getId(), account.user().getId(), "member");
        workspaceMapper.addMember(active.getId(), account.user().getId(), "member");
        lifecycleMapper.markWorkspaceTearingDown(other.getOrgId(), other.getId());
        lifecycleMapper.markOrganizationTearingDown(other.getOrgId());

        confirmEmailChange(prepareEmailChange(account, unique() + "@attacker.example"));

        assertTrue(userMapper.getUserById(account.user().getId()).isEmailVerified());
        assertNoMembership(account.user());
        assertFalse(workspaceMapper.isMemberIncludingPending(other.getId(), account.user().getId()));
        assertTrue(workspaceMapper.isMember(active.getId(), account.user().getId()));
        assertPendingNotFound(account);
        assertCrmDenied(account.session());
    }

    @Test
    void emailConfirmationRevokesPendingGrantCommittedWhileWaitingForUserLock() throws Exception {
        Account account = preregister();
        orgAllowedDomainMapper.remove(target.getOrgId(), "victim.example");
        EmailChangeFlow flow = prepareEmailChange(account, unique() + "@attacker.example");
        UserMapper realUserMapper = sqlSessionTemplate.getMapper(UserMapper.class);
        CountDownLatch invitationLocked = new CountDownLatch(1);
        CountDownLatch commitInvitation = new CountDownLatch(1);
        CountDownLatch confirmationLockAttempted = new CountDownLatch(1);
        doAnswer(invocation -> {
            confirmationLockAttempted.countDown();
            return realUserMapper.lockById(account.user().getId());
        }).when(userMapper).lockById(account.user().getId());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> invitation = executor.submit(() -> new TransactionTemplate(transactionManager)
                .executeWithoutResult(transaction -> {
                    realUserMapper.lockById(account.user().getId());
                    workspaceMapper.lockWorkspace(target.getId());
                    invitationLocked.countDown();
                    await(commitInvitation);
                    workspaceMapper.addPendingMember(target.getId(), account.user().getId(), "member");
                }));
            assertTrue(invitationLocked.await(15, TimeUnit.SECONDS));
            Future<?> confirmation = executor.submit(() -> {
                confirmEmailChange(flow);
                return null;
            });
            assertTrue(confirmationLockAttempted.await(15, TimeUnit.SECONDS));
            commitInvitation.countDown();
            invitation.get(30, TimeUnit.SECONDS);
            confirmation.get(30, TimeUnit.SECONDS);
        } finally {
            commitInvitation.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS));
        }

        assertTrue(userMapper.getUserById(account.user().getId()).isEmailVerified());
        assertNoMembership(account.user());
        assertPendingNotFound(account);
    }

    @Test
    void replacementRequestRevalidatesAfterPreviousBrowserConfirmationCompletes() throws Exception {
        Account account = preregister();
        String confirmedAddress = unique() + "@attacker.example";
        String replacementAddress = unique() + "@attacker.example";
        EmailChangeFlow flow = prepareEmailChange(account, confirmedAddress);
        EmailChangeTokenMapper realTokenMapper = sqlSessionTemplate.getMapper(EmailChangeTokenMapper.class);
        CountDownLatch requestRead = new CountDownLatch(1);
        CountDownLatch resumeRequest = new CountDownLatch(1);
        AtomicBoolean preliminaryRead = new AtomicBoolean(true);
        doAnswer(invocation -> {
            int count = realTokenMapper.countRecentByUser(account.user().getId(), 900);
            if (preliminaryRead.getAndSet(false)) {
                requestRead.countDown();
                await(resumeRequest);
            }
            return count;
        }).when(emailChangeTokenMapper).countRecentByUser(account.user().getId(), 900);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> replacement = executor.submit(() -> {
                requestEmailChange(account, replacementAddress);
                return null;
            });
            assertTrue(requestRead.await(15, TimeUnit.SECONDS));
            confirmEmailChange(flow);
            resumeRequest.countDown();
            replacement.get(30, TimeUnit.SECONDS);
        } finally {
            resumeRequest.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS));
        }

        ArgumentCaptor<User> recipient = ArgumentCaptor.forClass(User.class);
        ArgumentCaptor<String> destination = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
        verify(emailChangeEmail, times(2))
            .sendVerificationEmail(recipient.capture(), destination.capture(), token.capture());
        assertEquals(confirmedAddress, recipient.getValue().getEmail());
        assertEquals(replacementAddress, destination.getValue());
        assertFalse(emailChangeTokenMapper.existsRedeemableByHash(
            OneTimeTokenDigest.sha256(token.getAllValues().getFirst())));
        assertTrue(emailChangeTokenMapper.existsRedeemableByHash(OneTimeTokenDigest.sha256(token.getValue())));
        assertEquals(confirmedAddress, userMapper.getUserById(account.user().getId()).getEmail());
    }

    /**
     * Pauses confirmation after its user lock and releases it when replacement reaches contention.
     * The insert probe also exercises the pre-fix path, where invalidation already holds the old
     * token and insertion waits for the user foreign key, completing the deadlock cycle.
     */
    @Test
    void replacementRequestWaitsForBrowserConfirmationBeforeLockingOldToken() throws Exception {
        Account account = preregister();
        String confirmedAddress = unique() + "@attacker.example";
        String replacementAddress = unique() + "@attacker.example";
        EmailChangeFlow flow = prepareEmailChange(account, confirmedAddress);
        UserMapper realUserMapper = sqlSessionTemplate.getMapper(UserMapper.class);
        EmailChangeTokenMapper realTokenMapper = sqlSessionTemplate.getMapper(EmailChangeTokenMapper.class);
        CountDownLatch requestRead = new CountDownLatch(1);
        CountDownLatch resumeRequest = new CountDownLatch(1);
        CountDownLatch confirmationLocked = new CountDownLatch(1);
        CountDownLatch resumeConfirmation = new CountDownLatch(1);
        CountDownLatch requestContention = new CountDownLatch(1);
        AtomicBoolean preliminaryRead = new AtomicBoolean(true);
        AtomicReference<Thread> requestThread = new AtomicReference<>();
        doAnswer(invocation -> {
            int count = realTokenMapper.countRecentByUser(account.user().getId(), 900);
            if (preliminaryRead.getAndSet(false)) {
                requestRead.countDown();
                await(resumeRequest);
            }
            return count;
        }).when(emailChangeTokenMapper).countRecentByUser(account.user().getId(), 900);
        doAnswer(invocation -> {
            if (Thread.currentThread() == requestThread.get()) {
                requestContention.countDown();
                return realUserMapper.lockById(account.user().getId());
            }
            Integer locked = realUserMapper.lockById(account.user().getId());
            assertNotNull(locked);
            confirmationLocked.countDown();
            await(resumeConfirmation);
            return locked;
        }).when(userMapper).lockById(account.user().getId());
        doAnswer(invocation -> {
            requestContention.countDown();
            return realTokenMapper.insert(
                invocation.getArgument(0, Integer.class), invocation.getArgument(1, String.class),
                invocation.getArgument(2, String.class), invocation.getArgument(3, String.class),
                invocation.getArgument(4, Integer.class));
        }).when(emailChangeTokenMapper).insert(
            eq(account.user().getId()), eq(replacementAddress), anyString(), anyString(), anyInt());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> replacement = executor.submit(() -> {
                requestThread.set(Thread.currentThread());
                requestEmailChange(account, replacementAddress);
                return null;
            });
            assertTrue(requestRead.await(15, TimeUnit.SECONDS));
            Future<?> confirmation = executor.submit(() -> {
                confirmEmailChange(flow);
                return null;
            });
            assertTrue(confirmationLocked.await(15, TimeUnit.SECONDS));
            resumeRequest.countDown();
            assertTrue(requestContention.await(15, TimeUnit.SECONDS));
            resumeConfirmation.countDown();
            confirmation.get(30, TimeUnit.SECONDS);
            replacement.get(30, TimeUnit.SECONDS);
        } finally {
            resumeRequest.countDown();
            resumeConfirmation.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS));
        }

        assertEquals(confirmedAddress, userMapper.getUserById(account.user().getId()).getEmail());
        verify(emailChangeEmail).sendVerificationEmail(
            argThat(user -> user != null && confirmedAddress.equals(user.getEmail())),
            eq(replacementAddress), anyString());
    }

    @Test
    void sameBrowserExchangeRetryRejectsTokenReplacedWhileWaitingForUserLock() throws Exception {
        Account account = preregister();
        EmailChangeFlow flow = prepareEmailChange(account, unique() + "@attacker.example");
        ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
        verify(emailChangeEmail).sendVerificationEmail(
            any(User.class), anyString(), token.capture());
        UserMapper realUserMapper = sqlSessionTemplate.getMapper(UserMapper.class);
        CountDownLatch exchangeLockAttempted = new CountDownLatch(1);
        CountDownLatch resumeExchange = new CountDownLatch(1);
        AtomicReference<Thread> exchangeThread = new AtomicReference<>();
        doAnswer(invocation -> {
            if (Thread.currentThread() == exchangeThread.get()) {
                exchangeLockAttempted.countDown();
                await(resumeExchange);
            }
            return realUserMapper.lockById(account.user().getId());
        }).when(userMapper).lockById(account.user().getId());

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> exchange = executor.submit(() -> {
                exchangeThread.set(Thread.currentThread());
                mockMvc.perform(post("/api/auth/email-change/exchange")
                        .session(flow.browser().session()).cookie(flow.browser().bindingCookie())
                        .with(csrf().asHeader()).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("token", token.getValue()))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("This verification link is invalid or has expired"));
                return null;
            });
            assertTrue(exchangeLockAttempted.await(15, TimeUnit.SECONDS));
            requestEmailChange(account, unique() + "@attacker.example");
            resumeExchange.countDown();
            exchange.get(30, TimeUnit.SECONDS);
        } finally {
            resumeExchange.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS));
        }
    }

    @Test
    void organizationAddByEmailRequiresMailboxVerification() throws Exception {
        Account account = preregister();
        orgMemberMapper.addMember(target.getOrgId(), manager.getId(), "owner");
        String body = objectMapper.writeValueAsString(Map.of(
            "email", account.user().getEmail(), "orgRole", "admin"));

        mockMvc.perform(post("/api/orgs/{id}/members", target.getOrgId())
                .session(managerSession).with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(
                "The account must verify its email before joining the organization"));
        assertFalse(orgMemberMapper.isMember(target.getOrgId(), account.user().getId()));

        verificationService.confirm(verificationTokenFor(account.user()));
        mockMvc.perform(post("/api/orgs/{id}/members", target.getOrgId())
                .session(managerSession).with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isNoContent());
        assertEquals("admin", orgMemberMapper.getRole(target.getOrgId(), account.user().getId()));
    }

    @Test
    void organizationRoleWriteByIdRequiresMailboxVerification() throws Exception {
        Account account = preregister();
        orgMemberMapper.addMember(target.getOrgId(), manager.getId(), "owner");
        String body = objectMapper.writeValueAsString(Map.of("orgRole", "admin"));

        mockMvc.perform(put("/api/orgs/{id}/members/{userId}", target.getOrgId(), account.user().getId())
                .session(managerSession).with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(
                "The account must verify its email before joining the organization"));
        assertFalse(orgMemberMapper.isMember(target.getOrgId(), account.user().getId()));

        verificationService.confirm(verificationTokenFor(account.user()));
        mockMvc.perform(put("/api/orgs/{id}/members/{userId}", target.getOrgId(), account.user().getId())
                .session(managerSession).with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isNoContent());
        assertEquals("admin", orgMemberMapper.getRole(target.getOrgId(), account.user().getId()));
    }

    @Test
    void organizationRoleWriteForExistingUnverifiedMemberStaysAllowed() throws Exception {
        Account account = preregister();
        orgMemberMapper.addMember(target.getOrgId(), manager.getId(), "owner");
        orgMemberMapper.addMember(target.getOrgId(), account.user().getId(), "admin");

        mockMvc.perform(put("/api/orgs/{id}/members/{userId}", target.getOrgId(), account.user().getId())
                .session(managerSession).with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("orgRole", "owner"))))
            .andExpect(result -> assertEquals(204, result.getResponse().getStatus(),
                () -> String.valueOf(result.getResolvedException())));
        assertEquals("owner", orgMemberMapper.getRole(target.getOrgId(), account.user().getId()));
        assertFalse(userMapper.getUserById(account.user().getId()).isEmailVerified());

        mockMvc.perform(delete("/api/orgs/{id}/members/{userId}", target.getOrgId(), account.user().getId())
                .session(managerSession).with(csrf().asHeader()))
            .andExpect(status().isNoContent());
        assertFalse(orgMemberMapper.isMember(target.getOrgId(), account.user().getId()));
    }

    @Test
    void emailedInviteAcceptanceChecksCurrentAddressAgainstOrgDomain() throws Exception {
        Account account = preregister();
        MvcResult created = invite("invites", account.user().getEmail())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.member").doesNotExist())
            .andReturn();
        String token = objectMapper.readTree(created.getResponse().getContentAsString())
            .get("invite").get("token").asString();
        String ownedAddress = unique() + "@attacker.example";
        confirmEmailChange(prepareEmailChange(account, ownedAddress));
        User stale = account.user();
        assertEquals(ownedAddress, userMapper.getUserById(stale.getId()).getEmail());

        ForbiddenException denied = assertThrows(ForbiddenException.class,
            () -> inviteService.acceptInvite(token, stale));
        assertEquals("This organization only allows members from approved email domains",
            denied.getMessage());
        assertNoMembership(account.user());
    }

    @Test
    void accountCreationRequiresAuthenticationAndWorkspacePermission() throws Exception {
        String body = registration("unauthorized_" + unique(), unique() + "@victim.example");
        mockMvc.perform(post("/api/users")
                .with(csrf().asHeader()).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isUnauthorized());
        User member = newUser();
        workspaceMapper.addMember(target.getId(), member.getId(), "member");
        mockMvc.perform(post("/api/users")
                .session(login(member)).header("X-Workspace-Id", target.getId())
                .with(csrf().asHeader()).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isForbidden());
    }

    private ResultActions invite(String path, String address) throws Exception {
        return mockMvc.perform(post("/api/workspaces/{id}/" + path, target.getId())
            .session(managerSession).header("X-Workspace-Id", target.getId())
            .with(csrf().asHeader()).contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of("email", address, "role", "member"))));
    }

    private void assertStaleInviteLinkIdentityRejected() throws Exception {
        Account account = preregister();
        String token = OneTimeTokenDigest.generate();
        inviteLinkMapper.insertHashed(target.getId(), OneTimeTokenDigest.sha256(token),
            "member", 14, null, manager.getId());
        String ownedAddress = unique() + "@attacker.example";
        confirmEmailChange(prepareEmailChange(account, ownedAddress));
        User changed = userMapper.getUserById(account.user().getId());
        assertNotNull(changed);
        assertEquals(ownedAddress, changed.getEmail());
        assertTrue(changed.isEmailVerified());
        assertFalse(account.user().isEmailVerified());

        ForbiddenException denied = assertThrows(ForbiddenException.class,
            () -> inviteLinkService.redeemLink(token, account.user()));
        assertEquals("Your email domain isn't permitted to join this workspace", denied.getMessage());
        assertNoMembership(account.user());
        assertEquals(0, inviteLinkMapper.findByTokenHash(OneTimeTokenDigest.sha256(token)).getUsedCount());
    }

    private EmailChangeFlow prepareEmailChange(Account account, String address) throws Exception {
        requestEmailChange(account, address);
        ArgumentCaptor<User> recipient = ArgumentCaptor.forClass(User.class);
        ArgumentCaptor<String> destination = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
        verify(emailChangeEmail).sendVerificationEmail(recipient.capture(), destination.capture(), token.capture());
        assertEquals(account.user().getId(), recipient.getValue().getId());
        assertEquals(address, destination.getValue());
        Browser browser = bootstrap(account.session());
        MvcResult exchange = mockMvc.perform(post("/api/auth/email-change/exchange")
                .session(browser.session()).cookie(browser.bindingCookie()).with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("token", token.getValue()))))
            .andExpect(status().isSeeOther()).andReturn();
        return new EmailChangeFlow(browser, responseCookie(exchange, OneTimeLinkFlowCookie.EMAIL_CHANGE));
    }

    private void requestEmailChange(Account account, String address) throws Exception {
        mockMvc.perform(post("/api/users/me/email-change")
                .session(account.session()).with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "newEmail", address, "currentPassword", PASSWORD))))
            .andExpect(status().isOk());
    }

    private void confirmEmailChange(EmailChangeFlow flow) throws Exception {
        mockMvc.perform(post("/api/auth/email-change/confirm")
                .session(flow.browser().session()).cookie(flow.browser().bindingCookie(), flow.cookie())
                .with(csrf().asHeader()))
            .andExpect(status().isOk());
    }

    private void assertPendingNotFound(Account account) throws Exception {
        mockMvc.perform(post("/api/workspaces/{id}/accept", target.getId())
                .session(account.session()).with(csrf().asHeader()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.message").value("No pending invitation for this workspace"));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(15, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Pending invitation was not released");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Pending invitation was interrupted", exception);
        }
    }

    private Account preregister() throws Exception {
        String username = "preclaimed_" + unique();
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(registration(username, unique() + "@victim.example")))
            .andExpect(status().isOk()).andReturn();
        User user = userMapper.getUserByUsername(username);
        assertNotNull(user);
        assertFalse(user.isEmailVerified());
        assertTrue(workspaceMapper.getMembershipsForUser(user.getId()).isEmpty());
        return new Account(user, session(result));
    }

    private String verificationTokenFor(User user) {
        ArgumentCaptor<User> recipient = ArgumentCaptor.forClass(User.class);
        ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
        verify(verificationEmail).sendVerificationEmail(recipient.capture(), token.capture());
        assertEquals(user.getId(), recipient.getValue().getId());
        return token.getValue();
    }

    private void assertNoMembership(User user) {
        assertFalse(workspaceMapper.isMemberIncludingPending(target.getId(), user.getId()));
    }

    private void assertCrmDenied(MockHttpSession session) throws Exception {
        mockMvc.perform(get("/api/companies/page").session(session)
                .header("X-Workspace-Id", target.getId()))
            .andExpect(status().isForbidden());
    }

    private Browser bootstrap(MockHttpSession session) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/auth/csrf").session(session))
            .andExpect(status().isOk()).andReturn();
        return new Browser(session(result), responseCookie(result, OneTimeLinkFlowService.BROWSER_BINDING_COOKIE));
    }

    private Flow exchange(String path, String cookieName, String token, Browser browser) throws Exception {
        MvcResult exchanged = mockMvc.perform(post(path + "/exchange")
                .session(browser.session()).cookie(browser.bindingCookie())
                .with(csrf().asHeader()).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("token", token))))
            .andExpect(status().isSeeOther()).andReturn();
        Cookie cookie = responseCookie(exchanged, cookieName);
        MvcResult preview = mockMvc.perform(get(path).session(browser.session())
                .cookie(browser.bindingCookie(), cookie))
            .andExpect(status().isOk()).andReturn();
        return new Flow(cookie, objectMapper.readTree(preview.getResponse().getContentAsString()).get("flowId").asString());
    }

    private MockHttpSession login(User user) throws Exception {
        return session(mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("username", user.getUsername(), "password", PASSWORD))))
            .andExpect(status().isOk()).andReturn());
    }

    private String registration(String username, String address) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
            "username", username, "displayName", "Employee", "email", address, "password", PASSWORD));
    }

    private User memberManager(Workspace workspace) {
        User user = newUser();
        workspaceMapper.addMember(workspace.getId(), user.getId(), "member");
        tenantContext.set(workspace.getId(), workspace.getOrgId(), user.getId(), "member", null);
        try {
            WorkspaceRole role = new WorkspaceRole();
            role.setWorkspaceId(workspace.getId());
            role.setName("Member manager " + unique());
            roleMapper.insertRole(role);
            List<String> permissions = new ArrayList<>(workspaceService.builtInRoles().stream()
                .filter(candidate -> "member".equals(candidate.getName())).findFirst().orElseThrow().getPermissions());
            permissions.add(Permission.MEMBER_MANAGE.name());
            roleMapper.insertPermissions(workspace.getId(), role.getId(), permissions);
            workspaceMapper.setMemberCustomRole(workspace.getId(), user.getId(), role.getId());
        } finally {
            tenantContext.clear();
        }
        PublicApiTestSecuritySupport.enrollPasskey(jdbcTemplate, user);
        return user;
    }

    private Workspace newWorkspace() {
        Organization organization = new Organization();
        organization.setName("Identity trust " + unique());
        organization.setSlug("identity-trust-" + unique());
        organizationMapper.insert(organization);
        Workspace workspace = new Workspace();
        workspace.setOrgId(organization.getId());
        workspace.setName("Identity trust " + unique());
        workspace.setSlug("identity-trust-" + unique());
        workspaceMapper.insert(workspace);
        return workspace;
    }

    private User newUser() {
        User user = new User();
        user.setUsername("identity_" + unique());
        user.setDisplayName("Identity " + unique());
        user.setEmail(unique() + "@example.com");
        user.setEmailVerified(true);
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setTimezone("UTC");
        userMapper.insert(user);
        return user;
    }

    private static MockHttpSession session(MvcResult result) {
        return assertInstanceOf(MockHttpSession.class, result.getRequest().getSession(false));
    }

    private static Cookie responseCookie(MvcResult result, String name) {
        String header = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream()
            .filter(value -> value.startsWith(name + "=")).findFirst().orElseThrow();
        return new Cookie(name, header.substring(name.length() + 1, header.indexOf(';')));
    }

    private static String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private record Account(User user, MockHttpSession session) {}
    private record Browser(MockHttpSession session, Cookie bindingCookie) {}
    private record Flow(Cookie cookie, String id) {}
    private record EmailChangeFlow(Browser browser, Cookie cookie) {}
}
