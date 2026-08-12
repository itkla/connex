package ooo.klae.connex.backend.ai;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;
import ooo.klae.connex.backend.exceptions.TooManyRequestsException;
import ooo.klae.connex.backend.services.WorkspaceService;

/**
 * Coordinates cache-miss AI invocations with organization quotas, forced-refresh throttling, and
 * per-cache-identity-and-content-hash single-flight execution. All quotas and flights are local to
 * one JVM replica; cluster-wide enforcement requires a shared coordinator. Each admission scans
 * the bounded organization-quota and refresh-identity registries for stale entries while holding
 * the state lock, so operators should size those capacities with that linear scan cost in mind.
 */
@Component
@Slf4j
public class AiInvocationAdmissionService {
    static final Duration FOLLOWER_WAIT = Duration.ofSeconds(60);

    private final WorkspaceService workspaceService;
    private final Clock clock;
    private final int quotaAttemptsPerOrg;
    private final Duration quotaWindow;
    private final Duration refreshThrottle;
    private final int quotaMaxOrganizations;
    private final int refreshMaxIdentities;
    private final int maxActiveFlights;
    private final Duration followerWait;
    private final Object stateLock = new Object();
    private final Map<Integer, QuotaState> quotaWindows = new LinkedHashMap<>();
    private final Map<CacheIdentity, Instant> refreshTimestamps = new LinkedHashMap<>();
    private final Map<FlightIdentity, FlightState> activeFlights = new HashMap<>();

    @Autowired
    public AiInvocationAdmissionService(
            AiProperties properties,
            WorkspaceService workspaceService,
            Clock clock) {
        this(properties, workspaceService, clock, FOLLOWER_WAIT);
    }

    AiInvocationAdmissionService(
            AiProperties properties,
            WorkspaceService workspaceService,
            Clock clock,
            Duration followerWait) {
        AiProperties configured = Objects.requireNonNull(properties, "properties");
        this.workspaceService = Objects.requireNonNull(workspaceService, "workspaceService");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.followerWait = positiveDuration(followerWait, "follower wait");
        quotaAttemptsPerOrg = positive(
                configured.getInvocationQuotaAttemptsPerOrg(), "quota attempts per organization");
        quotaWindow = positiveDuration(configured.getInvocationQuotaWindow(), "quota window");
        refreshThrottle = positiveDuration(
                configured.getInvocationRefreshThrottle(), "refresh throttle");
        quotaMaxOrganizations = positive(
                configured.getInvocationQuotaMaxOrganizations(), "quota organization capacity");
        refreshMaxIdentities = positive(
                configured.getInvocationRefreshMaxIdentities(), "refresh identity capacity");
        maxActiveFlights = positive(
                configured.getInvocationMaxActiveFlights(), "active flight capacity");
    }

    /**
     * Joins an existing flight or admits one new cache-miss provider attempt. Followers consume no
     * quota and wait for the registered leader; rejected admissions never invoke the provider.
     * @param identity persistent cache identity
     * @param contentHash output-shaping content fingerprint
     * @param refresh whether the caller is forcing a cache refresh
     * @return admission decision and lifecycle handle
     */
    public Admission acquire(CacheIdentity identity, String contentHash, boolean refresh) {
        CacheIdentity key = Objects.requireNonNull(identity, "identity");
        String hash = Objects.requireNonNull(contentHash, "contentHash");
        if (hash.isBlank()) {
            throw new IllegalArgumentException("AI invocation content hash must not be blank");
        }
        FlightIdentity flightIdentity = new FlightIdentity(key, hash);
        int orgId = workspaceService.getCurrentOrgId();
        if (orgId <= 0) {
            throw new IllegalStateException("AI invocation organization is unavailable");
        }
        Instant now = clock.instant();
        synchronized (stateLock) {
            purgeStale(now);
            FlightState current = activeFlights.get(flightIdentity);
            if (current != null) {
                return Admission.follower(this, flightIdentity, current);
            }
            Rejection capacity = capacityRejection(key, orgId, refresh);
            if (capacity != Rejection.NONE) {
                return rejected(orgId, capacity);
            }
            if (refresh && refreshIsThrottled(key, now)) {
                return rejected(orgId, Rejection.REFRESH_THROTTLE);
            }
            QuotaState quota = quotaWindows.get(orgId);
            if (quota != null && quota.size() >= quotaAttemptsPerOrg) {
                return rejected(orgId, Rejection.ORGANIZATION_QUOTA);
            }
            if (quota == null) {
                quota = new QuotaState();
                quotaWindows.put(orgId, quota);
            }
            quota.reserve();
            if (refresh) {
                refreshTimestamps.put(key, now);
            }
            FlightState flight = new FlightState(orgId);
            activeFlights.put(flightIdentity, flight);
            return Admission.leader(this, flightIdentity, flight);
        }
    }

