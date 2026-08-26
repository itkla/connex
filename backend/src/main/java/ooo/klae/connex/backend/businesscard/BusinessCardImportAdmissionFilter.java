package ooo.klae.connex.backend.businesscard;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.capability.Capability;
import ooo.klae.connex.backend.capability.CapabilityEntitlement;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.TooManyRequestsException;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.WorkspaceRequestResolver;

/**
 * Rejects invalid, unentitled, or throttled card operations before controller dispatch.
 */
@RequiredArgsConstructor
public class BusinessCardImportAdmissionFilter extends OncePerRequestFilter {
    private static final String SCAN_PATH = "/api/business-cards/scan";
    private static final String IMPORT_PATH = "/api/business-cards/import";
    private static final String RESERVATION_PATH = "/api/business-cards/import/reservation";

    private final BusinessCardRateLimiter rateLimiter;
    private final CapabilityEntitlement capabilityEntitlement;
    private final WorkspaceRequestResolver workspaceRequestResolver;
    private final WorkspaceService workspaceService;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return operation(request) == null;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof User user)
                || user.getId() <= 0) {
            chain.doFilter(request, response);
            return;
        }
        Operation admission = operation(request);
        if (admission == null) {
            chain.doFilter(request, response);
            return;
        }
        if (admission != Operation.SCAN) {
            try {
                BusinessCardIdempotencyKey.canonicalize(request.getHeader("Idempotency-Key"));
            } catch (BadRequestException exception) {
                reject(response, HttpServletResponse.SC_BAD_REQUEST, Rejection.INVALID_IDEMPOTENCY_KEY);
                return;
            }
        }
        Capability capability = admission == Operation.SCAN
            ? Capability.BUSINESS_CARD_SCANNING
            : Capability.BUSINESS_CARD_IMPORT;
        if (!capabilityEntitlement.isEntitled(capability)) {
            reject(response, HttpServletResponse.SC_FORBIDDEN, Rejection.CAPABILITY_UNAVAILABLE);
            return;
        }
        Integer workspaceId = workspaceRequestResolver.resolve(request, user.getId());
        if (workspaceId == null) {
            reject(response, HttpServletResponse.SC_FORBIDDEN, Rejection.WORKSPACE_REQUIRED);
            return;
        }
        try {
            workspaceService.requirePermission(workspaceId, user.getId(), Permission.PERSON_CREATE);
            workspaceService.requirePermission(workspaceId, user.getId(), Permission.ATTACHMENT_CREATE);
        } catch (ForbiddenException exception) {
            reject(response, HttpServletResponse.SC_FORBIDDEN, Rejection.PERMISSION_DENIED);
            return;
        }
        try {
            switch (admission) {
                case SCAN -> rateLimiter.requireScanAdmissionAllowed(user.getId());
                case IMPORT -> rateLimiter.requireImportAdmissionAllowed(user.getId());
                case RESERVATION -> rateLimiter.requireReservationAllowed(user.getId());
                case STATUS -> rateLimiter.requireStatusAllowed(user.getId());
            }
        } catch (TooManyRequestsException exception) {
            reject(response, 429, Rejection.RATE_LIMITED);
            return;
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

    private static Operation operation(HttpServletRequest request) {
        String method = request.getMethod();
        String path = apiPath(request);
        if ("POST".equals(method) && SCAN_PATH.equals(path)) {
            return Operation.SCAN;
        }
        if ("POST".equals(method) && IMPORT_PATH.equals(path)) {
            return Operation.IMPORT;
        }
        if ("POST".equals(method) && RESERVATION_PATH.equals(path)) {
            return Operation.RESERVATION;
        }
        if ("GET".equals(method) && IMPORT_PATH.equals(path)) {
            return Operation.STATUS;
        }
        return null;
    }

    private static void reject(HttpServletResponse response, int status, Rejection rejection)
            throws IOException {
        response.setHeader("Cache-Control", "no-store");
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(
            "{\"error\":\"" + rejection.error + "\",\"code\":\"" + rejection.code + "\"}");
    }

    private enum Rejection {
        INVALID_IDEMPOTENCY_KEY(
            "BUSINESS_CARD_INVALID_IDEMPOTENCY_KEY",
            "The idempotency key is invalid"),
        CAPABILITY_UNAVAILABLE(
            "BUSINESS_CARD_CAPABILITY_UNAVAILABLE",
            "Business-card operations are unavailable"),
        WORKSPACE_REQUIRED(
            "BUSINESS_CARD_WORKSPACE_REQUIRED",
            "A workspace is required for business-card operations"),
        PERMISSION_DENIED(
            "BUSINESS_CARD_PERMISSION_DENIED",
            "Business-card permission is required"),
        RATE_LIMITED(
            "BUSINESS_CARD_RATE_LIMITED",
            "Too many business-card requests");

        private final String code;
        private final String error;

        Rejection(String code, String error) {
            this.code = code;
            this.error = error;
        }
    }

    private enum Operation {
        SCAN,
        IMPORT,
        RESERVATION,
        STATUS
    }
}
