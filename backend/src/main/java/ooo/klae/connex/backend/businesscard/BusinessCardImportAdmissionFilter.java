package ooo.klae.connex.backend.businesscard;

import java.io.IOException;

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
import ooo.klae.connex.backend.exceptions.TooManyRequestsException;

/**
 * Rejects invalid, unentitled, or principal-throttled card import operations before controller dispatch.
 */
@RequiredArgsConstructor
public class BusinessCardImportAdmissionFilter extends OncePerRequestFilter {
    private static final String IMPORT_PATH = "/api/business-cards/import";
    private static final String RESERVATION_PATH = "/api/business-cards/import/reservation";

    private final BusinessCardRateLimiter rateLimiter;
    private final CapabilityEntitlement capabilityEntitlement;

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
        try {
            BusinessCardIdempotencyKey.canonicalize(request.getHeader("Idempotency-Key"));
        } catch (BadRequestException exception) {
            reject(response, HttpServletResponse.SC_BAD_REQUEST);
            return;
        }
        if (!capabilityEntitlement.isEntitled(Capability.BUSINESS_CARD_IMPORT)) {
            reject(response, HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        Operation admission = operation(request);
        if (admission == null) {
            chain.doFilter(request, response);
            return;
        }
        try {
            switch (admission) {
                case IMPORT -> rateLimiter.requireImportAdmissionAllowed(user.getId());
                case RESERVATION -> rateLimiter.requireReservationAllowed(user.getId());
                case STATUS -> rateLimiter.requireStatusAllowed(user.getId());
            }
        } catch (TooManyRequestsException exception) {
            reject(response, 429);
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

    private static void reject(HttpServletResponse response, int status) {
        response.setHeader("Cache-Control", "no-store");
        response.setStatus(status);
    }

    private enum Operation {
        IMPORT,
        RESERVATION,
        STATUS
    }
}
