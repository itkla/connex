package ooo.klae.connex.backend.services;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.WebUtils;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.mappers.OneTimeLinkFlowMapper;
import ooo.klae.connex.backend.services.OneTimeLinkFlowClaimService.Claim;
import ooo.klae.connex.backend.util.OneTimeTokenDigest;

/**
 * Exchanges one-time source bearers for purpose-bound grants persisted on the control plane. Each
 * exchange owner combines a private HttpOnly browser cookie with a random lineage held only in the
 * JDBC-backed servlet session. Re-presenting a source token from that same lineage during its
 * original validity renews the same grant after a delivery failure; a different or expired servlet
 * session fails closed even if it presents the browser cookie. Login and SSO session rotation carry
 * the lineage forward explicitly, while ordinary session replacement does not.
 *
 * <p>Control-plane final operations claim, mutate, and delete the grant in one transaction. Tenant
 * membership operations receive a completion callback that must run inside their existing
 * workspace transaction, where control-catalog routing makes the domain mutation and grant delete
 * one database commit. Failures therefore leave both the source token and grant retryable. Claims
 * are never stolen on elapsed wall-clock time; after process loss, the owning lineage can renew an
 * expired flow from the still-valid source token, which clears the abandoned claim.
 */
@Service
@RequiredArgsConstructor
public class OneTimeLinkFlowService {

    public static final String BROWSER_BINDING_COOKIE = "connex_one_time_link_binding";

    private static final String SESSION_LINEAGE_ATTRIBUTE =
        OneTimeLinkFlowService.class.getName() + ".SESSION_LINEAGE";
    private static final String INVALID_LINK = "This link is invalid or has expired";
    private static final Pattern BROWSER_BINDING_PATTERN = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final Pattern HASH_PATTERN = Pattern.compile("[0-9a-f]{64}");

    private final OneTimeLinkFlowMapper flowMapper;
    private final OneTimeLinkFlowClaimService claimService;

    /**
     * One-time browser-link purposes that must never be interchangeable. Each purpose carries the
     * lifetime of the grant it issues; document acceptance is longer because reading a contract
     * routinely exceeds a ten-minute exchange window, while the recipient token's own expiry and
     * terminal state still bound every operation the grant can reach.
     */
    public enum Purpose {
        PASSWORD_RESET(Duration.ofMinutes(10)),
        REGISTRATION_VERIFICATION(Duration.ofMinutes(10)),
        EMAIL_CHANGE(Duration.ofMinutes(10)),
        WORKSPACE_INVITE(Duration.ofMinutes(10)),
        WORKSPACE_INVITE_LINK(Duration.ofMinutes(10)),
        SSO_LINK(Duration.ofMinutes(10)),
        DOCUMENT_ACCEPTANCE(Duration.ofMinutes(60)),
        DELIVERY_UNSUBSCRIBE(Duration.ofMinutes(10));

        private final Duration lifetime;

        Purpose(Duration lifetime) {
            this.lifetime = lifetime;
        }

        /** Returns how long a grant issued for this purpose stays valid. */
        public Duration lifetime() {
            return lifetime;
        }
    }

    /**
     * Establishes the server-side half of a browser exchange owner during CSRF bootstrap.
     * @param request browser request whose servlet session owns the lineage
     * @param browserBinding raw private binding delivered to the browser
     */
    public void establishBrowserBinding(HttpServletRequest request, String browserBinding) {
        requireValidBinding(browserBinding);
        sessionLineage(request.getSession(true), true);
    }

    /**
     * @param request current browser request carrying both halves of the exchange owner
     * @param purpose single allowed operation
     * @param sourceTokenHash digest of the original bearer
     * @return deterministic raw browser grant and its cookie lifetime
     */
    @Transactional
    public IssuedGrant issue(HttpServletRequest request, Purpose purpose, String sourceTokenHash) {
        return issue(request, browserBinding(request), purpose, sourceTokenHash, null);
    }

    /**
     * Issues a flow whose source lives on a tenant plane, remembering the routing hint the token
     * carried so a token-free endpoint can find the tenant without any caller-supplied identifier.
     * The hint is a lookup aid only: the tenant row is still matched by the source-token digest.
     * @param request current browser request carrying both halves of the exchange owner
     * @param purpose single allowed operation
     * @param sourceTokenHash digest of the original bearer
     * @param routingWorkspaceId workspace the validated source token routed to
     * @return deterministic raw browser grant and its cookie lifetime
     */
    @Transactional
    public IssuedGrant issueRouted(
            HttpServletRequest request,
            Purpose purpose,
            String sourceTokenHash,
            int routingWorkspaceId) {
        return issue(
            request, browserBinding(request), purpose, sourceTokenHash, routingWorkspaceId);
    }

    /**
     * Issues a flow when a binding was created earlier in the same response, as in SSO linking.
     * @param request current request carrying the server-session lineage
     * @param browserBinding raw binding being delivered in the current response
     * @param purpose single allowed operation
     * @param sourceTokenHash digest of the server-created challenge
     * @return deterministic raw browser grant and its cookie lifetime
     */
    @Transactional
    public IssuedGrant issue(
            HttpServletRequest request,
            String browserBinding,
            Purpose purpose,
            String sourceTokenHash) {
        return issue(request, browserBinding, purpose, sourceTokenHash, null);
    }

