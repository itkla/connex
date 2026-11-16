package ooo.klae.connex.backend.services;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.web.firewall.RequestRejectedException;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Fixed-window JVM-local admission for access-denial audit rows, keyed by user, audit action, denial
 * reason, client address and User-Agent fingerprint (#1850, #1936). The reason is part of the key so
 * that an account whose confinement changes cause, for example from awaiting enrollment to awaiting
 * passkey approval (#1534), records the new reason at once instead of after the previous reason's window.
 *
 * <p>A filter that records a denial before refusing a {@code GET} can be driven from another site. The
 * session cookie rides a cross-site top-level navigation under {@code SameSite=Lax}, and any cross-site
 * subresource load under the {@code SameSite=None} that SAML deployments use. Every visit would otherwise
 * append another row attributed to the victim and advance the shared integrity head. One row per
 * address and stable browser per window still records the fact of the denial. The client address is
 * part of the key because a forged trigger arrives from the victim's own browser, or from the frontend
 * server rendering a page for it, while a stolen session used from elsewhere must keep leaving its
 * own evidence through its own requests.
 * Eight address slots per user, action and reason each admit up to four fingerprints, bounding retained
 * windows at 32 rows. Fingerprints never consume another address slot, extend its window or refresh
 * its eviction timestamp. Without releases, every address-only admission remains admitted while
 * windows are retained. Releases preserve evidence for other fingerprints at the original address
 * expiry: a retry can be suppressed because its fingerprint already has an unreleased admission.
 * Retaining that slot also retains address capacity, so after a release another address can be
 * suppressed where the address-only limiter would have freed capacity. The fingerprint is the unkeyed
 * SHA-256 of the full UTF-8 header, retained only in memory and never persisted, logged or used as a
 * metric tag. Absent and firewall-rejected headers share one sentinel.
 *
 * <p>The bound is per replica, and the address is only as distinct as {@link
 * ooo.klae.connex.backend.util.ClientIpResolver} makes it: behind a proxy that is not configured as
 * trusted, every request resolves to the proxy and only its bounded fingerprints distinguish clients.
 *
 * <p>A suppressed row leaves no audit evidence, so suppression is counted instead:
 * {@code connex.security.denial.audit.suppressed} per action, and
 * {@code connex.security.denial.audit.evictions} for live windows dropped to bound the key set.
 * Operators can then see a forged flood or a saturated limiter.
 */
@Component
public class DenialAuditRateLimiter {
    static final int MAX_ADDRESSES_PER_DENIAL = 8;
    static final int MAX_FINGERPRINTS_PER_ADDRESS = 4;
    static final int MAX_TRACKED_DENIALS = 4_096;
    static final String SUPPRESSED_METRIC = "connex.security.denial.audit.suppressed";
    static final String EVICTED_METRIC = "connex.security.denial.audit.evictions";
    private static final String UNRESOLVED_ADDRESS = "";
    private static final String UNAVAILABLE_USER_AGENT = "";
    private static final Comparator<Map.Entry<Key, Window>> NEWEST_ADMISSION_FIRST = Comparator
            .comparingLong((Map.Entry<Key, Window> entry) -> entry.getValue().latestAdmissionMillis())
            .reversed();

    private final long windowMillis;
    private final Clock clock;
    private final MeterRegistry meterRegistry;
    private final Counter evictions;
    private final AtomicLong admissionSequence = new AtomicLong();
    private final ConcurrentHashMap<Key, Window> windows = new ConcurrentHashMap<>();

    /**
     * An admitted audit row, which the caller hands back through {@link #release} when it could not
     * be written.
     *
     * @param userId denied user
     * @param action audit action the row records
     * @param reason denial reason the row records
     * @param clientAddress client address the row was admitted for, empty when none was resolved
     * @param userAgentFingerprint memory-only header digest, empty for absent or rejected headers
     * @param admittedAtMillis clock sample taken for this admission
     * @param admissionId unique limiter-local identity independent of the clock
     */
    public record Admission(
            int userId, String action, String reason, String clientAddress, String userAgentFingerprint,
            long admittedAtMillis, long admissionId) {
    }

    public DenialAuditRateLimiter(
            @Value("${connex.security.denial-audit-window-seconds:3600}") long windowSeconds,
            Clock clock,
            MeterRegistry meterRegistry) {
        if (windowSeconds <= 0) {
            throw new IllegalArgumentException("Denial audit window must be positive");
        }
        this.windowMillis = Math.multiplyExact(windowSeconds, 1_000L);
        this.clock = clock;
        this.meterRegistry = meterRegistry;
        this.evictions = meterRegistry.counter(EVICTED_METRIC);
    }

    /**
     * Admits at most four distinct User-Agent fingerprints inside each of eight address slots per
     * user, action and reason. An address's window starts with its first admission; later fingerprints
     * do not extend it. Without releases, admission is a strict superset of the address-only rule
     * while windows are retained. After a release, evidence for other admitted fingerprints is
     * preserved until the address's original expiry; their occupied slot also retains its capacity.
     *
     * @param userId denied user
     * @param action audit action the row would record
     * @param reason denial reason the row would record
     * @param clientAddress resolved client address, or null when none could be resolved
     * @param request already-denied request supplying the advisory User-Agent fingerprint
     * @return the admission when the caller may append the audit row, otherwise empty
     */
    public Optional<Admission> acquire(int userId, String action, String reason, String clientAddress,
            HttpServletRequest request) {
        Key key = new Key(userId, action, reason);
        String address = Objects.requireNonNullElse(clientAddress, UNRESOLVED_ADDRESS);
        String fingerprint = userAgentFingerprint(request);
        long admissionId = admissionSequence.incrementAndGet();
        long now = clock.millis();
        AtomicBoolean accepted = new AtomicBoolean();
        windows.compute(key, (existingKey, existing) -> {
            List<Admitted> live = existing == null ? List.of() : existing.liveAt(now, windowMillis);
            Optional<Admitted> slot = live.stream()
                    .filter(admitted -> admitted.address().equals(address)).findFirst();
            if (slot.isPresent()) {
                Admitted admitted = slot.get();
                if (admitted.fingerprints().size() >= MAX_FINGERPRINTS_PER_ADDRESS
                        || admitted.fingerprints().stream().anyMatch(agent -> agent.hash().equals(fingerprint))) {
                    return new Window(live);
                }
                accepted.set(true);
                List<Fingerprint> fingerprints = new ArrayList<>(admitted.fingerprints());
                fingerprints.add(new Fingerprint(fingerprint, admissionId));
                List<Admitted> next = new ArrayList<>(live);
                next.set(next.indexOf(admitted), new Admitted(address, admitted.admittedAtMillis(),
                        List.copyOf(fingerprints)));
                return new Window(List.copyOf(next));
            }
            if (live.size() >= MAX_ADDRESSES_PER_DENIAL) {
                return new Window(live);
            }
            accepted.set(true);
            List<Admitted> next = new ArrayList<>(live);
            next.add(new Admitted(address, now, List.of(new Fingerprint(fingerprint, admissionId))));
            return new Window(List.copyOf(next));
        });
        if (windows.size() > MAX_TRACKED_DENIALS) {
            evict(now);
        }
        if (!accepted.get()) {
            meterRegistry.counter(SUPPRESSED_METRIC, "action", action).increment();
            return Optional.empty();
        }
        return Optional.of(new Admission(userId, action, reason, address, fingerprint, now, admissionId));
    }

    /**
     * Hands back only the fingerprint admission whose audit row could not be written, matching its
     * address, fingerprint and unique admission identity. Clock samples never identify admissions,
     * so a paused acquisition or same-millisecond retry cannot let a stale release drop a newer one.
     *
     * <p>Releasing the first or any later fingerprint permits that fingerprint to retry immediately.
     * Other fingerprints keep their suppression and the address's original expiry. Removing the last
     * fingerprint drops the address slot, so a failed first append with no later admissions leaves
     * exactly the address-only limiter's retry, capacity and window-start behavior. When later rows
     * remain, their slot stays occupied until the original expiry rather than re-admitting duplicates.
     *
     * @param admission the admission {@link #acquire} returned
     */
    public void release(Admission admission) {
        Key admissionKey = new Key(admission.userId(), admission.action(), admission.reason());
        windows.computeIfPresent(admissionKey, (key, existing) -> {
            List<Admitted> remaining = new ArrayList<>();
            for (Admitted admitted : existing.admitted()) {
                if (!admitted.address().equals(admission.clientAddress())) {
                    remaining.add(admitted);
                    continue;
                }
                List<Fingerprint> fingerprints = admitted.fingerprints().stream()
                        .filter(agent -> !agent.hash().equals(admission.userAgentFingerprint())
                                || agent.admissionId() != admission.admissionId())
                        .toList();
                if (!fingerprints.isEmpty()) {
                    remaining.add(new Admitted(admitted.address(), admitted.admittedAtMillis(), fingerprints));
                }
            }
            return remaining.isEmpty() ? null : new Window(List.copyOf(remaining));
        });
    }

    /**
     * Bounds the tracked key set, dropping fully expired denials before the least recently admitted
     * live ones.
     *
     * <p>Evicting a live key re-arms it: each of its eight addresses may record four more rows early.
     * While more keys than the limit stay live, the oldest can be evicted again and again, so the
     * per-key bound only holds below that limit. Losing evidence is the worse failure, so the limit is
     * enforced by dropping the oldest suppression rather than by refusing to admit a row.
     *
     * <p>Once saturated this runs for every new key, so it stays one pass over the cached latest
     * admission of each window: the excess oldest are kept in a heap of that size, which is a single
     * minimum scan for the usual excess of one.
     */
    private void evict(long now) {
        windows.forEach((key, window) -> {
            if (window.expiredAt(now, windowMillis)) {
                windows.remove(key, window);
            }
        });
        int excess = windows.size() - MAX_TRACKED_DENIALS;
        if (excess <= 0) {
            return;
        }
        PriorityQueue<Map.Entry<Key, Window>> oldest = new PriorityQueue<>(excess, NEWEST_ADMISSION_FIRST);
        for (Map.Entry<Key, Window> entry : windows.entrySet()) {
            oldest.offer(entry);
            if (oldest.size() > excess) {
                oldest.poll();
            }
        }
        for (Map.Entry<Key, Window> entry : oldest) {
            if (windows.remove(entry.getKey(), entry.getValue())) {
                evictions.increment();
            }
        }
    }

    int trackedDenials() {
        return windows.size();
    }

    /**
     * Returns the milliseconds since {@code startedAt}: zero for a start in the future, and saturated
     * rather than wrapped for one beyond the {@code long} range. It therefore never decreases as the
     * start moves earlier, which is what lets a window's expiry be decided by its latest admission.
     */
    private static long elapsed(long now, long startedAt) {
        if (startedAt >= now) {
            return 0L;
        }
        long elapsed = now - startedAt;
        return elapsed < 0 ? Long.MAX_VALUE : elapsed;
    }

    private record Key(int userId, String action, String reason) {
    }

    /** Absent and firewall-rejected headers never prevent an already-decided denial from recording. */
    private static String userAgentFingerprint(HttpServletRequest request) {
        String userAgent;
        try {
            userAgent = request.getHeader("User-Agent");
        } catch (RequestRejectedException rejected) {
            return UNAVAILABLE_USER_AGENT;
        }
        if (userAgent == null) {
            return UNAVAILABLE_USER_AGENT;
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(userAgent.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record Fingerprint(String hash, long admissionId) {
    }

    private record Admitted(String address, long admittedAtMillis, List<Fingerprint> fingerprints) {
    }

    private record Window(List<Admitted> admitted, long latestAdmissionMillis) {
        private Window(List<Admitted> admitted) {
            this(admitted,
                    admitted.stream().mapToLong(Admitted::admittedAtMillis).max().orElse(Long.MIN_VALUE));
        }

        private List<Admitted> liveAt(long now, long windowMillis) {
            return admitted.stream()
                    .filter(entry -> elapsed(now, entry.admittedAtMillis()) < windowMillis)
                    .toList();
        }

        private boolean expiredAt(long now, long windowMillis) {
            return admitted.isEmpty() || elapsed(now, latestAdmissionMillis) >= windowMillis;
        }
    }
}
