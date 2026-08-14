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
 * Session store. Each browser session owns a random private binding; only its SHA-256 digest is
 * persisted with a source token. Re-presenting that token from the same session during the token's
 * original validity reissues the same derived grant, while another session fails closed. A final
 * operation validates its grant before the domain transaction and removes it only after that
 * transaction succeeds, so a transient failure does not strand an otherwise valid link.
 */
@Service
public class OneTimeLinkFlowService {

    private static final Duration LIFETIME = Duration.ofMinutes(10);
    private static final String ATTRIBUTE_PREFIX = OneTimeLinkFlowService.class.getName() + ".";
    private static final String BINDING_ATTRIBUTE = ATTRIBUTE_PREFIX + "BINDING";
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
        HttpSession session = request.getSession(true);
        String rawGrant = derivedGrant(sessionBinding(session), purpose, sourceTokenHash);
        FlowState state = new FlowState(
            OneTimeTokenDigest.sha256(rawGrant),
            sourceTokenHash,
            Instant.now().plus(LIFETIME));
        session.setAttribute(attributeName(purpose), state);
        return new IssuedGrant(rawGrant, LIFETIME);
    }

    /**
     * Returns the durable exchange owner for the current browser session. The random binding stays
     * inside Spring Session; source-token rows receive only this one-way digest.
     * @param request current browser request
     * @return SHA-256 digest of the private session binding
     */
    public String exchangeBindingHash(HttpServletRequest request) {
        return OneTimeTokenDigest.sha256(sessionBinding(request.getSession(true)));
    }

    /**
     * Resolves a valid flow without consuming it, for token-free preview and validation endpoints.
     * @param request current browser request
     * @param purpose expected operation
     * @param rawGrant flow cookie value
     * @return digest of the original emailed token
     */
    public String require(HttpServletRequest request, Purpose purpose, String rawGrant) {
        return requireFlow(request, purpose, rawGrant).sourceTokenHash();
    }

    /**
     * Resolves a valid flow and its non-authorizing identity for a token-free preview.
     * @param request current browser request
     * @param purpose expected operation
     * @param rawGrant flow cookie value
     * @return source-token digest and preview identity
     */
    public ResolvedFlow requireFlow(HttpServletRequest request, Purpose purpose, String rawGrant) {
        return resolve(request, purpose, rawGrant);
    }

    /**
     * Resolves a flow only when its final request carries the identity returned by its preview.
     * @param request current browser request
     * @param purpose expected operation
     * @param rawGrant flow cookie value
     * @param flowId preview identity shown for the operation
     * @return digest of the original emailed token
     */
    public String requireBound(
            HttpServletRequest request, Purpose purpose, String rawGrant, String flowId) {
        ResolvedFlow flow = resolve(request, purpose, rawGrant);
        if (!OneTimeTokenDigest.constantTimeEquals(flow.flowId(), flowId)) {
            throw new BadRequestException(INVALID_LINK);
        }
        return flow.sourceTokenHash();
    }

    /** Removes the matching flow after its domain operation has committed successfully. */
    public void complete(HttpServletRequest request, Purpose purpose, String rawGrant) {
        HttpSession session = request.getSession(false);
        if (session == null || rawGrant == null || rawGrant.isBlank()) {
            return;
        }
        synchronized (session) {
            Object value = session.getAttribute(attributeName(purpose));
            if (value instanceof FlowState state
                    && OneTimeTokenDigest.constantTimeEquals(
                        state.grantHash(), OneTimeTokenDigest.sha256(rawGrant))) {
                session.removeAttribute(attributeName(purpose));
            }
        }
    }

    /** Replaces an authenticated session while carrying forward only active link-flow state. */
    public void replaceSessionPreservingFlows(HttpServletRequest request) {
        HttpSession existing = request.getSession(false);
        if (existing == null) {
            return;
        }
        FlowState[] flows = new FlowState[Purpose.values().length];
        String binding = null;
        synchronized (existing) {
            Object bindingValue = existing.getAttribute(BINDING_ATTRIBUTE);
            if (bindingValue instanceof String value && !value.isBlank()) {
                binding = value;
            }
            for (Purpose purpose : Purpose.values()) {
                Object value = existing.getAttribute(attributeName(purpose));
                if (value instanceof FlowState state && state.expiresAt().isAfter(Instant.now())) {
                    flows[purpose.ordinal()] = state;
                }
            }
            existing.invalidate();
        }
        HttpSession replacement = request.getSession(true);
        if (binding != null) {
            replacement.setAttribute(BINDING_ATTRIBUTE, binding);
        }
        for (Purpose purpose : Purpose.values()) {
            FlowState state = flows[purpose.ordinal()];
            if (state != null) {
                replacement.setAttribute(attributeName(purpose), state);
            }
        }
    }

    private ResolvedFlow resolve(HttpServletRequest request, Purpose purpose, String rawGrant) {
        HttpSession session = request.getSession(false);
        if (session == null || rawGrant == null || rawGrant.isBlank()) {
            throw new BadRequestException(INVALID_LINK);
        }
        synchronized (session) {
            Object value = session.getAttribute(attributeName(purpose));
            if (!(value instanceof FlowState state)) {
                throw new BadRequestException(INVALID_LINK);
            }
            if (!state.expiresAt().isAfter(Instant.now())) {
                session.removeAttribute(attributeName(purpose));
                throw new BadRequestException(INVALID_LINK);
            }
            if (!OneTimeTokenDigest.constantTimeEquals(
                    state.grantHash(), OneTimeTokenDigest.sha256(rawGrant))) {
                throw new BadRequestException(INVALID_LINK);
            }
            return new ResolvedFlow(state.sourceTokenHash(), state.grantHash());
        }
    }

    private static String sessionBinding(HttpSession session) {
        synchronized (session) {
            Object existing = session.getAttribute(BINDING_ATTRIBUTE);
            if (existing instanceof String value && !value.isBlank()) {
                return value;
            }
            String binding = OneTimeTokenDigest.generate();
            session.setAttribute(BINDING_ATTRIBUTE, binding);
            return binding;
        }
    }

    private static String derivedGrant(String binding, Purpose purpose, String sourceTokenHash) {
        if (sourceTokenHash == null || sourceTokenHash.isBlank()) {
            throw new BadRequestException(INVALID_LINK);
        }
        return OneTimeTokenDigest.sha256(
            binding + ":" + purpose.name() + ":" + sourceTokenHash);
    }

    private static String attributeName(Purpose purpose) {
        return ATTRIBUTE_PREFIX + purpose.name();
    }

    /** Browser grant returned once to the cookie writer. */
    public record IssuedGrant(String value, Duration lifetime) {
    }

    /** Server-resolved source and non-authorizing preview identity for one browser flow. */
    public record ResolvedFlow(String sourceTokenHash, String flowId) {
    }

    private record FlowState(String grantHash, String sourceTokenHash, Instant expiresAt)
            implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
    }
}
