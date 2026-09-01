package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import ooo.klae.connex.backend.beans.PasskeyBootstrapConfirmationToken;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.MailTransportUnavailableException;
import ooo.klae.connex.backend.mappers.PasskeyBootstrapConfirmationTokenMapper;
import ooo.klae.connex.backend.mappers.SpringSessionMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.session.SessionEpochRestampGrant;
import ooo.klae.connex.backend.util.OneTimeTokenDigest;
import ooo.klae.connex.backend.webauthn.WebAuthnService;

/**
 * Covers the out-of-band confirmation that gates a first passkey on a privileged account (#1506).
 */
class PasskeyBootstrapConfirmationServiceTest {

    private static final String REQUESTING_SESSION = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_SESSION = "22222222-2222-2222-2222-222222222222";

    private final PasskeyBootstrapConfirmationTokenMapper tokenMapper =
        mock(PasskeyBootstrapConfirmationTokenMapper.class);
    private final PasskeyBootstrapConfirmationEmailService emailService =
        mock(PasskeyBootstrapConfirmationEmailService.class);
    private final PasskeyBootstrapConfirmationPolicy policy =
        mock(PasskeyBootstrapConfirmationPolicy.class);
    private final WebAuthnService webAuthnService = mock(WebAuthnService.class);
    private final SpringSessionMapper springSessionMapper = mock(SpringSessionMapper.class);
    private final SessionSecurityService sessionSecurityService =
        mock(SessionSecurityService.class);
    private final AuditService auditService = mock(AuditService.class);
    private final UserMapper userMapper = mock(UserMapper.class);

