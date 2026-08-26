package ooo.klae.connex.backend.password;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

import ooo.klae.connex.backend.exceptions.BreachedPasswordCheckUnavailableException;
import ooo.klae.connex.backend.exceptions.BreachedPasswordException;
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
    void overlongCandidateIsScreenedByTheBytesBcryptActuallyConsumes() {
        String breachedPrefix = "A".repeat(72);
        ArgumentCaptor<String> digest = ArgumentCaptor.forClass(String.class);
        when(lookup.isBreached(anyString())).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn(ENCODED);

        service.encode(breachedPrefix + "unique-suffix",
                PasswordScreeningFlow.SELF_REGISTRATION, null);

        verify(lookup).isBreached(digest.capture());
        assertEquals(sha1UpperHex(breachedPrefix), digest.getValue());
    }

    @Test
    void suffixCannotCarryABreachedSeventyTwoBytePrefixPastScreening() {
        String breachedPrefix = "A".repeat(72);
        when(lookup.isBreached(sha1UpperHex(breachedPrefix))).thenReturn(true);

        assertThrows(BreachedPasswordException.class,
                () -> service.encode(breachedPrefix + "unique-suffix",
                        PasswordScreeningFlow.SELF_REGISTRATION, null));

        verify(passwordEncoder, never()).encode(anyString());
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

    private static BreachedPasswordSourceUnavailableException unavailable() {
        return new BreachedPasswordSourceUnavailableException(
                BreachedPasswordUnavailableReason.TIMEOUT);
    }
}