    /**
     * Reserves one organization-quota attempt for an interactive, non-cacheable model call.
     * Rejected reservations never invoke the provider, and an uncommitted close releases quota.
     * @return active direct invocation admission
     */
    public DirectAdmission acquireDirect() {
        int orgId = workspaceService.getCurrentOrgId();
        if (orgId <= 0) {
            throw new IllegalStateException("AI invocation organization is unavailable");
        }
        Instant now = clock.instant();
        synchronized (stateLock) {
            purgeStale(now);
            if (!quotaWindows.containsKey(orgId)
                    && quotaWindows.size() >= quotaMaxOrganizations) {
                rejected(orgId, Rejection.CAPACITY);
                throw new DirectAdmissionRejectedException(Rejection.CAPACITY);
            }
            QuotaState quota = quotaWindows.get(orgId);
            if (quota != null && quota.size() >= quotaAttemptsPerOrg) {
                rejected(orgId, Rejection.ORGANIZATION_QUOTA);
                throw new DirectAdmissionRejectedException(Rejection.ORGANIZATION_QUOTA);
            }
            if (quota == null) {
                quota = new QuotaState();
                quotaWindows.put(orgId, quota);
            }
            quota.reserve();
            return new DirectAdmission(this, orgId);
        }
    }

    private Admission rejected(int orgId, Rejection rejection) {
        log.warn("AI invocation rejected: organizationId={}, reason={}", orgId, rejection);
        return Admission.rejected(rejection);
    }

    private Rejection capacityRejection(CacheIdentity identity, int orgId, boolean refresh) {
        if (activeFlights.size() >= maxActiveFlights) {
            return Rejection.CAPACITY;
        }
        if (!quotaWindows.containsKey(orgId)
                && quotaWindows.size() >= quotaMaxOrganizations) {
            return Rejection.CAPACITY;
        }
        if (refresh
                && !refreshTimestamps.containsKey(identity)
                && refreshTimestamps.size() >= refreshMaxIdentities) {
            return Rejection.CAPACITY;
        }
        return Rejection.NONE;
    }

    private boolean refreshIsThrottled(CacheIdentity identity, Instant now) {
        Instant previous = refreshTimestamps.get(identity);
        return previous != null
                && Duration.between(previous, now).compareTo(refreshThrottle) < 0;
    }

    private void purgeStale(Instant now) {
        Instant quotaCutoff = now.minus(quotaWindow);
        List<Integer> staleOrganizations = new ArrayList<>();
        for (Map.Entry<Integer, QuotaState> entry : quotaWindows.entrySet()) {
            QuotaState quota = entry.getValue();
            quota.purge(quotaCutoff);
            if (quota.isEmpty()) {
                staleOrganizations.add(entry.getKey());
            }
        }
        staleOrganizations.forEach(quotaWindows::remove);
        refreshTimestamps.entrySet().removeIf(entry ->
                Duration.between(entry.getValue(), now)
                        .compareTo(refreshThrottle) >= 0);
    }

