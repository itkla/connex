package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import ooo.klae.connex.backend.beans.FederatedIdentity;
import ooo.klae.connex.backend.beans.SsoConnection;
import ooo.klae.connex.backend.beans.SsoLinkChallenge;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.WorkspaceLifecycleRef;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.FederatedIdentityMapper;
import ooo.klae.connex.backend.mappers.SsoConnectionMapper;
import ooo.klae.connex.backend.mappers.SsoDomainMapper;
import ooo.klae.connex.backend.mappers.SsoLinkChallengeMapper;
import ooo.klae.connex.backend.mappers.TenantLifecycleControlMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.util.ClientIpResolver.ResolvedClientIp;

@ExtendWith(MockitoExtension.class)
class SsoLifecycleFenceUnitTest {
    private static final int ORG_ID = 3;
    private static final int USER_ID = 7;

    @Mock private SsoLinkChallengeMapper challengeMapper;
    @Mock private FederatedIdentityMapper identityMapper;
    @Mock private TenantLifecycleControlMapper lifecycleMapper;
    @Mock private UserMapper userMapper;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private LoginRateLimiter loginRateLimiter;
    @Mock private AuthService authService;
    @Mock private AuditService auditService;
    @Mock private SsoConnectionMapper connectionMapper;
    @Mock private SsoDomainMapper domainMapper;
    @Mock private WorkspaceService workspaceService;
    @Mock private OrgAllowedDomainService allowedDomainService;
    @Mock private SsoUserProvisioner userProvisioner;
    @Mock private FreshMembershipTransaction freshMembershipTransaction;
    @Mock private TransactionTemplate transactionTemplate;

    private SsoLinkService linkService;
    private SsoLoginService loginService;

    @BeforeEach
    void setUp() {
        linkService = new SsoLinkService(
            challengeMapper,
            identityMapper,
            lifecycleMapper,
            userMapper,
            passwordEncoder,
            loginRateLimiter,
            authService,
            auditService);
        loginService = new SsoLoginService(
            identityMapper,
            userMapper,
            connectionMapper,
            domainMapper,
            lifecycleMapper,
            workspaceService,
            allowedDomainService,
            userProvisioner,
            auditService,
            freshMembershipTransaction,
            transactionTemplate);
        when(lifecycleMapper.lockActiveOrganizationForShare(ORG_ID)).thenReturn(null);
    }

    @Test
    void challengeCreationChecksTheLifecycleFenceBeforeChallengeSideEffects() {
        SsoLoginResult.LinkRequired required =
            new SsoLoginResult.LinkRequired(
                USER_ID,
                "oidc",
                "https://issuer.example",
                "subject",
                ORG_ID);
        User user = new User();
        user.setId(USER_ID);
        when(userMapper.getUserByIdForShare(USER_ID)).thenReturn(user);

        assertThrows(ForbiddenException.class, () -> linkService.createChallenge(required));

        InOrder order = inOrder(userMapper, lifecycleMapper);
        order.verify(userMapper).getUserByIdForShare(USER_ID);
        order.verify(lifecycleMapper).lockActiveOrganizationForShare(ORG_ID);
        verifyNoInteractions(challengeMapper, identityMapper, authService);
    }

    @Test
    void challengeCreationRevalidatesThatTheLockedAccountStillHasAPassword() {
        SsoLoginResult.LinkRequired required =
            new SsoLoginResult.LinkRequired(
                USER_ID,
                "oidc",
                "https://issuer.example",
                "subject",
                ORG_ID);
        User user = new User();
        user.setId(USER_ID);
        when(userMapper.getUserByIdForShare(USER_ID)).thenReturn(user);
        when(lifecycleMapper.lockActiveOrganizationForShare(ORG_ID)).thenReturn(ORG_ID);

        assertThrows(ResourceNotFoundException.class, () -> linkService.createChallenge(required));

        verifyNoInteractions(challengeMapper, identityMapper, authService, auditService);
    }

