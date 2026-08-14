package ooo.klae.connex.backend.config;

import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.services.LogoutAuditService;
import ooo.klae.connex.backend.util.OneTimeTokenDigest;

/**
 * Records a user-initiated logout before Spring Security invalidates the HTTP session and clears
 * the security context. This ordering preserves the authenticated actor, request context, and live
 * session identifier long enough to derive its one-way digest. Database idempotency, rather than a
 * JVM or session-object monitor, makes overlapping requests and separate replicas converge on one
 * audit row. The handler intentionally swallows failures so the following security logout handlers
 * always destroy the session; a broken audit sink must never keep a user signed in. Anonymous,
 * expired, already-invalidated, and repeated logout requests do not create a misleading event.
 * Server-side bulk session revocation is a distinct security operation and does not pass through
 * this user-initiated handler.
 */
@Component
@RequiredArgsConstructor
public class LogoutAuditHandler implements LogoutHandler {

    private final LogoutAuditService logoutAuditService;

    @Override
    public void logout(HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
        HttpSession session = request.getSession(false);
        if (session == null || authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof User user)) {
            return;
        }
        try {
            logoutAuditService.record(request, user, OneTimeTokenDigest.sha256(session.getId()));
        } catch (RuntimeException exception) {
            return;
        }
    }
}
