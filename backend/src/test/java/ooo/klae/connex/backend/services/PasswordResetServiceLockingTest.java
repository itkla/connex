package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import jakarta.servlet.http.HttpServletRequest;
import ooo.klae.connex.backend.beans.PasswordResetToken;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.exceptions.BreachedPasswordCheckUnavailableException;
import ooo.klae.connex.backend.mappers.PasswordResetTokenMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.password.BreachedPasswordLookup;
import ooo.klae.connex.backend.password.BreachedPasswordSourceUnavailableException;
import ooo.klae.connex.backend.password.BreachedPasswordUnavailableReason;
import ooo.klae.connex.backend.password.PasswordCredentialService;

class PasswordResetServiceLockingTest {

    @Test
    void remoteScreeningPrecedesTheLocksAndPrivilegeIsRevalidatedUnderThem() {
        UserMapper userMapper = mock(UserMapper.class);
        PasswordResetTokenMapper tokenMapper = mock(PasswordResetTokenMapper.class);
        BreachedPasswordLookup lookup = mock(BreachedPasswordLookup.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        AuditService auditService = mock(AuditService.class);
        PasswordCredentialService credentialService = new PasswordCredentialService(
                lookup, encoder, userMapper, auditService);
        PasswordResetService service = new PasswordResetService(
                userMapper,
                tokenMapper,
                credentialService,
                mock(PasswordResetEmailService.class),
                mock(PasswordResetRateLimiter.class),
                auditService,
                mock(SessionRegistry.class),
                mock(SsoConnectionService.class));
        PasswordResetToken token = new PasswordResetToken();
        token.setUserId(41);
        User user = new User();
        user.setId(41);
        when(tokenMapper.findExchangedRedeemableByHash("token-hash")).thenReturn(token);
        when(userMapper.lockById(41)).thenReturn(41);
        when(userMapper.getUserById(41)).thenReturn(user);
        when(userMapper.isPrivilegedAccount(41)).thenReturn(true);
        when(lookup.isBreached(anyString())).thenThrow(
                new BreachedPasswordSourceUnavailableException(
                        BreachedPasswordUnavailableReason.TIMEOUT));

        assertThrows(BreachedPasswordCheckUnavailableException.class,
                () -> service.resetPasswordByHash("token-hash", "Candidate-2026!"));

        InOrder lockOrder = inOrder(userMapper, lookup);
        lockOrder.verify(lookup).isBreached(anyString());
        lockOrder.verify(userMapper).getUserById(41);
        lockOrder.verify(userMapper).lockById(41);
        lockOrder.verify(userMapper).lockAssignedCustomRoleIds(41);
        lockOrder.verify(userMapper).isPrivilegedAccount(41);
        verify(encoder, never()).encode(anyString());
        verify(tokenMapper, never()).markConsumed(anyString());
        verify(userMapper, never()).updatePasswordHash(eq(41), anyString());
    }

    @Test
    void resetTransactionsUseReadCommittedForPostLockPrivilegeVisibility() throws Exception {
        Transactional rawTokenTransaction = PasswordResetService.class
                .getMethod("resetPassword", String.class, String.class)
                .getAnnotation(Transactional.class);
        Transactional exchangedTokenTransaction = PasswordResetService.class
                .getMethod("resetPasswordByHash", String.class, String.class)
                .getAnnotation(Transactional.class);
        Transactional linkFlowTransaction = OneTimeLinkFlowService.class
                .getMethod(
                        "consumePasswordReset",
                        HttpServletRequest.class,
                        String.class,
                        Consumer.class)
                .getAnnotation(Transactional.class);
        Transactional genericFlowTransaction = OneTimeLinkFlowService.class
                .getMethod(
                        "consume",
                        HttpServletRequest.class,
                        OneTimeLinkFlowService.Purpose.class,
                        String.class,
                        Consumer.class)
                .getAnnotation(Transactional.class);

        assertNotNull(rawTokenTransaction);
        assertNotNull(exchangedTokenTransaction);
        assertNotNull(linkFlowTransaction);
        assertNotNull(genericFlowTransaction);
        assertEquals(Isolation.READ_COMMITTED, rawTokenTransaction.isolation());
        assertEquals(Isolation.READ_COMMITTED, exchangedTokenTransaction.isolation());
        assertEquals(Isolation.READ_COMMITTED, linkFlowTransaction.isolation());
        assertEquals(Isolation.DEFAULT, genericFlowTransaction.isolation());
    }
}
