package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.InOrder;
import org.springframework.mock.web.MockHttpServletRequest;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.config.PrivilegedMfaProperties;
import ooo.klae.connex.backend.dto.PasskeyRecoveryRequest;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.webauthn.WebAuthnService;

class MfaRecoveryServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-13T12:00:00Z");
    private final AuthService authService = mock(AuthService.class);
    private final UserMapper userMapper = mock(UserMapper.class);
    private final WebAuthnService webAuthnService = mock(WebAuthnService.class);
    private final SessionSecurityService sessionSecurityService = mock(SessionSecurityService.class);
    private final AuditService auditService = mock(AuditService.class);
    private final PrivilegedMfaProperties properties = properties();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final MfaRecoveryService service = new MfaRecoveryService(
            authService,
            userMapper,
            webAuthnService,
            sessionSecurityService,
            properties,
            auditService,
            clock);

    @BeforeEach
    void allowUserLock() {
        when(userMapper.lockById(7)).thenReturn(7);
    }

    @Test
    void recoveryRequiresBothAccountAndOperatorProofsAndAuditsWithoutTokenMaterial() {
        User user = user();
        MockHttpServletRequest httpRequest = new MockHttpServletRequest();
        PasskeyRecoveryRequest request = request("operator-proof");
        when(authService.getCurrentUser()).thenReturn(user);
        when(webAuthnService.recover(7)).thenReturn(2);

        service.recover(request, httpRequest);

        InOrder proofOrder = inOrder(userMapper, authService);
        proofOrder.verify(userMapper).lockById(7);
        proofOrder.verify(authService).requireFirstPasskeyBootstrapAuthentication(
                7, "current-password", httpRequest);
        verify(webAuthnService).recover(7);
        verify(auditService).recordStrictScoped(
                eq("auth.mfa.recovery.used"),
                eq("user"),
                eq(7),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull(),
                eq("Admin"),
                eq("Operator-authorized passkey recovery used"),
                any());
        verify(sessionSecurityService).clearRecentAuthentication(httpRequest);
    }

    @Test
    void invalidOperatorProofCannotRemoveCredentials() {
        when(authService.getCurrentUser()).thenReturn(user());
        PasskeyRecoveryRequest request = request("wrong-proof");

        assertThrows(ForbiddenException.class,
                () -> service.recover(request, new MockHttpServletRequest()));

        verify(webAuthnService, never()).recover(7);
        verify(auditService, never()).recordStrictScoped(any(), any(), any(), any(), any(), any(), any(), any());
    }

    private static PrivilegedMfaProperties properties() {
        PrivilegedMfaProperties properties = new PrivilegedMfaProperties();
        properties.setRecoveryTokenSha256(sha256Hex("operator-proof"));
        properties.setRecoveryExpiresAt(NOW.plusSeconds(1800).toString());
        properties.setRecoveryActor("security-operator");
        return properties;
    }

    private static PasskeyRecoveryRequest request(String token) {
        PasskeyRecoveryRequest request = new PasskeyRecoveryRequest();
        request.setCurrentPassword("current-password");
        request.setRecoveryToken(token);
        return request;
    }

    private static User user() {
        User user = new User();
        user.setId(7);
        user.setDisplayName("Admin");
        return user;
    }

    private static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
