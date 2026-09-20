package ooo.klae.connex.backend.password;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import ooo.klae.connex.backend.exceptions.BreachedPasswordCheckUnavailableException;
import ooo.klae.connex.backend.exceptions.BreachedPasswordException;
import ooo.klae.connex.backend.exceptions.PasswordTooLongException;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.services.AuditService;

class PasswordCredentialServiceTest {
    private static final String CANDIDATE = "Sensitive-Candidate-2026!";
    private static final String ENCODED = "encoded-credential";

    private final BreachedPasswordLookup lookup = mock(BreachedPasswordLookup.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final UserMapper userMapper = mock(UserMapper.class);
    private final AuditService auditService = mock(AuditService.class);
    private PasswordCredentialService service;

    @BeforeEach
    void setUp() {
        service = new PasswordCredentialService(lookup, passwordEncoder, userMapper, auditService);
    }

    @Test
    void candidateOfExactlySeventyTwoBytesIsScreenedWholeAndEncoded() {
        String candidate = "Aa1!" + "a".repeat(68);
        ArgumentCaptor<String> digest = ArgumentCaptor.forClass(String.class);
        when(lookup.isBreached(anyString())).thenReturn(false);
        when(passwordEncoder.encode(candidate)).thenReturn(ENCODED);

        assertEquals(ENCODED, service.encode(candidate, PasswordScreeningFlow.SELF_REGISTRATION, null));

        verify(lookup).isBreached(digest.capture());
        assertEquals(sha1UpperHex(candidate), digest.getValue());
    }

    @Test
    void multiByteCandidateOfExactlySeventyTwoBytesIsEncoded() {
        String candidate = "\u3042".repeat(24);
        when(lookup.isBreached(anyString())).thenReturn(false);
        when(passwordEncoder.encode(candidate)).thenReturn(ENCODED);

        assertEquals(ENCODED, service.encode(candidate, PasswordScreeningFlow.BOOTSTRAP_OWNER, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ascii", "multibyte-final", "multibyte-short"})
    void candidateOverSeventyTwoBytesIsRejectedBeforeAnyBreachLookup(String shape) {
        String candidate = switch (shape) {
            case "ascii" -> "Aa1!" + "a".repeat(69);
            case "multibyte-final" -> "Aa1!" + "a".repeat(67) + "\u00e9";
            default -> "\u3042".repeat(25);
        };

        PasswordTooLongException exception = assertThrows(PasswordTooLongException.class,
                () -> service.encode(candidate, PasswordScreeningFlow.SELF_SERVICE_RESET, 42));

        assertEquals("newPassword", exception.getField());
        assertFalse(exception.getMessage().contains(candidate));
        verify(lookup, never()).isBreached(anyString());
        verify(passwordEncoder, never()).encode(anyString());
        verifyNoInteractions(auditService, userMapper);
    }

    @Test
    void screeningRejectsAnOverlongCandidateForTheFlowField() {
        PasswordTooLongException exception = assertThrows(PasswordTooLongException.class,
                () -> service.screen("A".repeat(73), PasswordScreeningFlow.SELF_REGISTRATION));

        assertEquals("password", exception.getField());
        verify(lookup, never()).isBreached(anyString());
    }

    @Test
    void encodeScreenedRefusesAnOverlongCandidateEvenAfterACleanScreening() {
        assertThrows(PasswordTooLongException.class,
                () -> service.encodeScreened(PasswordScreening.clean(), "A".repeat(73),
                        PasswordScreeningFlow.SELF_SERVICE_RESET, 42));

        verify(passwordEncoder, never()).encode(anyString());
        verifyNoInteractions(auditService, userMapper);
    }

    @Test
    void candidateWithinTheLimitIsScreenedWhole() {
        ArgumentCaptor<String> digest = ArgumentCaptor.forClass(String.class);
        when(lookup.isBreached(anyString())).thenReturn(false);
        when(passwordEncoder.encode(CANDIDATE)).thenReturn(ENCODED);

        service.encode(CANDIDATE, PasswordScreeningFlow.SELF_REGISTRATION, null);

        verify(lookup).isBreached(digest.capture());
        assertEquals(sha1UpperHex(CANDIDATE), digest.getValue());
    }

    @Test
    void locallyInducedCapacityRefusalFailsClosedForSelfServiceReset() {
        when(lookup.isBreached(anyString())).thenThrow(
                new BreachedPasswordSourceUnavailableException(
                        BreachedPasswordUnavailableReason.CAPACITY));
        when(userMapper.isPrivilegedAccount(11)).thenReturn(false);

        assertThrows(BreachedPasswordCheckUnavailableException.class,
                () -> service.encode(CANDIDATE, PasswordScreeningFlow.SELF_SERVICE_RESET, 11));

        verify(passwordEncoder, never()).encode(anyString());
    }

    @Test
    void upstreamOutageStillFailsOpenForAnUnprivilegedSelfServiceReset() {
        when(lookup.isBreached(anyString())).thenThrow(
                new BreachedPasswordSourceUnavailableException(
                        BreachedPasswordUnavailableReason.UPSTREAM));
        when(userMapper.isPrivilegedAccount(11)).thenReturn(false);
        when(passwordEncoder.encode(CANDIDATE)).thenReturn(ENCODED);

        assertEquals(ENCODED,
                service.encode(CANDIDATE, PasswordScreeningFlow.SELF_SERVICE_RESET, 11));
    }

    @Test
    void screeningReadsNoPrivilegeUntilThePolicyDecision() {
        when(lookup.isBreached(anyString())).thenReturn(false);

        PasswordScreening screening = service.screen(CANDIDATE, PasswordScreeningFlow.SELF_SERVICE_RESET);

        assertTrue(screening.answered());
        verify(userMapper, never()).isPrivilegedAccount(anyInt());
    }

    @Test
    void unavailableScreeningDefersItsFailOpenDecisionToEncodeScreened() {
        when(lookup.isBreached(anyString())).thenThrow(
                new BreachedPasswordSourceUnavailableException(
                        BreachedPasswordUnavailableReason.TIMEOUT));
        when(userMapper.isPrivilegedAccount(11)).thenReturn(true);

        PasswordScreening screening = service.screen(CANDIDATE, PasswordScreeningFlow.SELF_SERVICE_RESET);
        assertFalse(screening.answered());
        verify(userMapper, never()).isPrivilegedAccount(anyInt());

        assertThrows(BreachedPasswordCheckUnavailableException.class,
                () -> service.encodeScreened(
                        screening, CANDIDATE, PasswordScreeningFlow.SELF_SERVICE_RESET, 11));
    }

    private static String sha1UpperHex(String value) {
        try {
            return java.util.HexFormat.of().withUpperCase().formatHex(
                    java.security.MessageDigest.getInstance("SHA-1")
                            .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @Test
    void knownBreachedPasswordIsRejectedBeforeEncoding() {
        when(lookup.isBreached(anyString())).thenReturn(true);

        BreachedPasswordException exception = assertThrows(BreachedPasswordException.class,
                () -> service.encode(CANDIDATE, PasswordScreeningFlow.SELF_REGISTRATION, null));

        assertEquals("password", exception.getField());
        assertFalse(exception.getMessage().contains(CANDIDATE));
        verify(passwordEncoder, never()).encode(anyString());
    }

    @Test
    void uniquePasswordIsEncodedAfterLocalDigestLookup() {
        ArgumentCaptor<String> digest = ArgumentCaptor.forClass(String.class);
        when(lookup.isBreached(anyString())).thenReturn(false);
        when(passwordEncoder.encode(CANDIDATE)).thenReturn(ENCODED);

        String result = service.encode(CANDIDATE, PasswordScreeningFlow.ADMIN_ACCOUNT_CREATION, null);

        assertEquals(ENCODED, result);
        verify(lookup).isBreached(digest.capture());
        assertEquals(40, digest.getValue().length());
        assertFalse(digest.getValue().contains(CANDIDATE));
    }

    @ParameterizedTest
    @EnumSource(value = PasswordScreeningFlow.class, names = {
            "SELF_REGISTRATION", "ADMIN_ACCOUNT_CREATION", "BOOTSTRAP_OWNER"
    })
    void accountCreationAndBootstrapFailClosedWhenSourceIsUnavailable(
            PasswordScreeningFlow flow) {
        when(lookup.isBreached(anyString())).thenThrow(unavailable());

        BreachedPasswordCheckUnavailableException exception = assertThrows(
                BreachedPasswordCheckUnavailableException.class,
                () -> service.encode(CANDIDATE, flow, null));

        assertEquals("password", exception.getField());
        verify(passwordEncoder, never()).encode(anyString());
    }

    @Test
    void privilegedResetFailsClosedWhenSourceIsUnavailable() {
        when(lookup.isBreached(anyString())).thenThrow(unavailable());
        when(userMapper.isPrivilegedAccount(41)).thenReturn(true);

        BreachedPasswordCheckUnavailableException exception = assertThrows(
                BreachedPasswordCheckUnavailableException.class,
                () -> service.encode(CANDIDATE, PasswordScreeningFlow.SELF_SERVICE_RESET, 41));

        assertEquals("newPassword", exception.getField());
        verify(passwordEncoder, never()).encode(anyString());
    }

    @Test
    void nonPrivilegedResetFailsOpenOnlyAfterSanitizedDurableAudit() {
        when(lookup.isBreached(anyString())).thenThrow(unavailable());
        when(userMapper.isPrivilegedAccount(42)).thenReturn(false);
        when(passwordEncoder.encode(CANDIDATE)).thenReturn(ENCODED);
        ArgumentCaptor<Object> auditChanges = ArgumentCaptor.forClass(Object.class);

        String result = service.encode(
                CANDIDATE, PasswordScreeningFlow.SELF_SERVICE_RESET, 42);

        assertEquals(ENCODED, result);
        verify(auditService).recordStrictIndependentScoped(
                eq("auth.password.breach_check_unavailable"),
                eq("user"),
                eq(42),
                isNull(),
                isNull(),
                eq("password-policy"),
                eq("Breached-password policy decision"),
                auditChanges.capture());
        String persistedMetadata = auditChanges.getValue().toString();
        assertFalse(persistedMetadata.contains(CANDIDATE));
        assertFalse(persistedMetadata.matches(".*[0-9A-F]{40}.*"));
        assertFalse(persistedMetadata.contains("Sensitive"));
    }

    @Test
    void nonPrivilegedResetFailsClosedIfIndependentAuditCannotBePersisted() {
        when(lookup.isBreached(anyString())).thenThrow(unavailable());
        when(userMapper.isPrivilegedAccount(42)).thenReturn(false);
        doThrow(new IllegalStateException("audit unavailable"))
                .when(auditService).recordStrictIndependentScoped(
                        anyString(), anyString(), eq(42), isNull(), isNull(), anyString(),
                        anyString(), any());

        assertThrows(IllegalStateException.class,
                () -> service.encode(
                        CANDIDATE, PasswordScreeningFlow.SELF_SERVICE_RESET, 42));

        verify(passwordEncoder, never()).encode(anyString());
    }

    @Test
    void sourceErrorDetailNeverReachesExceptionOrAuditMetadata() {
        when(lookup.isBreached(anyString())).thenThrow(
                new BreachedPasswordSourceUnavailableException(
                        BreachedPasswordUnavailableReason.UPSTREAM));
        ArgumentCaptor<Object> auditChanges = ArgumentCaptor.forClass(Object.class);

        BreachedPasswordCheckUnavailableException exception = assertThrows(
                BreachedPasswordCheckUnavailableException.class,
                () -> service.encode(CANDIDATE, PasswordScreeningFlow.SELF_REGISTRATION, null));

        verify(auditService).recordStrictIndependentScoped(
                anyString(), anyString(), isNull(), isNull(), isNull(), anyString(), anyString(),
                auditChanges.capture());
        assertFalse(exception.getMessage().contains(CANDIDATE));
        assertFalse(auditChanges.getValue().toString().contains(CANDIDATE));
    }

    @Test
    void offlineSourceLossFailsClosedForNonPrivilegedReset() {
        when(lookup.isBreached(anyString())).thenThrow(
                new BreachedPasswordSourceUnavailableException(
                        BreachedPasswordUnavailableReason.OFFLINE_SOURCE));
        when(userMapper.isPrivilegedAccount(42)).thenReturn(false);

        assertThrows(BreachedPasswordCheckUnavailableException.class,
                () -> service.encode(
                        CANDIDATE, PasswordScreeningFlow.SELF_SERVICE_RESET, 42));

        verify(passwordEncoder, never()).encode(anyString());
    }

    @Test
    void failOpenInsideATransactionIsAuditedInThatTransactionBeforeCommit() {
        when(lookup.isBreached(anyString())).thenThrow(unavailable());
        when(userMapper.isPrivilegedAccount(42)).thenReturn(false);
        when(passwordEncoder.encode(CANDIDATE)).thenReturn(ENCODED);
        ArgumentCaptor<Object> auditChanges = ArgumentCaptor.forClass(Object.class);

        TransactionSynchronization synchronization = inTransaction(() -> assertEquals(ENCODED,
                service.encode(CANDIDATE, PasswordScreeningFlow.SELF_SERVICE_RESET, 42)));
        verifyNoInteractions(auditService);

        synchronization.beforeCommit(false);

        verify(auditService).recordStrictScoped(
                eq("auth.password.breach_check_unavailable"),
                eq("user"),
                eq(42),
                isNull(),
                isNull(),
                eq("password-policy"),
                eq("Breached-password policy decision"),
                auditChanges.capture());
        assertEquals(Map.of("flow", "self_service_reset", "decision", "fail_open", "reason", "timeout"),
                auditChanges.getValue());
        synchronization.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
        verify(auditService, never()).recordStrictIndependentScoped(
                anyString(), anyString(), any(), any(), any(), anyString(), anyString(), any());
    }

    @Test
    void failOpenAuditFailureBeforeCommitAbortsTheCommit() {
        when(lookup.isBreached(anyString())).thenThrow(unavailable());
        when(userMapper.isPrivilegedAccount(42)).thenReturn(false);
        when(passwordEncoder.encode(CANDIDATE)).thenReturn(ENCODED);
        IllegalStateException auditFailure = new IllegalStateException("audit unavailable");
        doThrow(auditFailure).when(auditService).recordStrictScoped(
                anyString(), anyString(), eq(42), isNull(), isNull(), anyString(), anyString(), any());

        TransactionSynchronization synchronization = inTransaction(
                () -> service.encode(CANDIDATE, PasswordScreeningFlow.SELF_SERVICE_RESET, 42));

        assertSame(auditFailure, assertThrows(IllegalStateException.class,
                () -> synchronization.beforeCommit(false)));
    }

    @Test
    void failClosedInsideATransactionIsAuditedIndependentlyOnlyAfterCompletion() {
        when(lookup.isBreached(anyString())).thenThrow(
                new BreachedPasswordSourceUnavailableException(
                        BreachedPasswordUnavailableReason.CAPACITY));
        when(userMapper.isPrivilegedAccount(42)).thenReturn(false);
        ArgumentCaptor<Object> auditChanges = ArgumentCaptor.forClass(Object.class);

        TransactionSynchronization synchronization = inTransaction(() -> assertThrows(
                BreachedPasswordCheckUnavailableException.class,
                () -> service.encode(CANDIDATE, PasswordScreeningFlow.SELF_SERVICE_RESET, 42)));
        verifyNoInteractions(auditService);

        synchronization.beforeCommit(false);
        verifyNoInteractions(auditService);
        synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

        verify(auditService).recordStrictIndependentScoped(
                eq("auth.password.breach_check_unavailable"),
                eq("user"),
                eq(42),
                isNull(),
                isNull(),
                eq("password-policy"),
                eq("Breached-password policy decision"),
                auditChanges.capture());
        assertEquals(Map.of("flow", "self_service_reset", "decision", "fail_closed", "reason", "capacity"),
                auditChanges.getValue());
        verify(auditService, never()).recordStrictScoped(
                anyString(), anyString(), any(), any(), any(), anyString(), anyString(), any());
        verify(passwordEncoder, never()).encode(anyString());
    }

    @Test
    void failClosedAuditFailureAfterCompletionLeavesTheRefusalStanding() {
        when(lookup.isBreached(anyString())).thenThrow(unavailable());
        doThrow(new IllegalStateException("audit unavailable"))
                .when(auditService).recordStrictIndependentScoped(
                        anyString(), anyString(), isNull(), isNull(), isNull(), anyString(),
                        anyString(), any());

        TransactionSynchronization synchronization = inTransaction(() -> assertThrows(
                BreachedPasswordCheckUnavailableException.class,
                () -> service.encode(CANDIDATE, PasswordScreeningFlow.SELF_REGISTRATION, null)));

        assertDoesNotThrow(() -> synchronization.afterCompletion(
                TransactionSynchronization.STATUS_ROLLED_BACK));
        verify(auditService).recordStrictIndependentScoped(
                anyString(), anyString(), isNull(), isNull(), isNull(), anyString(), anyString(), any());
    }

    @Test
    void failClosedWithoutATransactionIsAuditedIndependentlyAtOnce() {
        when(lookup.isBreached(anyString())).thenThrow(unavailable());
        when(userMapper.isPrivilegedAccount(41)).thenReturn(true);

        assertThrows(BreachedPasswordCheckUnavailableException.class,
                () -> service.encode(CANDIDATE, PasswordScreeningFlow.SELF_SERVICE_RESET, 41));

        verify(auditService).recordStrictIndependentScoped(
                eq("auth.password.breach_check_unavailable"), eq("user"), eq(41), isNull(), isNull(),
                eq("password-policy"), eq("Breached-password policy decision"),
                eq(Map.of("flow", "self_service_reset", "decision", "fail_closed", "reason", "timeout")));
    }

    @Test
    void cleanScreeningInsideATransactionWritesNoAudit() {
        when(lookup.isBreached(anyString())).thenReturn(false);
        when(passwordEncoder.encode(CANDIDATE)).thenReturn(ENCODED);

        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        try {
            assertEquals(ENCODED, service.encode(CANDIDATE, PasswordScreeningFlow.SELF_SERVICE_RESET, 42));
            assertTrue(TransactionSynchronizationManager.getSynchronizations().isEmpty());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        verifyNoInteractions(auditService, userMapper);
    }

    private static TransactionSynchronization inTransaction(Runnable work) {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        try {
            work.run();
            List<TransactionSynchronization> synchronizations =
                    TransactionSynchronizationManager.getSynchronizations();
            assertEquals(1, synchronizations.size());
            return synchronizations.get(0);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    private static BreachedPasswordSourceUnavailableException unavailable() {
        return new BreachedPasswordSourceUnavailableException(
                BreachedPasswordUnavailableReason.TIMEOUT);
    }
}