    @Test
    void challengeConfirmationChecksTheFenceBeforeConsumptionIdentityOrSessionSideEffects() {
        SsoLinkChallenge challenge = new SsoLinkChallenge();
        challenge.setId(11);
        challenge.setUserId(USER_ID);
        challenge.setOrgId(ORG_ID);
        User user = new User();
        user.setId(USER_ID);
        when(challengeMapper.findByTokenHash(org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(challenge);
        when(userMapper.getUserByIdForShare(USER_ID)).thenReturn(user);

        assertThrows(
            ForbiddenException.class,
            () -> linkService.confirm(
                "token",
                "password",
                new ResolvedClientIp("203.0.113.7", false),
                new MockHttpServletRequest(),
                new MockHttpServletResponse()));

        verify(challengeMapper, never()).markConsumed(11);
        verifyNoInteractions(identityMapper, passwordEncoder, authService, auditService);
    }

    @Test
    void challengeConfirmationLocksUserOrganizationAndChallengeBeforePasswordSideEffects() {
        SsoLinkChallenge challenge = new SsoLinkChallenge();
        challenge.setId(11);
        challenge.setTokenHash("stored-hash");
        challenge.setUserId(USER_ID);
        challenge.setOrgId(ORG_ID);
        User user = new User();
        user.setId(USER_ID);
        user.setUsername("linked-user");
        user.setPasswordHash("encoded-password");
        when(challengeMapper.findByTokenHash(anyString())).thenReturn(challenge);
        when(userMapper.getUserByIdForShare(USER_ID)).thenReturn(user);
        when(lifecycleMapper.lockActiveOrganizationForShare(ORG_ID)).thenReturn(ORG_ID);
        when(challengeMapper.lockByTokenHash("stored-hash")).thenReturn(challenge);

        assertThrows(
            BadCredentialsException.class,
            () -> linkService.confirm(
                "token",
                "wrong-password",
                new ResolvedClientIp("172.20.5.10", true),
                new MockHttpServletRequest(),
                new MockHttpServletResponse()));

        InOrder order = inOrder(
            challengeMapper,
            userMapper,
            lifecycleMapper,
            loginRateLimiter,
            passwordEncoder);
        order.verify(challengeMapper).findByTokenHash(anyString());
        order.verify(userMapper).getUserByIdForShare(USER_ID);
        order.verify(lifecycleMapper).lockActiveOrganizationForShare(ORG_ID);
        order.verify(challengeMapper).lockByTokenHash("stored-hash");
        order.verify(loginRateLimiter).isBlockedForClient(
            eq(new ResolvedClientIp("172.20.5.10", true)),
            eq("linked-user"),
            anyLong());
        order.verify(passwordEncoder).matches("wrong-password", "encoded-password");
        order.verify(loginRateLimiter).recordFailureForClient(
            eq(new ResolvedClientIp("172.20.5.10", true)),
            eq("linked-user"),
            anyLong());
        verify(challengeMapper, never()).markConsumed(11);
        verifyNoInteractions(identityMapper, authService, auditService);
    }

    @Test
    void newJitResolutionLocksStoredWorkspaceBeforeTheOrganizationFence() {
        SsoConnection connection = new SsoConnection();
        connection.setOrgId(ORG_ID);
        connection.setJitWorkspaceId(9);
        connection.setDefaultRole("member");
        when(domainMapper.findOrgByDomain("example.test")).thenReturn(ORG_ID);
        when(allowedDomainService.isJoinAllowed(ORG_ID, "user@example.test")).thenReturn(true);
        when(connectionMapper.findByOrg(ORG_ID)).thenReturn(connection);
        when(freshMembershipTransaction.execute(eq(9), any())).thenAnswer(invocation ->
            ((Supplier<?>) invocation.getArgument(1)).get());
        when(lifecycleMapper.lockWorkspaceForShare(9)).thenReturn(
            new WorkspaceLifecycleRef(9, ORG_ID, "JIT", "jit", "active"));

        assertThrows(
            ForbiddenException.class,
            () -> loginService.resolve(
                "oidc",
                "https://issuer.example",
                "subject",
                "user@example.test",
                true,
                ORG_ID,
                "User"));

        InOrder order = inOrder(lifecycleMapper);
        order.verify(lifecycleMapper).lockWorkspaceForShare(9);
        order.verify(lifecycleMapper).lockActiveOrganizationForShare(ORG_ID);
        verify(identityMapper, never()).insert(org.mockito.ArgumentMatchers.any());
        verify(identityMapper, never()).touchLogin(org.mockito.ArgumentMatchers.anyInt());
        verify(userProvisioner, never()).provision(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyBoolean());
        verifyNoInteractions(workspaceService, auditService);
    }

    @Test
    void returningIdentityLocksItsUserBeforeTheOrganizationFence() {
        FederatedIdentity identity = new FederatedIdentity();
        identity.setId(17);
        identity.setUserId(USER_ID);
        identity.setOrgId(ORG_ID);
        identity.setProvider("oidc");
        identity.setIssuer("https://issuer.example");
        identity.setExternalSubject("subject");
        User user = new User();
        user.setId(USER_ID);
        when(identityMapper.findByOrgProviderIssuerSubject(
                ORG_ID,
                "oidc",
                "https://issuer.example",
                "subject"))
            .thenReturn(identity);
        when(userMapper.getUserByIdForShare(USER_ID)).thenReturn(user);
        when(transactionTemplate.execute(any())).thenAnswer(invocation ->
            ((TransactionCallback<?>) invocation.getArgument(0))
                .doInTransaction(new SimpleTransactionStatus()));

        assertThrows(
            ForbiddenException.class,
            () -> loginService.resolve(
                "oidc",
                "https://issuer.example",
                "subject",
                "user@example.test",
                true,
                ORG_ID,
                "User"));

        InOrder order = inOrder(identityMapper, userMapper, lifecycleMapper);
        order.verify(identityMapper).findByOrgProviderIssuerSubject(
            ORG_ID,
            "oidc",
            "https://issuer.example",
            "subject");
        order.verify(userMapper).getUserByIdForShare(USER_ID);
        order.verify(lifecycleMapper).lockActiveOrganizationForShare(ORG_ID);
        verify(identityMapper, never()).touchLogin(17);
    }
}
