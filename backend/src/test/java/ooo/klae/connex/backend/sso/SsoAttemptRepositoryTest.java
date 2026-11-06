package ooo.klae.connex.backend.sso;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.saml2.provider.service.authentication.AbstractSaml2AuthenticationRequest;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistration;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistrationRepository;

/** Protects destination metadata against cross-site, disabled-protocol, interleaved and unmatched requests. */
@ExtendWith(MockitoExtension.class)
class SsoAttemptRepositoryTest {
    @Mock private CompositeClientRegistrationRepository oauthRegistrations;
    @Mock private RelyingPartyRegistrationRepository samlRegistrations;

    @ParameterizedTest
    @ValueSource(strings = {"cross-site", "same-site", "none"})
    @NullSource
    void destinationStartsRequireSameOriginMetadataBeforeConfigOrSessionAccess(String site) throws Exception {
        var request = start("/api/oauth2/authorization/google", "/invite", site);
        var response = new MockHttpServletResponse();
        var called = new AtomicBoolean();

        filter(true).doFilter(request, response, (incoming, outgoing) -> called.set(true));

        assertEquals(403, response.getStatus());
        assertEquals(false, called.get());
        assertNull(request.getSession(false));
        verifyNoInteractions(oauthRegistrations, samlRegistrations);
    }

