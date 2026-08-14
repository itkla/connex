package ooo.klae.connex.backend.services;

import java.io.Serial;
import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;

import org.springframework.stereotype.Service;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.util.OneTimeTokenDigest;

/**
 * Exchanges emailed bearer tokens for purpose-bound, short-lived grants held in the shared Spring
 * Session store. Only the grant digest and source-token digest are retained server-side. A grant is
 * removed before its final operation so replay fails closed even when that operation later fails.
 * The SSO ownership proof remains retryable under its existing rate limit and is removed only after
 * a successful password check.
 */
@Service
public class OneTimeLinkFlowService {

    private static final Duration LIFETIME = Duration.ofMinutes(10);
    private static final String ATTRIBUTE_PREFIX = OneTimeLinkFlowService.class.getName() + ".";
    private static final String INVALID_LINK = "This link is invalid or has expired";

    /** One-time browser-link purposes that must never be interchangeable. */
    public enum Purpose {
        PASSWORD_RESET,
        REGISTRATION_VERIFICATION,
        EMAIL_CHANGE,
        WORKSPACE_INVITE,
        WORKSPACE_INVITE_LINK,
        SSO_LINK
    }

    /**
     * @param request current browser request
     * @param purpose single allowed operation
     * @param sourceTokenHash digest of the original emailed token
     * @return raw browser grant and its cookie lifetime
     */
    public IssuedGrant issue(HttpServletRequest request, Purpose purpose, String sourceTokenHash) {
        String rawGrant = OneTimeTokenDigest.generate();
        FlowState state = new FlowState(
            OneTimeTokenDigest.sha256(rawGrant),
            sourceTokenHash,
            Instant.now().plus(LIFETIME));
        request.getSession(true).setAttribute(attributeName(purpose), state);
        return new IssuedGrant(rawGrant, LIFETIME);
    }

    /**
     * Resolves a valid flow without consuming it, for token-free preview and validation endpoints.
     * @param request current browser request
     * @param purpose expected operation
     * @param rawGrant flow cookie value
     * @return digest of the original emailed token
     */
    public String require(HttpServletRequest request, Purpose purpose, String rawGrant) {
        return resolve(request, purpose, rawGrant, false);
    }

    /**
     * Atomically removes a valid flow from its server-side session before returning its source.
     * @param request current browser request
     * @param purpose expected operation
     * @param rawGrant flow cookie value
     * @return digest of the original emailed token
     */
    public String consume(HttpServletRequest request, Purpose purpose, String rawGrant) {
        return resolve(request, purpose, rawGrant, true);
    }

    /** Replaces an authenticated session while carrying forward only active link-flow state. */
    public void replaceSessionPreservingFlows(HttpServletRequest request) {
        HttpSession existing = request.getSession(false);
        if (existing == null) {
            return;
        }
        FlowState[] flows = new FlowState[Purpose.values().length];
        synchronized (existing) {
            for (Purpose purpose : Purpose.values()) {
                Object value = existing.getAttribute(attributeName(purpose));
                if (value instanceof FlowState state && state.expiresAt().isAfter(Instant.now())) {
                    flows[purpose.ordinal()] = state;
                }
            }
            existing.invalidate();
        }
        HttpSession replacement = request.getSession(true);
        for (Purpose purpose : Purpose.values()) {
            FlowState state = flows[purpose.ordinal()];
            if (state != null) {
                replacement.setAttribute(attributeName(purpose), state);
            }
        }
    }

    private String resolve(HttpServletRequest request, Purpose purpose, String rawGrant, boolean consume) {
        HttpSession session = request.getSession(false);
        if (session == null || rawGrant == null || rawGrant.isBlank()) {
            throw new BadRequestException(INVALID_LINK);
        }
        synchronized (session) {
            Object value = session.getAttribute(attributeName(purpose));
            if (!(value instanceof FlowState state)
                    || !state.expiresAt().isAfter(Instant.now())
                    || !OneTimeTokenDigest.constantTimeEquals(
                        state.grantHash(), OneTimeTokenDigest.sha256(rawGrant))) {
                session.removeAttribute(attributeName(purpose));
                throw new BadRequestException(INVALID_LINK);
            }
            if (consume) {
                session.removeAttribute(attributeName(purpose));
            }
            return state.sourceTokenHash();
        }
    }

    private static String attributeName(Purpose purpose) {
        return ATTRIBUTE_PREFIX + purpose.name();
    }

    /** Browser grant returned once to the cookie writer. */
    public record IssuedGrant(String value, Duration lifetime) {
    }

    private record FlowState(String grantHash, String sourceTokenHash, Instant expiresAt)
            implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
    }
}
