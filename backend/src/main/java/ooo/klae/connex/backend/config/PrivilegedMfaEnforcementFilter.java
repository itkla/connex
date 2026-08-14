package ooo.klae.connex.backend.config;

import java.io.IOException;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.services.AuditService;
import ooo.klae.connex.backend.services.PrivilegedAccountService;
import ooo.klae.connex.backend.services.SessionSecurityService;
import ooo.klae.connex.backend.webauthn.WebAuthnService;

/**
 * Confines unenrolled privileged accounts and applies WebAuthn step-up to uncovered export paths.
 */
public class PrivilegedMfaEnforcementFilter extends OncePerRequestFilter {
    public static final String ENROLLMENT_REQUIRED_CODE = "PRIVILEGED_MFA_ENROLLMENT_REQUIRED";
    private static final String RECENT_AUTHENTICATION_REQUIRED_CODE = "RECENT_AUTHENTICATION_REQUIRED";
    private static final Set<String> ENROLLMENT_GET_PATHS = Set.of(
            "/api/auth/me",
            "/api/auth/csrf",
            "/api/auth/webauthn/register/requirements",
            "/api/auth/webauthn/credentials",
            "/api/capabilities",
            "/api/workspaces");
    private static final Set<String> ENROLLMENT_POST_PATHS = Set.of(
            "/api/auth/logout",
            "/api/auth/webauthn/register/options",
            "/api/auth/webauthn/register",
            "/api/auth/webauthn/recover");
    private static final Set<String> EXACT_EXPORT_PATHS = Set.of(
            "/api/audit/export");
    private static final Pattern ORG_AUDIT_EXPORT = Pattern.compile("/api/orgs/\\d+/audit/export");
    private static final Pattern CAMPAIGN_EXPORT = Pattern.compile("/api/campaigns/\\d+/exports(?:/\\d+)?");
    private static final Pattern REPORT_EXPORT = Pattern.compile(
            "/api/reports/\\d+/(?:export\\.csv|snapshots/\\d+/export\\.csv)");

    private final PrivilegedMfaProperties properties;
    private final PrivilegedAccountService privilegedAccountService;
    private final WebAuthnService webAuthnService;
    private final SessionSecurityService sessionSecurityService;
    private final AuditService auditService;

    public PrivilegedMfaEnforcementFilter(
            PrivilegedMfaProperties properties,
            PrivilegedAccountService privilegedAccountService,
            WebAuthnService webAuthnService,
            SessionSecurityService sessionSecurityService,
            AuditService auditService) {
        this.properties = properties;
        this.privilegedAccountService = privilegedAccountService;
        this.webAuthnService = webAuthnService;
        this.sessionSecurityService = sessionSecurityService;
        this.auditService = auditService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        User user = currentUser();
        if (user == null || !properties.isEnforced()) {
            filterChain.doFilter(request, response);
            return;
        }
        String path = request.getRequestURI();
        if (privilegedAccountService.isPrivileged(user.getId())
                && !webAuthnService.hasPasskey(user.getId())
                && !isEnrollmentPath(request.getMethod(), path)) {
            auditService.recordFailureScoped("auth.mfa.policy.denied", "user", user.getId(), null, null,
                    user.getDisplayName(), "Privileged account confined pending MFA enrollment",
                    "enrollment_required");
            deny(response, ENROLLMENT_REQUIRED_CODE,
                    "A passkey must be enrolled before this privileged account can continue");
            return;
        }
        if (requiresExportStepUp(path)
                && !sessionSecurityService.hasFreshRecentAuthentication(request.getSession(false), user.getId())) {
            auditService.recordFailureScoped("auth.mfa.step_up.required", "user", user.getId(), null, null,
                    user.getDisplayName(), "Recent MFA required for data export", "step_up_required");
            deny(response, RECENT_AUTHENTICATION_REQUIRED_CODE,
                    "Recent passkey verification is required");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private static User currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof User user) {
            return user;
        }
        return null;
    }

    static boolean requiresExportStepUp(String path) {
        return path.startsWith("/api/exports/")
                || EXACT_EXPORT_PATHS.contains(path)
                || ORG_AUDIT_EXPORT.matcher(path).matches()
                || CAMPAIGN_EXPORT.matcher(path).matches()
                || REPORT_EXPORT.matcher(path).matches();
    }

    private static boolean isEnrollmentPath(String method, String path) {
        return ("GET".equals(method) && ENROLLMENT_GET_PATHS.contains(path))
                || ("POST".equals(method) && ENROLLMENT_POST_PATHS.contains(path));
    }

    private static void deny(HttpServletResponse response, String code, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }
}