    private PasskeyBootstrapConfirmationService service;
    private MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        service = new PasskeyBootstrapConfirmationService(
            tokenMapper, emailService, policy, webAuthnService, springSessionMapper,
            userMapper, sessionSecurityService, auditService);
        ReflectionTestUtils.setField(service, "tokenExpiryMinutes", 30);
        ReflectionTestUtils.setField(service, "requestWindowSeconds", 900);
        ReflectionTestUtils.setField(service, "maxRequests", 5);
        request = new MockHttpServletRequest();
        request.getSession();
        when(springSessionMapper.primaryIdBySessionId(anyString())).thenReturn(REQUESTING_SESSION);
    }

    @Test
    void requestMailsASingleUseBearerAndPersistsOnlyItsDigest() {
        User user = user();
        when(webAuthnService.hasPasskey(7)).thenReturn(false);
        when(policy.requiresConfirmation(7)).thenReturn(true);
        when(emailService.canDeliver()).thenReturn(true);
        when(tokenMapper.countRecentByUser(7, 900)).thenReturn(0);

        service.request(user, request, "203.0.113.9");

        ArgumentCaptor<String> mailed = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendConfirmationEmail(eq(user), mailed.capture());
        verify(tokenMapper).invalidateForUser(7);
        verify(tokenMapper).insert(
            eq(7), eq(OneTimeTokenDigest.sha256(mailed.getValue())), eq(REQUESTING_SESSION),
            eq("203.0.113.9"), eq(30));
    }

    @Test
    void requestFailsClosedWhenTheInstanceCannotDeliverMail() {
        User user = user();
        when(webAuthnService.hasPasskey(7)).thenReturn(false);
        when(policy.requiresConfirmation(7)).thenReturn(true);
        when(emailService.canDeliver()).thenReturn(false);

        assertThrows(MailTransportUnavailableException.class,
            () -> service.request(user, request, "203.0.113.9"));

        verify(tokenMapper, never()).insert(anyInt(), anyString(), anyString(), anyString(), anyInt());
    }

    @Test
    void requestIsThrottledPerAccount() {
        User user = user();
        when(webAuthnService.hasPasskey(7)).thenReturn(false);
        when(policy.requiresConfirmation(7)).thenReturn(true);
        when(emailService.canDeliver()).thenReturn(true);
        when(tokenMapper.countRecentByUser(7, 900)).thenReturn(5);

        assertThrows(BadRequestException.class,
            () -> service.request(user, request, "203.0.113.9"));

        verify(emailService, never()).sendConfirmationEmail(any(), anyString());
    }

    @Test
    void redeemRefusesAConfirmationRequestedByADifferentSession() {
        User user = user();
        String raw = OneTimeTokenDigest.generate();
        when(tokenMapper.findRedeemableByHash(OneTimeTokenDigest.sha256(raw)))
            .thenReturn(token(7, OTHER_SESSION));

        assertThrows(BadRequestException.class, () -> service.redeem(user, raw, request));

        verify(tokenMapper, never()).markConsumed(anyString(), anyInt(), anyString());
        verify(sessionSecurityService, never()).markPasskeyBootstrapConfirmation(any(), anyInt());
    }

    @Test
    void redeemRefusesAConfirmationBelongingToAnotherAccount() {
        User user = user();
        String raw = OneTimeTokenDigest.generate();
        when(tokenMapper.findRedeemableByHash(OneTimeTokenDigest.sha256(raw)))
            .thenReturn(token(8, REQUESTING_SESSION));

        assertThrows(BadRequestException.class, () -> service.redeem(user, raw, request));

        verify(sessionSecurityService, never()).markPasskeyBootstrapConfirmation(any(), anyInt());
    }

    @Test
    void redeemRefusesWhenTheAtomicConsumeLosesTheRace() {
        User user = user();
        String raw = OneTimeTokenDigest.generate();
        String hash = OneTimeTokenDigest.sha256(raw);
        when(tokenMapper.findRedeemableByHash(hash)).thenReturn(token(7, REQUESTING_SESSION));
        when(tokenMapper.markConsumed(hash, 7, REQUESTING_SESSION)).thenReturn(0);

        assertThrows(BadRequestException.class, () -> service.redeem(user, raw, request));

        verify(sessionSecurityService, never()).markPasskeyBootstrapConfirmation(any(), anyInt());
    }

    @Test
    void redeemStampsTheRequestingSessionOnSuccess() {
        User user = user();
        String raw = OneTimeTokenDigest.generate();
        String hash = OneTimeTokenDigest.sha256(raw);
        when(tokenMapper.findRedeemableByHash(hash)).thenReturn(token(7, REQUESTING_SESSION));
        when(tokenMapper.markConsumed(hash, 7, REQUESTING_SESSION)).thenReturn(1);

        service.redeem(user, raw, request);

        verify(sessionSecurityService).markPasskeyBootstrapConfirmation(request, 7);
    }

    @Test
    void isRequiredForIsFalseOnceAPasskeyExists() {
        when(webAuthnService.hasPasskey(7)).thenReturn(true);

        assertEquals(false, service.isRequiredFor(7));
    }

    @Test
    void isSatisfiedForAcceptsTheDurablePostRecoveryGrantForTheSameSession() {
        User user = user();
        when(sessionSecurityService.hasFreshPasskeyBootstrapConfirmation(request, 7))
            .thenReturn(false);
        when(userMapper.epochRestampGrant(7))
            .thenReturn(new SessionEpochRestampGrant(REQUESTING_SESSION, 4));

        assertEquals(true, service.isSatisfiedFor(user, request));
    }

    @Test
    void isSatisfiedForRejectsAPostRecoveryGrantHeldByAnotherSession() {
        User user = user();
        when(sessionSecurityService.hasFreshPasskeyBootstrapConfirmation(request, 7))
            .thenReturn(false);
        when(userMapper.epochRestampGrant(7))
            .thenReturn(new SessionEpochRestampGrant(OTHER_SESSION, 4));

        assertEquals(false, service.isSatisfiedFor(user, request));
    }

    @Test
    void isSatisfiedForRejectsWhenNeitherProofIsPresent() {
        User user = user();
        when(sessionSecurityService.hasFreshPasskeyBootstrapConfirmation(request, 7))
            .thenReturn(false);
        when(userMapper.epochRestampGrant(7)).thenReturn(null);

        assertEquals(false, service.isSatisfiedFor(user, request));
    }

    private static User user() {
        User user = new User();
        user.setId(7);
        user.setDisplayName("Admin");
        user.setEmail("admin@example.com");
        return user;
    }

    private static PasskeyBootstrapConfirmationToken token(int userId, String sessionPrimaryId) {
        PasskeyBootstrapConfirmationToken token = new PasskeyBootstrapConfirmationToken();
        token.setUserId(userId);
        token.setSessionPrimaryId(sessionPrimaryId);
        return token;
    }
}
