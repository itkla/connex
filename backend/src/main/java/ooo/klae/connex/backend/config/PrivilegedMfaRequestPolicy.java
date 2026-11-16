package ooo.klae.connex.backend.config;

import java.util.Set;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;

/** Shares the filter's normalized confinement decision with locked request authorization. */
public final class PrivilegedMfaRequestPolicy {
    private static final String ATTRIBUTE = PrivilegedMfaRequestPolicy.class.getName();
    private static final Set<String> ENROLLMENT_GET_PATHS = Set.of(
        "/api/auth/me", "/api/auth/csrf", "/api/auth/webauthn/register/requirements",
        "/api/auth/webauthn/credentials", "/api/capabilities", "/api/workspaces",
        "/api/ownership-offers");
    private static final Set<String> ENROLLMENT_POST_PATHS = Set.of(
        "/api/auth/logout", "/api/auth/webauthn/register/options", "/api/auth/webauthn/register",
        "/api/auth/webauthn/register/confirmation", "/api/auth/webauthn/register/confirmation/exchange",
        "/api/auth/webauthn/recover", "/api/auth/webauthn/attestation/options",
        "/api/auth/webauthn/attestation");
    private static final Pattern SAFE_EXIT_PATH = Pattern.compile(
        "(?:/api/(?:workspaces|orgs)/\\d+/leave|/api/ownership-offers/\\d+/decline)");
    private static final Set<String> LINK_FLOW_PATHS = Set.of(
        "/api/document-acceptance", "/api/delivery/unsubscribe");

    private PrivilegedMfaRequestPolicy() {
    }

    /** Captures the browser exemption using the path already normalized by its security filter. */
    public static boolean bindBrowser(HttpServletRequest request, int userId, String normalizedPath) {
        String method = request.getMethod();
        boolean exempt = ("GET".equals(method) && ENROLLMENT_GET_PATHS.contains(normalizedPath))
            || ("POST".equals(method) && (ENROLLMENT_POST_PATHS.contains(normalizedPath)
                || SAFE_EXIT_PATH.matcher(normalizedPath).matches()))
            || isLinkFlowPath(normalizedPath);
        request.setAttribute(ATTRIBUTE, new Decision(userId, exempt, false));
        return exempt;
    }

    /** PAT authentication has no enrollment or recipient-flow exemption. */
    public static void bindPublicApi(HttpServletRequest request, int userId) {
        request.setAttribute(ATTRIBUTE, new Decision(userId, false, true));
    }

    /** Returns only a decision installed by a security filter, never request-supplied path data. */
    public static Decision decision(HttpServletRequest request) {
        return request.getAttribute(ATTRIBUTE) instanceof Decision decision ? decision : null;
    }

    /** Whether a normalized path belongs to a purpose-bound grant-cookie recipient flow. */
    static boolean isLinkFlowPath(String path) {
        return LINK_FLOW_PATHS.stream()
            .anyMatch(prefix -> path.equals(prefix) || path.startsWith(prefix + "/"));
    }

    /** Server-owned actor and exemption snapshot for this dispatch. */
    public record Decision(int userId, boolean exempt, boolean publicApi) {
    }
}
