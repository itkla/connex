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

import org.springframework.stereotype.Component;

import ooo.klae.connex.backend.services.WorkspaceService;

/**
 * Coordinates cache-miss AI invocations with organization quotas, forced-refresh throttling, and
 * per-cache-identity single-flight execution. All quotas and flights are local to one JVM replica;
 * cluster-wide enforcement requires a shared coordinator.
 */
@Component
public class AiInvocationAdmissionService {
    private final WorkspaceService workspaceService;
    private final Clock clock;
    private final int quotaAttemptsPerOrg;
    private final Duration quotaWindow;
    private final Duration refreshThrottle;
    private final int quotaMaxOrganizations;
    private final int refreshMaxIdentities;
    private final int maxActiveFlights;
    private final Object stateLock = new Object();
    private final Map<Integer, QuotaState> quotaWindows =
            new LinkedHashMap<>(16, 0.75f, true);
    private final Map<CacheIdentity, Instant> refreshTimestamps =
            new LinkedHashMap<>(16, 0.75f, true);
    private final Map<CacheIdentity, FlightState> activeFlights = new HashMap<>();

    public AiInvocationAdmissionService(
            AiProperties properties,
            WorkspaceService workspaceService,
            Clock clock) {
        AiProperties configured = Objects.requireNonNull(properties, "properties");
        this.workspaceService = Objects.requireNonNull(workspaceService, "workspaceService");
        this.clock = Objects.requireNonNull(clock, "clock");
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
     * @param refresh whether the caller is forcing a cache refresh
     * @return admission decision and lifecycle handle
     */
    public Admission acquire(CacheIdentity identity, boolean refresh) {
        CacheIdentity key = Objects.requireNonNull(identity, "identity");
        int orgId = workspaceService.getCurrentOrgId();
        if (orgId <= 0) {
            throw new IllegalStateException("AI invocation organization is unavailable");
        }
        Instant now = clock.instant();
        synchronized (stateLock) {
            purgeStale(now);
            FlightState current = activeFlights.get(key);
            if (current != null) {
                return Admission.follower(this, key, current);
            }
            Rejection capacity = capacityRejection(key, orgId, refresh);
            if (capacity != Rejection.NONE) {
                return Admission.rejected(capacity);
            }
            if (refresh && refreshIsThrottled(key, now)) {
                return Admission.rejected(Rejection.REFRESH_THROTTLE);
            }
            QuotaState quota = quotaWindows.get(orgId);
            if (quota != null && quota.size() >= quotaAttemptsPerOrg) {
                return Admission.rejected(Rejection.ORGANIZATION_QUOTA);
            }
            if (quota == null) {
                quota = new QuotaState();
                quotaWindows.put(orgId, quota);
            }
            quota.reserve();
            if (refresh) {
                refreshTimestamps.put(key, now);
            }
            FlightState flight = new FlightState();
            activeFlights.put(key, flight);
            return Admission.leader(this, key, flight, orgId);
        }
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

    private void commitInvocation(int orgId) {
        synchronized (stateLock) {
            QuotaState quota = quotaWindows.get(orgId);
            if (quota == null) {
                throw new IllegalStateException("AI invocation quota reservation is unavailable");
            }
            quota.commit(clock.instant());
        }
    }

    private void complete(
            CacheIdentity identity,
            FlightState flight,
            LeaderOutcome outcome,
            int orgId,
            boolean invocationCommitted) {
        synchronized (stateLock) {
            if (activeFlights.get(identity) != flight) {
                return;
            }
            if (!invocationCommitted) {
                QuotaState quota = quotaWindows.get(orgId);
                if (quota != null) {
                    quota.release();
                    if (quota.isEmpty()) {
                        quotaWindows.remove(orgId);
                    }
                }
            }
            flight.completion.complete(outcome);
            activeFlights.remove(identity);
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

    /** Persistent identity used for single-flight and forced-refresh admission. */
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

    /**
     * Lifecycle handle for an invocation admission. Leaders must publish an outcome; closing an
     * incomplete leader publishes failure so followers cannot remain blocked.
     */
    public static final class Admission implements AutoCloseable {
        private final AiInvocationAdmissionService owner;
        private final CacheIdentity identity;
        private final FlightState flight;
        private final int orgId;
        private final Decision decision;
        private final Rejection rejection;
        private final Object lifecycleLock = new Object();
        private boolean invocationCommitted;
        private boolean leaderCompleted;
        private boolean closed;

        private Admission(
                AiInvocationAdmissionService owner,
                CacheIdentity identity,
                FlightState flight,
                int orgId,
                Decision decision,
                Rejection rejection) {
            this.owner = owner;
            this.identity = identity;
            this.flight = flight;
            this.orgId = orgId;
            this.decision = decision;
            this.rejection = rejection;
        }

        private static Admission leader(
                AiInvocationAdmissionService owner,
                CacheIdentity identity,
                FlightState flight,
                int orgId) {
            return new Admission(owner, identity, flight, orgId, Decision.LEADER, Rejection.NONE);
        }

        private static Admission follower(
                AiInvocationAdmissionService owner,
                CacheIdentity identity,
                FlightState flight) {
            return new Admission(owner, identity, flight, 0, Decision.FOLLOWER, Rejection.NONE);
        }

        private static Admission rejected(Rejection rejection) {
            return new Admission(null, null, null, 0, Decision.RATE_LIMITED, rejection);
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
            if (decision != Decision.FOLLOWER || flight == null) {
                throw new IllegalStateException("Only an AI invocation follower can await a leader");
            }
            return flight.completion.join();
        }

        void commitLeaderInvocation() {
            synchronized (lifecycleLock) {
                if (decision != Decision.LEADER || owner == null || closed || leaderCompleted) {
                    throw new IllegalStateException("Only an active AI invocation leader can commit quota");
                }
                if (invocationCommitted) {
                    return;
                }
                owner.commitInvocation(orgId);
                invocationCommitted = true;
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
                owner.complete(identity, flight, completedOutcome, orgId, invocationCommitted);
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
                    owner.complete(
                            identity, flight, LeaderOutcome.FAILED, orgId, invocationCommitted);
                }
            }
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
        private final CompletableFuture<LeaderOutcome> completion = new CompletableFuture<>();
    }
}
