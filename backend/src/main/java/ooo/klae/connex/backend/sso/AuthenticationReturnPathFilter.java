package ooo.klae.connex.backend.sso;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistrationRepository;
import org.springframework.web.filter.OncePerRequestFilter;
import ooo.klae.connex.backend.config.RequestPathNormalizer;

/** Refuses cross-site subresource starts and admits destinations only for enabled, same-origin ceremonies. */
public final class AuthenticationReturnPathFilter extends OncePerRequestFilter {
    private final boolean samlEnabled;
    private final CompositeClientRegistrationRepository oauthRegistrations;
    private final RelyingPartyRegistrationRepository samlRegistrations;

    public AuthenticationReturnPathFilter(boolean samlEnabled, CompositeClientRegistrationRepository oauthRegistrations,
            RelyingPartyRegistrationRepository samlRegistrations) {
        this.samlEnabled = samlEnabled;
        this.oauthRegistrations = oauthRegistrations;
        this.samlRegistrations = samlRegistrations;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String path = RequestPathNormalizer.apiPath(request);
        boolean oauth = path.matches("/api/oauth2/authorization/[^/]+");
        boolean saml = path.matches("/saml2/authenticate/[^/]+") || path.equals("/saml2/authenticate");
        if (oauth || saml) {
            boolean destinationRequested = request.getParameterValues("redirect") != null;
            if (saml && !samlEnabled || isCrossSiteSubresource(request)
                    || destinationRequested && !"same-origin".equals(request.getHeader("Sec-Fetch-Site"))) {
                refuse(response);
                return;
            }
            if (destinationRequested) {
                String registration = path.equals("/saml2/authenticate")
                        ? AuthenticationAttemptStore.parameter(request, "registrationId")
                        : path.substring(path.lastIndexOf('/') + 1);
                boolean enabled;
                try {
                    enabled = oauth ? oauthRegistrations.findByRegistrationId(registration) != null
                            : samlRegistrations.findByRegistrationId(registration) != null;
                } catch (RuntimeException exception) {
                    refuse(response);
                    return;
                }
                if (!enabled) {
                    refuse(response);
                    return;
                }
                AuthenticationReturnPathSession.prepareStart(request);
            }
        }
        filterChain.doFilter(request, response);
    }

    static boolean isCrossSiteSubresource(HttpServletRequest request) {
        return "cross-site".equals(request.getHeader("Sec-Fetch-Site"))
                && (!"navigate".equals(request.getHeader("Sec-Fetch-Mode"))
                        || !"document".equals(request.getHeader("Sec-Fetch-Dest")));
    }

    private static void refuse(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
    }
}
