package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import ooo.klae.connex.backend.beans.EmailChangeToken;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.mappers.EmailChangeTokenMapper;
import ooo.klae.connex.backend.mappers.NotificationMapper;
import ooo.klae.connex.backend.mappers.PasswordResetTokenMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.notifications.NotificationStateVersionService;
import ooo.klae.connex.backend.util.OneTimeTokenDigest;
import ooo.klae.connex.backend.webauthn.WebAuthnService;

/** Pins root-before-token ordering for the programmatic confirmation entry point. */
class EmailChangeServiceLockOrderTest {

    @Test
    void programmaticConfirmationLocksUserBeforeClaimingOrConsumingToken() {
        UserMapper userMapper = mock(UserMapper.class);
        WorkspaceMapper workspaceMapper = mock(WorkspaceMapper.class);
        EmailChangeTokenMapper tokenMapper = mock(EmailChangeTokenMapper.class);
        EmailChangeService service = new EmailChangeService(
            userMapper, workspaceMapper, mock(NotificationMapper.class),
            mock(NotificationStateVersionService.class), tokenMapper,
            mock(PasswordResetTokenMapper.class), mock(EmailChangeEmailService.class),
            mock(AuthService.class), mock(SessionSecurityService.class), mock(AuditService.class),
            mock(LoginRateLimiter.class), mock(PrivilegedAccountService.class), mock(WebAuthnService.class));
        String rawToken = OneTimeTokenDigest.generate();
        String tokenHash = OneTimeTokenDigest.sha256(rawToken);
        User user = new User();
        user.setId(41);
        user.setEmail("previous@example.com");
        user.setSessionEpoch(0);
        EmailChangeToken token = new EmailChangeToken();
        token.setUserId(user.getId());
        token.setCredentialGeneration(user.getSessionEpoch());
        token.setNewEmail("next@example.com");
        when(tokenMapper.findRedeemableByHash(tokenHash)).thenReturn(token);
        when(tokenMapper.findExchangedRedeemableByHash(tokenHash)).thenReturn(token);
        when(userMapper.lockById(user.getId())).thenReturn(user.getId());
        when(userMapper.getUserByIdForShare(user.getId())).thenReturn(user);
        when(tokenMapper.claimExchange(eq(tokenHash), anyString())).thenReturn(1);
        when(tokenMapper.markConsumed(tokenHash)).thenReturn(1);
        when(workspaceMapper.findPendingGrants(user.getId())).thenReturn(List.of());

        assertTrue(service.confirmChange(rawToken).isEmpty());

        InOrder order = inOrder(userMapper, tokenMapper);
        order.verify(userMapper).lockById(user.getId());
        order.verify(tokenMapper).claimExchange(eq(tokenHash), anyString());
        order.verify(userMapper).lockById(user.getId());
        order.verify(tokenMapper).markConsumed(tokenHash);
        order.verify(userMapper).updateEmail(user.getId(), token.getNewEmail());
        order.verify(tokenMapper).invalidateForUser(user.getId());
    }
}
