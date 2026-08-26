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

    private static final Duration LIFETIME = Duration.ofMinutes(10);
    private static final String SESSION_LINEAGE_ATTRIBUTE =
        OneTimeLinkFlowService.class.getName() + ".SESSION_LINEAGE";
    private static final String INVALID_LINK = "This link is invalid or has expired";
    private static final Pattern BROWSER_BINDING_PATTERN = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final Pattern HASH_PATTERN = Pattern.compile("[0-9a-f]{64}");

    private final OneTimeLinkFlowMapper flowMapper;
    private final OneTimeLinkFlowClaimService claimService;

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
        Cookie cookie = WebUtils.getCookie(request, BROWSER_BINDING_COOKIE);
        String binding = cookie == null ? null : cookie.getValue();
        return issue(request, binding, purpose, sourceTokenHash);
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
            LIFETIME.toSeconds());
        return new IssuedGrant(rawGrant, LIFETIME);
    }

    /**
     * Returns the durable owner for a source-token exchange.
     * @param request request carrying the private cookie and its server-session lineage
     * @return SHA-256 digest of the combined owner
     */
    public String exchangeOwnerHash(HttpServletRequest request) {
        Cookie cookie = WebUtils.getCookie(request, BROWSER_BINDING_COOKIE);
        String binding = cookie == null ? null : cookie.getValue();
        return exchangeOwnerHash(request, binding);
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