    private void commitInvocation(FlightIdentity identity, FlightState flight) {
        synchronized (stateLock) {
            if (activeFlights.get(identity) != flight) {
                throw new IllegalStateException("AI invocation flight is unavailable");
            }
            if (flight.invocationCommitted) {
                return;
            }
            QuotaState quota = quotaWindows.get(flight.orgId);
            if (quota == null) {
                throw new IllegalStateException("AI invocation quota reservation is unavailable");
            }
            quota.commit(clock.instant());
            flight.invocationCommitted = true;
        }
    }

    private void commitDirect(int orgId) {
        synchronized (stateLock) {
            QuotaState quota = quotaWindows.get(orgId);
            if (quota == null) {
                throw new IllegalStateException("AI invocation quota reservation is unavailable");
            }
            quota.commit(clock.instant());
        }
    }

    private void releaseDirect(int orgId) {
        synchronized (stateLock) {
            QuotaState quota = quotaWindows.get(orgId);
            if (quota == null) {
                return;
            }
            quota.release();
            if (quota.isEmpty()) {
                quotaWindows.remove(orgId);
            }
        }
    }

    private void complete(
            FlightIdentity identity,
            FlightState flight,
            LeaderOutcome outcome) {
        synchronized (stateLock) {
            if (activeFlights.get(identity) == flight) {
                if (!flight.invocationCommitted) {
                    QuotaState quota = quotaWindows.get(flight.orgId);
                    if (quota != null) {
                        quota.release();
                        if (quota.isEmpty()) {
                            quotaWindows.remove(flight.orgId);
                        }
                    }
                }
                activeFlights.remove(identity);
            }
            flight.completion.complete(outcome);
        }
    }

    int quotaStateSize() {
        synchronized (stateLock) {
            return quotaWindows.size();
        }
    }

    int refreshStateSize() {
        synchronized (stateLock) {
            return refreshTimestamps.size();
        }
    }

    int activeFlightCount() {
        synchronized (stateLock) {
            return activeFlights.size();
        }
    }

    private static int positive(int value, String name) {
        if (value <= 0) {
            throw new IllegalStateException("AI invocation " + name + " must be positive");
        }
        return value;
    }

