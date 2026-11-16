package ooo.klae.connex.backend.sso;

import java.time.Clock;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.saml2.provider.service.authentication.AbstractSaml2AuthenticationRequest;
import org.springframework.security.saml2.provider.service.web.Saml2AuthenticationRequestRepository;

import ooo.klae.connex.backend.config.RequestPathNormalizer;

/** Binds a SAML destination to its RelayState and complete AuthnRequest, including the request id. */
public final class SamlAuthenticationAttemptRepository implements Saml2AuthenticationRequestRepository<AbstractSaml2AuthenticationRequest> {
    private static final String PROTOCOL = "saml2";
    private final AuthenticationAttemptStore store;

    public SamlAuthenticationAttemptRepository() {
        this(Clock.systemUTC());
    }

    SamlAuthenticationAttemptRepository(Clock clock) {
        store = new AuthenticationAttemptStore(clock);
    }

    @Override
    public AbstractSaml2AuthenticationRequest loadAuthenticationRequest(HttpServletRequest request) {
        AuthenticationAttemptStore.Attempt attempt = matching(request);
        return attempt != null && attempt.authorization() instanceof AbstractSaml2AuthenticationRequest authorization ? authorization : null;
    }

    @Override
    public void saveAuthenticationRequest(AbstractSaml2AuthenticationRequest authorization, HttpServletRequest request, HttpServletResponse response) {
        if (authorization == null) {
            removeAuthenticationRequest(request, response);
            return;
        }
        if (authorization.getId() == null || authorization.getId().isBlank()) {
            throw new IllegalArgumentException("SAML request identity is missing");
        }
        store.save(PROTOCOL, authorization.getRelayState(), authorization, request);
    }

    @Override
    public AbstractSaml2AuthenticationRequest removeAuthenticationRequest(HttpServletRequest request, HttpServletResponse response) {
        AuthenticationAttemptStore.Attempt attempt = matching(request);
        if (attempt == null || !(attempt.authorization() instanceof AbstractSaml2AuthenticationRequest authorization)) return null;
        store.remove(PROTOCOL, attempt.identity(), request);
        AuthenticationReturnPathSession.captureMatched(request, attempt.destination());
        return authorization;
    }

    private AuthenticationAttemptStore.Attempt matching(HttpServletRequest request) {
        String relayState = AuthenticationAttemptStore.parameter(request, "RelayState");
        AuthenticationAttemptStore.Attempt attempt = store.load(PROTOCOL, relayState, request);
        if (attempt == null || !(attempt.authorization() instanceof AbstractSaml2AuthenticationRequest authorization)
                || !relayState.equals(authorization.getRelayState())) return null;
        return RequestPathNormalizer.apiPath(request).equals("/api/login/saml2/sso/" + authorization.getRelyingPartyRegistrationId())
                ? attempt : null;
    }
}
