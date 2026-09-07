package ooo.klae.connex.backend.config;

import java.io.IOException;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.services.SessionSecurityService;

/**
 * Enforces the configured absolute lifetime for authenticated servlet sessions. Browser CSP
 * violation reports are exempt: the collector reads no principal, and a report that happens to
 * carry an absolutely expired session cookie must still be received rather than answered 401.
 */
@RequiredArgsConstructor
public class AbsoluteSessionTimeoutFilter extends OncePerRequestFilter {
    private static final String CSP_REPORT_PATH = "/api/csp-reports";

    private final SessionSecurityService sessionSecurityService;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = apiPath(request);
        return !path.startsWith("/api/") || isCspReport(request.getMethod(), path);
    }

    private static boolean isCspReport(String method, String path) {
        return "POST".equals(method) && CSP_REPORT_PATH.equals(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        if (session != null && sessionSecurityService.isAbsoluteExpired(session)) {
            session.invalidate();
            SecurityContextHolder.clearContext();
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        if (session != null) {
            sessionSecurityService.ensureAuthenticatedSessionStarted(session);
        }
        chain.doFilter(request, response);
    }

    private static String apiPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isBlank() && uri.startsWith(contextPath)) {
            return uri.substring(contextPath.length());
        }
        return uri;
    }
}