    private IssuedGrant issue(
            HttpServletRequest request,
            String browserBinding,
            Purpose purpose,
            String sourceTokenHash,
            Integer routingWorkspaceId) {
        requireValidHash(sourceTokenHash);
        String exchangeOwnerHash = exchangeOwnerHash(request, browserBinding);
        String rawGrant = derivedGrant(exchangeOwnerHash, purpose, sourceTokenHash);
        flowMapper.clearExpiredClaim(
            OneTimeTokenDigest.sha256(rawGrant), exchangeOwnerHash, purpose.name());
        flowMapper.upsert(
            OneTimeTokenDigest.sha256(rawGrant),
            exchangeOwnerHash,
            purpose.name(),
            sourceTokenHash,
            routingWorkspaceId,
            purpose.lifetime().toSeconds());
        return new IssuedGrant(rawGrant, purpose.lifetime());
    }

    private static String browserBinding(HttpServletRequest request) {
        Cookie cookie = WebUtils.getCookie(request, BROWSER_BINDING_COOKIE);
        return cookie == null ? null : cookie.getValue();
    }

    /**
     * Returns the durable owner for a source-token exchange.
     * @param request request carrying the private cookie and its server-session lineage
     * @return SHA-256 digest of the combined owner
     */
    public String exchangeOwnerHash(HttpServletRequest request) {
        return exchangeOwnerHash(request, browserBinding(request));
    }

    private static String exchangeOwnerHash(
            HttpServletRequest request, String binding) {
        requireValidBinding(binding);
        HttpSession session = request.getSession(false);
        if (session == null) {
            throw invalidLink();
        }
        String lineage = sessionLineage(session, false);
        if (lineage == null) {
            throw invalidLink();
        }
        return OneTimeTokenDigest.sha256(binding + ":" + lineage);
    }

    /** Resolves a valid flow without consuming it for a token-free validation endpoint. */
    public String require(HttpServletRequest request, Purpose purpose, String rawGrant) {
        return requireFlow(request, purpose, rawGrant).sourceTokenHash();
    }

    /** Resolves a valid flow and its non-authorizing identity for a token-free preview. */
    public ResolvedFlow requireFlow(HttpServletRequest request, Purpose purpose, String rawGrant) {
        String grantHash = grantHash(rawGrant);
        String sourceTokenHash = flowMapper.findValidSourceTokenHash(
            grantHash,
            exchangeOwnerHash(request),
            purpose.name());
        if (sourceTokenHash == null) {
            throw invalidLink();
        }
        return new ResolvedFlow(sourceTokenHash, grantHash);
    }

    /**
     * Resolves a valid flow for a token-free final operation only when the request echoes the
     * preview identity it was rendered from, so a grant another tab swapped in cannot decide it.
     * @param request current browser request
     * @param purpose expected operation
     * @param rawGrant flow cookie value
     * @param flowId preview identity shown for the operation
     * @return the source digest and non-authorizing identity
     */
    public ResolvedFlow requireBoundFlow(
            HttpServletRequest request, Purpose purpose, String rawGrant, String flowId) {
        if (!OneTimeTokenDigest.constantTimeEquals(grantHash(rawGrant), flowId)) {
            throw invalidLink();
        }
        return requireFlow(request, purpose, rawGrant);
    }

    /**
     * Resolves a valid routed flow without consuming it. A grant issued without a routing hint can
     * never route, so it fails closed exactly like an unknown or expired grant.
     * @param request current browser request
     * @param purpose expected operation
     * @param rawGrant flow cookie value
     * @return the source digest, non-authorizing identity, and tenant routing hint
     */
    public RoutedFlow requireRoutedFlow(
            HttpServletRequest request, Purpose purpose, String rawGrant) {
        ResolvedFlow flow = requireFlow(request, purpose, rawGrant);
        Integer workspaceId = flowMapper.findValidRoutingWorkspaceId(
            flow.flowId(),
            exchangeOwnerHash(request),
            purpose.name());
        if (workspaceId == null) {
            throw invalidLink();
        }
        return new RoutedFlow(flow.sourceTokenHash(), flow.flowId(), workspaceId);
    }

    /**
     * Runs a control-plane final operation in the same transaction as its grant claim and delete.
     * @param request current browser request
     * @param purpose expected operation
     * @param rawGrant flow cookie value
     * @param operation transactional domain operation receiving the source-token digest
     */
    @Transactional
    public void consume(
            HttpServletRequest request,
            Purpose purpose,
            String rawGrant,
            Consumer<String> operation) {
        consumeClaimed(request, purpose, rawGrant, operation);
    }