    private static Duration positiveDuration(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalStateException("AI invocation " + name + " must be positive");
        }
        return value;
    }

    /** Persistent identity used for cache lookup and forced-refresh admission. */
    public record CacheIdentity(
            int workspaceId,
            AiFeature feature,
            List<Integer> subjectIds,
            String locale) {

        public CacheIdentity {
            if (workspaceId <= 0) {
                throw new IllegalArgumentException("AI cache identity workspace must be positive");
            }
            Objects.requireNonNull(feature, "feature");
            List<Integer> sorted = new ArrayList<>(Objects.requireNonNull(subjectIds, "subjectIds"));
            if (sorted.isEmpty() || sorted.size() > 2
                    || sorted.stream().anyMatch(subjectId -> subjectId == null || subjectId <= 0)) {
                throw new IllegalArgumentException("AI cache identity subjects must contain one or two positive ids");
            }
            sorted.sort(Integer::compareTo);
            subjectIds = List.copyOf(sorted);
            locale = normalizeLanguage(locale);
        }

        /**
         * Creates a single-subject cache identity.
         * @param workspaceId active workspace
         * @param feature AI feature
         * @param subjectId persistent subject id
         * @param locale response locale
         * @return normalized cache identity
         */
        public static CacheIdentity forSubject(
                int workspaceId,
                AiFeature feature,
                int subjectId,
                Locale locale) {
            return new CacheIdentity(workspaceId, feature, List.of(subjectId), language(locale));
        }

        /**
         * Creates a two-subject cache identity with the ids sorted in ascending order.
         * @param workspaceId active workspace
         * @param feature AI feature
         * @param firstId first persistent subject id
         * @param secondId second persistent subject id
         * @param locale response locale
         * @return normalized cache identity
         */
        public static CacheIdentity forPair(
                int workspaceId,
                AiFeature feature,
                int firstId,
                int secondId,
                Locale locale) {
            return new CacheIdentity(workspaceId, feature, List.of(firstId, secondId), language(locale));
        }

        private static String language(Locale locale) {
            return locale == null ? Locale.ENGLISH.getLanguage() : locale.getLanguage();
        }

        private static String normalizeLanguage(String language) {
            if (language == null || language.isBlank()) {
                return Locale.ENGLISH.getLanguage();
            }
            Locale locale = Locale.forLanguageTag(language.replace('_', '-'));
            String normalized = locale.getLanguage().toLowerCase(Locale.ROOT);
            return normalized.isBlank() ? Locale.ENGLISH.getLanguage() : normalized;
        }
    }

    /** Admission role assigned to a cache-miss caller. */
    public enum Decision {
        LEADER,
        FOLLOWER,
        RATE_LIMITED
    }

    /** Stable reason for rejecting a new leader. */
    public enum Rejection {
        NONE,
        ORGANIZATION_QUOTA,
        REFRESH_THROTTLE,
        CAPACITY
    }

    /** Terminal result published by a flight leader to its followers. */
    public enum LeaderOutcome {
        CACHE_READY,
        FAILED
    }

    private record FlightIdentity(CacheIdentity cacheIdentity, String contentHash) {
        private FlightIdentity {
            Objects.requireNonNull(cacheIdentity, "cacheIdentity");
            Objects.requireNonNull(contentHash, "contentHash");
        }
    }

    /**
     * Lifecycle handle for an invocation admission. Leaders must publish an outcome; closing an
     * incomplete leader publishes failure so followers cannot remain blocked.
     */
    public static final class Admission implements AutoCloseable {
        private final AiInvocationAdmissionService owner;
        private final FlightIdentity identity;
        private final FlightState flight;
        private final Decision decision;
        private final Rejection rejection;
        private final Object lifecycleLock = new Object();
        private boolean leaderCompleted;
        private boolean closed;

        private Admission(
                AiInvocationAdmissionService owner,
                FlightIdentity identity,
                FlightState flight,
                Decision decision,
                Rejection rejection) {
            this.owner = owner;
            this.identity = identity;
            this.flight = flight;
            this.decision = decision;
            this.rejection = rejection;
        }

        private static Admission leader(
                AiInvocationAdmissionService owner,
                FlightIdentity identity,
                FlightState flight) {
            return new Admission(owner, identity, flight, Decision.LEADER, Rejection.NONE);
        }

        private static Admission follower(
                AiInvocationAdmissionService owner,
                FlightIdentity identity,
                FlightState flight) {
            return new Admission(owner, identity, flight, Decision.FOLLOWER, Rejection.NONE);
        }

        private static Admission rejected(Rejection rejection) {
            return new Admission(null, null, null, Decision.RATE_LIMITED, rejection);
        }

        /**
         * Returns the caller's admission role.
         * @return leader, follower, or rejected decision
         */
        public Decision decision() {
            return decision;
        }

        /**
         * Returns the rejection reason for a rate-limited admission.
         * @return stable rejection reason or {@link Rejection#NONE}
         */
        public Rejection rejection() {
            return rejection;
        }

        /**
         * Blocks a follower until its registered leader publishes a terminal outcome.
         * @return leader outcome
         */
        public LeaderOutcome awaitLeader() {
            if (decision != Decision.FOLLOWER || owner == null || identity == null || flight == null) {
                throw new IllegalStateException("Only an AI invocation follower can await a leader");
            }
            try {
                return flight.completion.copy()
                        .orTimeout(owner.followerWait.toMillis(), TimeUnit.MILLISECONDS)
                        .join();
            } catch (CompletionException exception) {
                owner.complete(identity, flight, LeaderOutcome.FAILED);
                return LeaderOutcome.FAILED;
            }
        }

        void commitLeaderInvocation() {
            synchronized (lifecycleLock) {
                if (decision != Decision.LEADER || owner == null || closed || leaderCompleted) {
                    throw new IllegalStateException("Only an active AI invocation leader can commit quota");
                }
                owner.commitInvocation(identity, flight);
            }
        }

        /**
         * Atomically publishes a leader outcome and removes its flight registration.
         * @param outcome terminal flight outcome
         */
        public void completeLeader(LeaderOutcome outcome) {
            if (decision != Decision.LEADER || owner == null || identity == null || flight == null) {
                throw new IllegalStateException("Only an AI invocation leader can complete a flight");
            }
            LeaderOutcome completedOutcome = Objects.requireNonNull(outcome, "outcome");
            synchronized (lifecycleLock) {
                if (leaderCompleted) {
                    return;
                }
                leaderCompleted = true;
                owner.complete(identity, flight, completedOutcome);
            }
        }

        @Override
        public void close() {
            synchronized (lifecycleLock) {
                if (closed) {
                    return;
                }
                closed = true;
                if (decision == Decision.LEADER && !leaderCompleted
                        && owner != null && identity != null && flight != null) {
                    leaderCompleted = true;
                    owner.complete(identity, flight, LeaderOutcome.FAILED);
                }
            }
        }
    }

    /** Lifecycle handle for one non-cacheable organization-quota reservation. */
    public static final class DirectAdmission implements AutoCloseable {
        private final AiInvocationAdmissionService owner;
        private final int orgId;
        private final Object lifecycleLock = new Object();
        private boolean committed;
        private boolean closed;

        private DirectAdmission(AiInvocationAdmissionService owner, int orgId) {
            this.owner = Objects.requireNonNull(owner, "owner");
            this.orgId = orgId;
        }

        void commitInvocation() {
            synchronized (lifecycleLock) {
                if (closed) {
                    throw new IllegalStateException("AI direct invocation admission is closed");
                }
                if (!committed) {
                    owner.commitDirect(orgId);
                    committed = true;
                }
            }
        }

        @Override
        public void close() {
            synchronized (lifecycleLock) {
                if (closed) {
                    return;
                }
                closed = true;
                if (!committed) {
                    owner.releaseDirect(orgId);
                }
            }
        }
    }

    /** Distinguishes direct-attempt organization quota from bounded registry capacity. */
    public static final class DirectAdmissionRejectedException extends TooManyRequestsException {
        private final Rejection rejection;

        public DirectAdmissionRejectedException(Rejection rejection) {
            super("AI direct invocation admission was rejected");
            this.rejection = Objects.requireNonNull(rejection, "rejection");
        }

        /** @return stable direct-admission rejection reason */
        public Rejection rejection() {
            return rejection;
        }
    }

    private static final class QuotaState {
        private final ArrayDeque<Instant> attempts = new ArrayDeque<>();
        private int reservations;

        private int size() {
            return attempts.size() + reservations;
        }

        private void reserve() {
            reservations++;
        }

        private void commit(Instant now) {
            if (reservations <= 0) {
                throw new IllegalStateException("AI invocation quota reservation is unavailable");
            }
            reservations--;
            attempts.addLast(now);
        }

        private void release() {
            if (reservations > 0) {
                reservations--;
            }
        }

        private void purge(Instant cutoff) {
            while (!attempts.isEmpty() && !attempts.getFirst().isAfter(cutoff)) {
                attempts.removeFirst();
            }
        }

        private boolean isEmpty() {
            return attempts.isEmpty() && reservations == 0;
        }
    }

    private static final class FlightState {
        private final int orgId;
        private final CompletableFuture<LeaderOutcome> completion = new CompletableFuture<>();
        private boolean invocationCommitted;

        private FlightState(int orgId) {
            this.orgId = orgId;
        }
    }
}
