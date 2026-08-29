package ooo.klae.connex.backend.config;

import java.io.IOException;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.notifications.WebSocketSessionRegistry;
import ooo.klae.connex.backend.services.SessionSecurityService;

/**
 * Refuses a session whose account session epoch has moved on since it authenticated.
 *
 * <p>Revocation is enumerate-and-expire and so fails open: a login whose session row is written
 * after a revocation has enumerated is never seen. This filter turns that miss from silent into
 * harmless — the raced session carries the pre-bump epoch and is refused on its next request.
 *
 * <p>Refusal is de-authentication in place: the security context is cleared and the chain continues.
 * The request then meets ordinary authorization, which answers 401 through the configured entry
 * point for a protected route and serves a public one unchanged. That is why there is no exemption
 * allowlist — login, logout, CSRF and every permitted route are exempt structurally rather than by a
 * list that decays. It is also why the session is not invalidated: the row carries the CSRF token
 * and the one-time-link lineage that in-flight reset and verification flows depend on, and leaving
 * it in place keeps it enumerable by a later revocation.
 *
 * <p>An absent stamp is refused, never defaulted. Treating it as epoch zero would make "carries no
 * stamp" permanently acceptable for every account still at zero, which is the same fail-open class
 * this exists to close.
 */
public class SessionEpochFilter extends OncePerRequestFilter {

    private final UserMapper userMapper;
    private final SessionSecurityService sessionSecurityService;
    private final WebSocketSessionRegistry webSocketSessions;

    public SessionEpochFilter(
            UserMapper userMapper,
            SessionSecurityService sessionSecurityService,
            WebSocketSessionRegistry webSocketSessions) {
        this.userMapper = userMapper;
        this.sessionSecurityService = sessionSecurityService;
        this.webSocketSessions = webSocketSessions;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!isApplicationAuthenticated(authentication)) {
            filterChain.doFilter(request, response);
            return;
        }
        if (!(authentication.getPrincipal() instanceof User user)) {
            filterChain.doFilter(request, response);
            return;
        }
        HttpSession session = request.getSession(false);
        Integer stamped = sessionSecurityService.sessionEpoch(session);
        Integer current = userMapper.currentSessionEpoch(user.getId());
        if (stamped == null || current == null || !stamped.equals(current)) {
            refuse(session);
        }
        filterChain.doFilter(request, response);
    }

    private void refuse(HttpSession session) {
        if (session != null) {
            webSocketSessions.closeByHttpSession(session.getId());
        }
        SecurityContextHolder.clearContext();
    }

    /**
     * Whether the request carries an authentication this application issued.
     *
     * <p>Anonymous authentication is excluded deliberately — it is the unauthenticated state, and
     * clearing it would strip what the authorization filter expects a public request to carry.
     *
     * <p>A principal of some other type is left alone here rather than refused. Those exist — an
     * identity-provider filter can persist one when the login ceremony never completed — but they
     * predate this filter, are tracked at their source in #1478, and belong to a threat model this
     * change does not address. Refusing them here would also be the wrong place: the session stays
     * live and unenumerable either way, so it has to be invalidated where it is created.
     */
    private static boolean isApplicationAuthenticated(Authentication authentication) {
        return authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }
}
