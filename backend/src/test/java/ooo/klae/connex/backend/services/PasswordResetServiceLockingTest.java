package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import jakarta.servlet.http.HttpServletRequest;
import ooo.klae.connex.backend.beans.PasswordResetToken;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.exceptions.BreachedPasswordCheckUnavailableException;
import ooo.klae.connex.backend.mappers.EmailChangeTokenMapper;
import ooo.klae.connex.backend.mappers.PasswordResetTokenMapper;
import ooo.klae.connex.backend.mappers.SpringSessionMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.password.BreachedPasswordLookup;
import ooo.klae.connex.backend.password.BreachedPasswordSourceUnavailableException;
import ooo.klae.connex.backend.password.BreachedPasswordUnavailableReason;
import ooo.klae.connex.backend.password.PasswordCredentialService;

class PasswordResetServiceLockingTest {
    private final UserMapper userMapper = mock(UserMapper.class);
    private final PasswordResetTokenMapper tokenMapper = mock(PasswordResetTokenMapper.class);
    private final EmailChangeTokenMapper emailChangeTokenMapper = mock(EmailChangeTokenMapper.class);
    private final BreachedPasswordLookup lookup = mock(BreachedPasswordLookup.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final AuditService auditService = mock(AuditService.class);
    private PasswordResetService service;

    @BeforeEach
    void setUp() {
        PasswordCredentialService credentialService = new PasswordCredentialService(
                lookup, encoder, userMapper, auditService);
        service = new PasswordResetService(
                userMapper,
                tokenMapper,
                emailChangeTokenMapper,
                credentialService,
                mock(PasswordResetEmailService.class),
                mock(PasswordResetRateLimiter.class),
                auditService,
                new AccountSessionRevocationService(mock(SessionRegistry.class), mock(SpringSessionMapper.class),
                    mock(ooo.klae.connex.backend.notifications.WebSocketSessionRegistry.class)),
                mock(SsoConnectionService.class));
        PasswordResetToken token = new PasswordResetToken();
        token.setUserId(41);
        token.setCredentialGeneration(0);
        User user = new User();
        user.setId(41);
        user.setSessionEpoch(0);
        when(tokenMapper.findExchangedRedeemableByHash("token-hash")).thenReturn(token);
        when(userMapper.lockById(41)).thenReturn(41);
        when(userMapper.getUserById(41)).thenReturn(user);
        when(userMapper.getUserByIdForShare(41)).thenReturn(user);
        when(lookup.isBreached(anyString())).thenThrow(
                new BreachedPasswordSourceUnavailableException(
                        BreachedPasswordUnavailableReason.TIMEOUT));
    }

    @Test
    void remoteScreeningPrecedesTheLocksAndPrivilegeIsRevalidatedUnderThem() {
        when(userMapper.isPrivilegedAccount(41)).thenReturn(true);

        List<TransactionSynchronization> synchronizations = resetInTransaction(() -> assertThrows(
                BreachedPasswordCheckUnavailableException.class,
                () -> service.resetPasswordByHash("token-hash", "Candidate-2026!")));

        verify(auditService, never()).recordStrictIndependentScoped(
                anyString(), anyString(), any(), any(), any(), anyString(), anyString(), any());
        InOrder lockOrder = inOrder(userMapper, lookup);
        lockOrder.verify(lookup).isBreached(anyString());
        lockOrder.verify(userMapper).getUserById(41);
        lockOrder.verify(userMapper).lockById(41);
        lockOrder.verify(userMapper).getUserByIdForShare(41);
        lockOrder.verify(userMapper).lockAssignedCustomRoleIds(41);
        lockOrder.verify(userMapper).isPrivilegedAccount(41);
        verify(encoder, never()).encode(anyString());
        verify(tokenMapper, never()).markConsumed(anyString());
        verify(userMapper, never()).updatePasswordHash(eq(41), anyString());

        synchronizations.forEach(synchronization -> synchronization.afterCompletion(
                TransactionSynchronization.STATUS_ROLLED_BACK));
        verify(auditService).recordStrictIndependentScoped(
                eq("auth.password.breach_check_unavailable"), eq("user"), eq(41), isNull(), isNull(),
                anyString(), anyString(), any());
    }

    @Test
    void failOpenDecisionTakesTheAuditHeadOnlyAfterBothTokenFamiliesAtCommit() {
        when(userMapper.isPrivilegedAccount(41)).thenReturn(false);
        when(encoder.encode("Candidate-2026!")).thenReturn("encoded-credential");
        when(tokenMapper.markConsumed("token-hash")).thenReturn(1);

        List<TransactionSynchronization> synchronizations = resetInTransaction(
                () -> service.resetPasswordByHash("token-hash", "Candidate-2026!"));

        verify(auditService, never()).recordStrictScoped(
                anyString(), anyString(), any(), any(), any(), anyString(), anyString(), any());
        synchronizations.forEach(synchronization -> synchronization.beforeCommit(false));
        InOrder commitOrder = inOrder(userMapper, tokenMapper, emailChangeTokenMapper, auditService);
        commitOrder.verify(userMapper).lockById(41);
        commitOrder.verify(userMapper).isPrivilegedAccount(41);
        commitOrder.verify(tokenMapper).markConsumed("token-hash");
        commitOrder.verify(userMapper).updatePasswordHash(41, "encoded-credential");
        commitOrder.verify(tokenMapper).invalidateForUser(41);
        commitOrder.verify(emailChangeTokenMapper).invalidateForUser(41);
        commitOrder.verify(auditService).recordStrictScoped(
                eq("auth.password.breach_check_unavailable"), eq("user"), eq(41), isNull(), isNull(),
                anyString(), anyString(), any());
        verify(auditService, never()).recordStrictIndependentScoped(
                anyString(), anyString(), any(), any(), any(), anyString(), anyString(), any());
    }

    private static List<TransactionSynchronization> resetInTransaction(Runnable reset) {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        try {
            reset.run();
            return TransactionSynchronizationManager.getSynchronizations();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
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
