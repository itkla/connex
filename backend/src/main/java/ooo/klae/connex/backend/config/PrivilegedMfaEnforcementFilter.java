package ooo.klae.connex.backend.config;

import java.io.IOException;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import ooo.klae.connex.backend.services.DenialAuditRateLimiter;
import ooo.klae.connex.backend.services.PrivilegedAccountService;
import ooo.klae.connex.backend.services.SessionSecurityService;
import ooo.klae.connex.backend.util.ClientIpResolver;
import ooo.klae.connex.backend.webauthn.WebAuthnService;

/**
 * Confines unenrolled privileged accounts and applies WebAuthn step-up to uncovered export paths.
 *
 * <p>Emailed-link recipient surfaces are exempt from the enrollment confinement: their authority is
 * the purpose-bound grant cookie, the session only supplies exchange lineage, and a signed-in
 * signer who has not yet enrolled a passkey must still be able to countersign or opt out.
 *
 * <p>Each denial is audited at most once per window for a user, action and client address, through
 * {@link DenialAuditRateLimiter}; every denied request is still refused.
 */
public class PrivilegedMfaEnforcementFilter extends OncePerRequestFilter {
    public static final String ENROLLMENT_REQUIRED_CODE = "PRIVILEGED_MFA_ENROLLMENT_REQUIRED";
    private static final Logger log = LoggerFactory.getLogger(PrivilegedMfaEnforcementFilter.class);
    private static final String ENROLLMENT_DENIED_ACTION = "auth.mfa.policy.denied";
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
            "/api/auth/webauthn/register/confirmation",
            "/api/auth/webauthn/register/confirmation/exchange",
            "/api/auth/webauthn/recover");
    private static final Set<String> EXACT_EXPORT_PATHS = Set.of(
            "/api/audit/export");
    private static final Set<String> LINK_FLOW_PATHS = Set.of(
            "/api/document-acceptance",
            "/api/delivery/unsubscribe");
    private static final String IDENTIFIER_SEGMENT = "[^/]+";
    private static final Pattern ORG_AUDIT_EXPORT = Pattern.compile(
            "/api/orgs/" + IDENTIFIER_SEGMENT + "/audit/export");
    private static final Pattern CAMPAIGN_EXPORT = Pattern.compile(
            "/api/campaigns/" + IDENTIFIER_SEGMENT + "/exports");
    private static final Pattern REPORT_EXPORT = Pattern.compile(
            "/api/reports/" + IDENTIFIER_SEGMENT + "/(?:export\\.csv|snapshots/"
                    + IDENTIFIER_SEGMENT + "/export\\.csv)");

    private final PrivilegedMfaProperties properties;
    private final PrivilegedAccountService privilegedAccountService;
    private final WebAuthnService webAuthnService;
    private final SessionSecurityService sessionSecurityService;
    private final AuditService auditService;
    private final DenialAuditRateLimiter denialAuditRateLimiter;
    private final ClientIpResolver clientIpResolver;

    public PrivilegedMfaEnforcementFilter(
            PrivilegedMfaProperties properties,
            PrivilegedAccountService privilegedAccountService,
            WebAuthnService webAuthnService,
            SessionSecurityService sessionSecurityService,
            AuditService auditService,
            DenialAuditRateLimiter denialAuditRateLimiter,
            ClientIpResolver clientIpResolver) {
        this.properties = properties;
        this.privilegedAccountService = privilegedAccountService;
        this.webAuthnService = webAuthnService;
        this.sessionSecurityService = sessionSecurityService;
        this.auditService = auditService;
        this.denialAuditRateLimiter = denialAuditRateLimiter;
        this.clientIpResolver = clientIpResolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        User user = currentUser();
        if (user == null) {
            filterChain.doFilter(request, response);
            return;
        }
        String path = RequestPathNormalizer.apiPath(request);
        if (properties.isEnforced()
                && privilegedAccountService.isPrivileged(user.getId())
                && !webAuthnService.hasPasskey(user.getId())
                && !isEnrollmentPath(request.getMethod(), path)
                && !isLinkFlowPath(path)) {
            recordDenial(request, user, ENROLLMENT_DENIED_ACTION,
                    "Privileged account confined pending MFA enrollment", "enrollment_required");
            deny(response, ENROLLMENT_REQUIRED_CODE,
                    "A passkey must be enrolled before this privileged account can continue");
            return;
        }
        if (requiresExportStepUp(request.getMethod(), path)
                && !sessionSecurityService.isExportStepUpSatisfied(request.getSession(false), user.getId())) {
            recordDenial(request, user, AuditService.EXPORT_STEP_UP_ACTION,
                    AuditService.EXPORT_STEP_UP_SUMMARY, "step_up_required");
            deny(response, RECENT_AUTHENTICATION_REQUIRED_CODE,
                    "Recent passkey verification is required");
            return;
        }
        filterChain.doFilter(request, response);
    }

    /**
     * Records a denial at most once per window for this user, action and client address.
     *
     * <p>Both denials can be written by a {@code GET}, and the session cookie is {@code SameSite=Lax},
     * so another site can trigger them with a top-level navigation. Unbounded, every visit appended a
     * row attributed to the victim (#1850). The write is strict so that a failure hands the window
     * back instead of suppressing the next denial's evidence; the request is refused either way.
     */
    private void recordDenial(HttpServletRequest request, User user, String action, String summary,
            String reason) {
        Optional<DenialAuditRateLimiter.Admission> admission =
                denialAuditRateLimiter.acquire(user.getId(), action, clientIpResolver.resolve(request));
        if (admission.isEmpty()) {
            return;
        }
        boolean written = false;
        try {
            auditService.recordStrictFailureIndependentScoped(action, "user", user.getId(), null, null,
                    user.getDisplayName(), summary, reason);
            written = true;
        } catch (RuntimeException e) {
            log.error("Failed to record access denial action={} userId={}", action, user.getId(), e);
        } finally {
            if (!written) {
                denialAuditRateLimiter.release(admission.get());
            }
        }
    }

    private static User currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof User user) {
            return user;
        }
        return null;
    }

    /**
     * Whether the request is a data-egress operation that must carry a recent WebAuthn assertion.
     *
     * <p>Identifier segments match any non-empty segment rather than decimal digits. Spring's
     * string-to-number conversion accepts {@code 0x1}, {@code #1} and {@code +1} as the integer 1, so
     * a decimal-only matcher would let {@code /api/orgs/0x1/audit/export} reach the export handler
     * ungated. A segment that no handler accepts is refused here and would have been rejected
     * downstream anyway, so matching wider fails closed.
     *
     * <p>Every surface here answers with the exported data itself, except the campaign one: a
     * campaign's exports are created by {@code POST} and their metadata is listed and read by
     * {@code GET} under {@code CAMPAIGN_VIEW}. Gating those reads would leave a viewer who cannot
     * create an export — and so cannot reach the step-up ceremony — looking at a silently empty
     * export history, so only the egress operation is gated.
     */
    static boolean requiresExportStepUp(String method, String path) {
        if (CAMPAIGN_EXPORT.matcher(path).matches()) {
            return "POST".equals(method);
        }
        return path.startsWith("/api/exports/")
                || EXACT_EXPORT_PATHS.contains(path)
                || ORG_AUDIT_EXPORT.matcher(path).matches()
                || REPORT_EXPORT.matcher(path).matches();
    }

    /** Whether the request is a grant-cookie recipient surface rather than a session-authorized one. */
    static boolean isLinkFlowPath(String path) {
        return LINK_FLOW_PATHS.stream()
                .anyMatch(prefix -> path.equals(prefix) || path.startsWith(prefix + "/"));
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
