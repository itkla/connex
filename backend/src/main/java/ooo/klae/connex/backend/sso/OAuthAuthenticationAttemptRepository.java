package ooo.klae.connex.backend.sso;

import java.time.Clock;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

import ooo.klae.connex.backend.config.RequestPathNormalizer;

/** Binds OAuth return navigation to the full authorization request and its exact state. */
public final class OAuthAuthenticationAttemptRepository implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {
    private static final String PROTOCOL = "oauth2";
    private final AuthenticationAttemptStore store;

    public OAuthAuthenticationAttemptRepository() {
        this(Clock.systemUTC());
    }

    OAuthAuthenticationAttemptRepository(Clock clock) {
        store = new AuthenticationAttemptStore(clock);
    }

    @Override
    public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
        AuthenticationAttemptStore.Attempt attempt = matching(request);
        return attempt != null && attempt.authorization() instanceof OAuth2AuthorizationRequest authorization ? authorization : null;
    }

    @Override
    public void saveAuthorizationRequest(OAuth2AuthorizationRequest authorization, HttpServletRequest request, HttpServletResponse response) {
        if (authorization == null) {
            removeAuthorizationRequest(request, response);
            return;
        }
        store.save(PROTOCOL, authorization.getState(), authorization, request);
    }

    @Override
    public OAuth2AuthorizationRequest removeAuthorizationRequest(HttpServletRequest request, HttpServletResponse response) {
        AuthenticationAttemptStore.Attempt attempt = matching(request);
        if (attempt == null || !(attempt.authorization() instanceof OAuth2AuthorizationRequest authorization)) return null;
        store.remove(PROTOCOL, attempt.identity(), request);
        AuthenticationReturnPathSession.captureMatched(request, attempt.destination());
        return authorization;
    }

    private AuthenticationAttemptStore.Attempt matching(HttpServletRequest request) {
        String state = AuthenticationAttemptStore.parameter(request, "state");
        AuthenticationAttemptStore.Attempt attempt = store.load(PROTOCOL, state, request);
        if (attempt == null || !(attempt.authorization() instanceof OAuth2AuthorizationRequest authorization)
                || !state.equals(authorization.getState())) return null;
        Object registration = authorization.getAttributes().get("registration_id");
        return registration instanceof String id
                && RequestPathNormalizer.apiPath(request).equals("/api/login/oauth2/code/" + id) ? attempt : null;
    }
}