    @Test
    void ordinaryStartsDelegateWithoutDestinationOrConfigurationSideEffects() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/oauth2/authorization/google");
        var called = new AtomicBoolean();
        filter(false).doFilter(request, new MockHttpServletResponse(), (incoming, outgoing) -> called.set(true));
        assertEquals(true, called.get());
        assertNull(request.getSession(false));
        verifyNoInteractions(oauthRegistrations, samlRegistrations);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/oauth2/authorization/google", "/saml2/authenticate/org-7", "/saml2/authenticate"})
    void fourCrossSiteImageStartsAllocateNothing(String path) throws Exception {
        var session = new MockHttpSession();
        for (int index = 0; index < 4; index++) {
            var request = new MockHttpServletRequest("GET", path);
            request.setSession(session);
            request.addParameter("registrationId", "org-7");
            request.addHeader("Sec-Fetch-Site", "cross-site");
            request.addHeader("Sec-Fetch-Mode", "no-cors");
            request.addHeader("Sec-Fetch-Dest", "image");
            var response = new MockHttpServletResponse();
            filter(true).doFilter(request, response, (incoming, outgoing) -> {
                throw new AssertionError("Cross-site image must not reach an attempt-allocating filter");
            });
            assertEquals(403, response.getStatus());
            assertEquals("no-store", response.getHeader("Cache-Control"));
        }
        assertEquals(false, session.getAttributeNames().hasMoreElements());
        verifyNoInteractions(oauthRegistrations, samlRegistrations);
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"})
    void crossSiteSubresourceGuardCoversEveryStartMethodAndRoute(String method) throws Exception {
        for (String path : new String[] {"/api/oauth2/authorization/google", "/saml2/authenticate/org-7", "/saml2/authenticate"}) {
            var request = new MockHttpServletRequest(method, path);
            request.addHeader("Sec-Fetch-Site", "cross-site");
            request.addHeader("Sec-Fetch-Mode", "no-cors");
            request.addHeader("Sec-Fetch-Dest", "image");
            var response = new MockHttpServletResponse();
            filter(true).doFilter(request, response, (incoming, outgoing) -> {
                throw new AssertionError("No start method may allocate a cross-site image attempt");
            });
            assertEquals(403, response.getStatus());
            assertNull(request.getSession(false));
        }
        verifyNoInteractions(oauthRegistrations, samlRegistrations);
    }

    @ParameterizedTest
    @CsvSource({"navigate,image", "no-cors,document", "navigate,", ",document", ","})
    void crossSiteStartsNeedBothTopLevelNavigationHeaders(String mode, String destination) throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/oauth2/authorization/google");
        request.addHeader("Sec-Fetch-Site", "cross-site");
        if (mode != null) request.addHeader("Sec-Fetch-Mode", mode);
        if (destination != null) request.addHeader("Sec-Fetch-Dest", destination);
        var response = new MockHttpServletResponse();
        filter(true).doFilter(request, response, (incoming, outgoing) -> {
            throw new AssertionError("Cross-site subresource must not start");
        });
        assertEquals(403, response.getStatus());
        assertNull(request.getSession(false));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/oauth2/authorization/google", "/saml2/authenticate/org-7", "/saml2/authenticate"})
    void topLevelCrossSiteNavigationStillCannotSetADestination(String path) throws Exception {
        var request = start(path, "/invite", "cross-site");
        request.addParameter("registrationId", "org-7");
        request.addHeader("Sec-Fetch-Mode", "navigate");
        request.addHeader("Sec-Fetch-Dest", "document");
        var response = new MockHttpServletResponse();
        filter(true).doFilter(request, response, (incoming, outgoing) -> {
            throw new AssertionError("Destination-setting starts still require same-origin metadata");
        });
        assertEquals(403, response.getStatus());
        assertNull(request.getSession(false));
        verifyNoInteractions(oauthRegistrations, samlRegistrations);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/oauth2/authorization/google", "/saml2/authenticate/org-7", "/saml2/authenticate"})
    void topLevelCrossSiteAndHeaderlessStartsCanAllocateTheirOwnAttempts(String path) throws Exception {
        for (boolean legacy : new boolean[] {false, true}) {
            var request = new MockHttpServletRequest("GET", path);
            request.addParameter("registrationId", "org-7");
            if (!legacy) {
                request.addHeader("Sec-Fetch-Site", "cross-site");
                request.addHeader("Sec-Fetch-Mode", "navigate");
                request.addHeader("Sec-Fetch-Dest", "document");
            }
            filter(true).doFilter(request, new MockHttpServletResponse(), (incoming, outgoing) -> {
                if (path.startsWith("/api/oauth2/")) {
                    new OAuthAuthenticationAttemptRepository().saveAuthorizationRequest(oauth("google", "state"),
                            request, new MockHttpServletResponse());
                } else {
                    new SamlAuthenticationAttemptRepository().saveAuthenticationRequest(saml("org-7", "request-id", "relay"),
                            request, new MockHttpServletResponse());
                }
            });
            assertNotNull(request.getSession(false));
            if (path.startsWith("/api/oauth2/")) {
                assertNotNull(new OAuthAuthenticationAttemptRepository().loadAuthorizationRequest(oauthCallback(request, "google", "state")));
            } else {
                var callback = new MockHttpServletRequest("POST", "/api/login/saml2/sso/org-7");
                callback.setSession(request.getSession(false));
                callback.addParameter("RelayState", "relay");
                assertNotNull(new SamlAuthenticationAttemptRepository().loadAuthenticationRequest(callback));
            }
        }
        verifyNoInteractions(oauthRegistrations, samlRegistrations);
    }

    @Test
    void enabledSamlStillRefusesCrossSiteDestinationBeforeRegistrationLookup() throws Exception {
        var request = start("/saml2/authenticate/org-7", "/invite", "cross-site");
        var response = new MockHttpServletResponse();
        filter(true).doFilter(request, response, (incoming, outgoing) -> {
            throw new AssertionError("Cross-site SAML must not start");
        });
        assertEquals(403, response.getStatus());
        assertNull(request.getSession(false));
        verifyNoInteractions(oauthRegistrations, samlRegistrations);
    }

    @Test
    void destinationRegistrationFailuresExposeNoProviderDetails() throws Exception {
        when(oauthRegistrations.findByRegistrationId("google")).thenThrow(new IllegalStateException("provider-private"));
        var request = start("/api/oauth2/authorization/google", "/invite", "same-origin");
        var response = new MockHttpServletResponse();
        filter(false).doFilter(request, response, (incoming, outgoing) -> {
            throw new AssertionError("Refused registration must not start");
        });
        assertEquals(403, response.getStatus());
        assertEquals("", response.getContentAsString());
        assertNull(request.getSession(false));
    }

    @Test
    void disabledCrossSiteSamlCannotOverwriteAnInFlightGoogleDestination() throws Exception {
        when(oauthRegistrations.findByRegistrationId("google")).thenReturn(mock(ClientRegistration.class));
        var google = start("/api/oauth2/authorization/google", "/invite", "same-origin");
        var repository = new OAuthAuthenticationAttemptRepository();
        var authorization = oauth("google", "original-google-state");
        filter(false).doFilter(google, new MockHttpServletResponse(), (incoming, outgoing) ->
                repository.saveAuthorizationRequest(authorization, google, new MockHttpServletResponse()));
        var injected = start("/saml2/authenticate/org-0", "/settings/personal/security", "cross-site");
        injected.setSession(google.getSession(false));
        var refused = new MockHttpServletResponse();

        filter(false).doFilter(injected, refused, (incoming, outgoing) -> {
            throw new AssertionError("Disabled SAML must not reach authentication");
        });
        var callback = oauthCallback(google, "google", authorization.getState());
        assertSame(authorization, repository.removeAuthorizationRequest(callback, new MockHttpServletResponse()));

        assertEquals(403, refused.getStatus());
        assertEquals("/invite", AuthenticationReturnPathSession.captured(callback));
        assertEquals("/onboarding?redirect=%2Finvite", AuthenticationReturnPathSession.continuation(callback, "/onboarding"));
        verifyNoInteractions(samlRegistrations);
    }

    @Test
    void freshInterleavedOAuthAttemptsKeepTheirOwnDestinationsAndUnmatchedCallbacksConsumeNothing() throws Exception {
        when(oauthRegistrations.findByRegistrationId("google")).thenReturn(mock(ClientRegistration.class));
        when(oauthRegistrations.findByRegistrationId("microsoft")).thenReturn(mock(ClientRegistration.class));
        var repository = new OAuthAuthenticationAttemptRepository();
        var google = start("/api/oauth2/authorization/google", "/invite", "same-origin");
        var microsoft = start("/api/oauth2/authorization/microsoft", "/settings/personal/security", "same-origin");
        var first = oauth("google", "google-state");
        var second = oauth("microsoft", "microsoft-state");
        filter(false).doFilter(google, new MockHttpServletResponse(), (incoming, outgoing) ->
                repository.saveAuthorizationRequest(first, google, new MockHttpServletResponse()));
        microsoft.setSession(google.getSession(false));
        filter(false).doFilter(microsoft, new MockHttpServletResponse(), (incoming, outgoing) ->
                repository.saveAuthorizationRequest(second, microsoft, new MockHttpServletResponse()));
        var unmatched = oauthCallback(google, "google", "unknown-state");
        assertNull(repository.removeAuthorizationRequest(unmatched, new MockHttpServletResponse()));
        assertEquals("/auth/login?sso_error=1", AuthenticationReturnPathSession.failurePath(unmatched));
        var wrongProvider = oauthCallback(google, "microsoft", first.getState());
        assertNull(repository.removeAuthorizationRequest(wrongProvider, new MockHttpServletResponse()));
        var firstCallback = oauthCallback(google, "google", first.getState());
        var secondCallback = oauthCallback(microsoft, "microsoft", second.getState());
        assertSame(first, repository.removeAuthorizationRequest(firstCallback, new MockHttpServletResponse()));
        assertEquals("/invite", AuthenticationReturnPathSession.captured(firstCallback));
        assertSame(second, repository.removeAuthorizationRequest(secondCallback, new MockHttpServletResponse()));
        assertEquals("/settings/personal/security", AuthenticationReturnPathSession.captured(secondCallback));
        assertNull(repository.removeAuthorizationRequest(firstCallback, new MockHttpServletResponse()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/oauth2/authorization/google", "/api/%6fauth2/authorization/google"})
    void enabledNormalizedStartsSaveOnlyValidatedDestinations(String path) throws Exception {
        when(oauthRegistrations.findByRegistrationId("google")).thenReturn(mock(ClientRegistration.class));
        var request = start(path, "/records/../invite-link", "same-origin");
        var repository = new OAuthAuthenticationAttemptRepository();
        filter(false).doFilter(request, new MockHttpServletResponse(), (incoming, outgoing) ->
                repository.saveAuthorizationRequest(oauth("google", "state"), request, new MockHttpServletResponse()));
        var callback = oauthCallback(request, "google", "state");
        assertNotNull(repository.removeAuthorizationRequest(callback, new MockHttpServletResponse()));
        assertEquals("/invite-link", AuthenticationReturnPathSession.captured(callback));
    }

    @Test
    void unavailableOAuthRegistrationDoesNotCreateAttemptState() throws Exception {
        var request = start("/api/oauth2/authorization/google", "/invite", "same-origin");
        var response = new MockHttpServletResponse();
        filter(false).doFilter(request, response, (incoming, outgoing) -> {
            throw new AssertionError("Disabled OAuth registration must not start");
        });
        assertEquals(403, response.getStatus());
        assertNull(request.getSession(false));
    }

    @Test
    void samlRelayStateAndRegistrationSelectOnlyTheirOwnAuthnRequest() throws Exception {
        when(samlRegistrations.findByRegistrationId("org-7")).thenReturn(mock(RelyingPartyRegistration.class));
        var request = start("/saml2/authenticate;source=invite/org-7", "/invite", "same-origin");
        var repository = new SamlAuthenticationAttemptRepository();
        var authorization = saml("org-7", "request-id", "relay-state");
        filter(true).doFilter(request, new MockHttpServletResponse(), (incoming, outgoing) ->
                repository.saveAuthenticationRequest(authorization, request, new MockHttpServletResponse()));
        var callback = new MockHttpServletRequest("POST", "/api/login/saml2/sso/org-7");
        callback.setSession(request.getSession(false));
        callback.addParameter("RelayState", "wrong-relay");
        assertNull(repository.removeAuthenticationRequest(callback, new MockHttpServletResponse()));
        callback.setParameter("RelayState", "relay-state");
        callback.setRequestURI("/api/login/saml2/sso/org-8");
        assertNull(repository.removeAuthenticationRequest(callback, new MockHttpServletResponse()));
        callback.setRequestURI("/api/login/saml2/sso/org-7");
        assertSame(authorization, repository.loadAuthenticationRequest(callback));
        assertSame(authorization, repository.removeAuthenticationRequest(callback, new MockHttpServletResponse()));
        assertEquals("/invite", AuthenticationReturnPathSession.captured(callback));
        assertNull(repository.loadAuthenticationRequest(callback));
    }

    @Test
    void duplicateAttemptCannotReplaceItsAuthorizationOrDestination() {
        var repository = new OAuthAuthenticationAttemptRepository();
        var request = start("/api/oauth2/authorization/google", "/invite", "same-origin");
        AuthenticationReturnPathSession.prepareStart(request);
        repository.saveAuthorizationRequest(oauth("google", "state-0"), request, new MockHttpServletResponse());
        request.setParameter("redirect", "/settings/personal/security");
        AuthenticationReturnPathSession.prepareStart(request);
        assertThrows(IllegalStateException.class, () -> repository.saveAuthorizationRequest(
                oauth("google", "state-0"), request, new MockHttpServletResponse()));
        var original = oauthCallback(request, "google", "state-0");
        assertNotNull(repository.removeAuthorizationRequest(original, new MockHttpServletResponse()));
        assertEquals("/invite", AuthenticationReturnPathSession.captured(original));
    }

    @Test
    void fifthLegitimateStartEvictsEarliestExpiryAndItsCallbackFailsClosed() throws Exception {
        when(oauthRegistrations.findByRegistrationId("google")).thenReturn(mock(ClientRegistration.class));
        var session = new MockHttpSession();
        Instant now = Instant.parse("2026-11-06T00:00:00Z");
        for (int index : new int[] {2, 0, 3, 1, 4}) {
            var repository = new OAuthAuthenticationAttemptRepository(Clock.fixed(now.plusSeconds(index), ZoneOffset.UTC));
            var request = start("/api/oauth2/authorization/google", "/invite?attempt=" + index, "same-origin");
            request.setSession(session);
            var response = new MockHttpServletResponse();
            var authorization = oauth("google", "state-" + index);
            filter(false).doFilter(request, response, (incoming, outgoing) ->
                    repository.saveAuthorizationRequest(authorization, request, response));
            assertEquals(200, response.getStatus());
        }
        var repository = new OAuthAuthenticationAttemptRepository(Clock.fixed(now.plusSeconds(5), ZoneOffset.UTC));
        var start = new MockHttpServletRequest();
        start.setSession(session);
        var evicted = oauthCallback(start, "google", "state-0");
        assertNull(repository.removeAuthorizationRequest(evicted, new MockHttpServletResponse()));
        assertNull(AuthenticationReturnPathSession.captured(evicted));
        assertEquals("/auth/login?sso_error=1", AuthenticationReturnPathSession.failurePath(evicted));
        for (int index = 1; index <= 4; index++) {
            var callback = oauthCallback(start, "google", "state-" + index);
            assertNotNull(repository.removeAuthorizationRequest(callback, new MockHttpServletResponse()));
            assertEquals("/invite?attempt=" + index, AuthenticationReturnPathSession.captured(callback));
        }
    }

    @Test
    void expiredAttemptsAndDuplicateCallbackStateCannotBeConsumed() {
        Instant now = Instant.parse("2026-11-06T00:00:00Z");
        var request = start("/api/oauth2/authorization/google", "/invite", "same-origin");
        AuthenticationReturnPathSession.prepareStart(request);
        var repository = new OAuthAuthenticationAttemptRepository(Clock.fixed(now, ZoneOffset.UTC));
        repository.saveAuthorizationRequest(oauth("google", "state"), request, new MockHttpServletResponse());
        var duplicate = oauthCallback(request, "google", "state");
        duplicate.addParameter("state", "other");
        assertNull(repository.removeAuthorizationRequest(duplicate, new MockHttpServletResponse()));
        assertNotNull(repository.loadAuthorizationRequest(oauthCallback(request, "google", "state")));
        var expired = new OAuthAuthenticationAttemptRepository(Clock.fixed(now.plus(Duration.ofMinutes(11)), ZoneOffset.UTC));
        assertNull(expired.removeAuthorizationRequest(oauthCallback(request, "google", "state"), new MockHttpServletResponse()));
    }

    @Test
    void attemptSessionAttributesSurviveJavaSerialization() throws Exception {
        var request = start("/api/oauth2/authorization/google", "/invite", "same-origin");
        AuthenticationReturnPathSession.prepareStart(request);
        var repository = new OAuthAuthenticationAttemptRepository();
        repository.saveAuthorizationRequest(oauth("google", "state"), request, new MockHttpServletResponse());
        var samlRepository = new SamlAuthenticationAttemptRepository();
        samlRepository.saveAuthenticationRequest(saml("org-7", "serialized-request-id", "serialized-relay"), request, new MockHttpServletResponse());
        var restored = new MockHttpSession();
        for (String name : java.util.Collections.list(request.getSession().getAttributeNames())) {
            var bytes = new ByteArrayOutputStream();
            try (var output = new ObjectOutputStream(bytes)) { output.writeObject(request.getSession().getAttribute(name)); }
            try (var input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
                restored.setAttribute(name, input.readObject());
            }
        }
        var callback = oauthCallback(request, "google", "state");
        callback.setSession(restored);
        assertNotNull(repository.removeAuthorizationRequest(callback, new MockHttpServletResponse()));
        assertEquals("/invite", AuthenticationReturnPathSession.captured(callback));
        var samlCallback = new MockHttpServletRequest("POST", "/api/login/saml2/sso/org-7");
        samlCallback.setSession(restored);
        samlCallback.addParameter("RelayState", "serialized-relay");
        var restoredSaml = samlRepository.removeAuthenticationRequest(samlCallback, new MockHttpServletResponse());
        assertNotNull(restoredSaml);
        assertEquals("serialized-request-id", restoredSaml.getId());
        assertEquals("/invite", AuthenticationReturnPathSession.captured(samlCallback));
    }

    static AbstractSaml2AuthenticationRequest saml(String registration, String id, String relay) {
        var relyingParty = mock(RelyingPartyRegistration.class);
        when(relyingParty.getRegistrationId()).thenReturn(registration);
        var metadata = mock(org.springframework.security.saml2.provider.service.registration.AssertingPartyMetadata.class);
        when(metadata.getSingleSignOnServiceLocation()).thenReturn("https://idp.example/sso");
        when(relyingParty.getAssertingPartyMetadata()).thenReturn(metadata);
        return org.springframework.security.saml2.provider.service.authentication.Saml2RedirectAuthenticationRequest
                .withRelyingPartyRegistration(relyingParty).id(id).relayState(relay)
                .authenticationRequestUri("https://idp.example/sso").samlRequest("encoded-request").build();
    }

    private AuthenticationReturnPathFilter filter(boolean samlEnabled) {
        return new AuthenticationReturnPathFilter(samlEnabled, oauthRegistrations, samlRegistrations);
    }

    private static MockHttpServletRequest start(String path, String destination, String site) {
        var request = new MockHttpServletRequest("GET", path);
        request.addParameter("redirect", destination);
        if (site != null) request.addHeader("Sec-Fetch-Site", site);
        return request;
    }

    static OAuth2AuthorizationRequest oauth(String registration, String state) {
        return OAuth2AuthorizationRequest.authorizationCode().authorizationUri("https://issuer.example/authorize")
                .clientId("client").redirectUri("https://app.example/api/login/oauth2/code/" + registration)
                .state(state).attributes(Map.of("registration_id", registration)).build();
    }

    private static MockHttpServletRequest oauthCallback(MockHttpServletRequest start, String registration, String state) {
        var request = new MockHttpServletRequest("GET", "/api/login/oauth2/code/" + registration);
        request.setSession(start.getSession(false));
        request.addParameter("state", state);
        return request;
    }
}