    /**
     * Runs password-reset completion at READ COMMITTED so privilege revalidation after locking
     * observes a concurrent promotion that committed while this flow was waiting.
     * @param request current browser request
     * @param rawGrant password-reset flow cookie value
     * @param operation transactional reset operation receiving the source-token digest
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void consumePasswordReset(
            HttpServletRequest request,
            String rawGrant,
            Consumer<String> operation) {
        consumeClaimed(request, Purpose.PASSWORD_RESET, rawGrant, operation);
    }

    private void consumeClaimed(
            HttpServletRequest request,
            Purpose purpose,
            String rawGrant,
            Consumer<String> operation) {
        Claim claim = claimService.claimInCurrentTransaction(
            grantHash(rawGrant), exchangeOwnerHash(request), purpose);
        operation.accept(claim.sourceTokenHash());
        claimService.completeInCurrentTransaction(claim);
    }

    /**
     * Runs a tenant final operation only when the request carries its preview identity. The domain
     * operation must invoke the supplied completion exactly once inside its workspace transaction.
     * @param <T> non-null domain result type
     * @param request current browser request
     * @param purpose expected operation
     * @param rawGrant flow cookie value
     * @param flowId preview identity shown for the operation
     * @param operation tenant transaction operation and in-transaction completion callback
     * @return non-null domain result
     */
    public <T> T consumeBound(
            HttpServletRequest request,
            Purpose purpose,
            String rawGrant,
            String flowId,
            TransactionCompletingOperation<T> operation) {
        String grantHash = grantHash(rawGrant);
        if (!OneTimeTokenDigest.constantTimeEquals(grantHash, flowId)) {
            throw invalidLink();
        }
        Claim claim = claimService.claim(grantHash, exchangeOwnerHash(request), purpose);
        AtomicBoolean completed = new AtomicBoolean();
        Runnable completion = () -> {
            if (completed.get()) {
                throw new IllegalStateException("One-time-link flow completion was invoked twice");
            }
            claimService.completeInCurrentTransaction(claim);
            completed.set(true);
        };
        try {
            T result = Objects.requireNonNull(
                operation.apply(claim.sourceTokenHash(), completion),
                "one-time-link domain result");
            if (!completed.get()) {
                throw new IllegalStateException(
                    "One-time-link domain transaction did not complete its flow");
            }
            return result;
        } catch (RuntimeException exception) {
            releaseAfterFailure(claim, exception);
            throw exception;
        }
    }

    /** Carries the exchange lineage across an explicitly authorized login or SSO session reset. */
    public void replaceSessionPreservingFlows(HttpServletRequest request) {
        HttpSession existing = request.getSession(false);
        String lineage = existing == null ? null : sessionLineage(existing, false);
        if (existing != null) {
            existing.invalidate();
        }
        HttpSession replacement = request.getSession(true);
        replacement.setAttribute(
            SESSION_LINEAGE_ATTRIBUTE,
            lineage == null ? OneTimeTokenDigest.generate() : lineage);
    }

    /** Browser grant returned once to the cookie writer. */
    public record IssuedGrant(String value, Duration lifetime) {
    }

    /** Server-resolved source and non-authorizing preview identity for one browser flow. */
    public record ResolvedFlow(String sourceTokenHash, String flowId) {
    }

    /** Resolved flow whose source lives in the hinted tenant workspace. */
    public record RoutedFlow(String sourceTokenHash, String flowId, int workspaceId) {
    }

    /** Tenant operation that deletes its grant inside the transaction that applies its mutation. */
    @FunctionalInterface
    public interface TransactionCompletingOperation<T> {
        T apply(String sourceTokenHash, Runnable completion);
    }

    private void releaseAfterFailure(Claim claim, RuntimeException failure) {
        try {
            claimService.release(claim);
        } catch (RuntimeException releaseFailure) {
            failure.addSuppressed(releaseFailure);
        }
    }

    private static String sessionLineage(HttpSession session, boolean create) {
        Object existing = session.getAttribute(SESSION_LINEAGE_ATTRIBUTE);
        if (existing instanceof String value && BROWSER_BINDING_PATTERN.matcher(value).matches()) {
            return value;
        }
        if (!create) {
            return null;
        }
        String lineage = OneTimeTokenDigest.generate();
        session.setAttribute(SESSION_LINEAGE_ATTRIBUTE, lineage);
        return lineage;
    }

    private static void requireValidBinding(String binding) {
        if (binding == null || !BROWSER_BINDING_PATTERN.matcher(binding).matches()) {
            throw invalidLink();
        }
    }

    private static void requireValidHash(String hash) {
        if (hash == null || !HASH_PATTERN.matcher(hash).matches()) {
            throw invalidLink();
        }
    }

    private static String grantHash(String rawGrant) {
        if (rawGrant == null || rawGrant.isBlank()) {
            throw invalidLink();
        }
        return OneTimeTokenDigest.sha256(rawGrant);
    }

    private static String derivedGrant(
            String exchangeOwnerHash, Purpose purpose, String sourceTokenHash) {
        return OneTimeTokenDigest.sha256(
            exchangeOwnerHash + ":" + purpose.name() + ":" + sourceTokenHash);
    }

    private static BadRequestException invalidLink() {
        return new BadRequestException(INVALID_LINK);
    }
}
