package ooo.klae.connex.backend.ai;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.AiBudgetControlOperations.Reservation;
import ooo.klae.connex.backend.ai.egress.AiRequestDeadline;
import ooo.klae.connex.backend.ai.provider.AiProviderCallerDeadlineExceededException;

/** Cluster-coordinated organization daily token-budget reservation lifecycle. */
@Service
@RequiredArgsConstructor
public class AiOrganizationBudgetCoordinator {
    private static final Duration MAX_PROVIDER_DURATION = Duration.ofHours(1);
    private static final Duration SETTLEMENT_MARGIN = Duration.ofMinutes(1);
    private static final Duration SETTLEMENT_RETENTION = Duration.ofDays(7);
    private static final int MAX_SWEEP_BATCHES = 100;

    private final AiBudgetControlOperations operations;
    private final AiBudgetControlAccess controlAccess;
    private final Clock clock;
    private final AiProperties aiProperties;

    /** Reserves a conservative token ceiling before a provider call. */
    public Lease reserve(int orgId, AiInvocation invocation, String serializedPrompt) {
        return reserve(orgId, invocation, serializedPrompt, deadline(invocation));
    }

    /** Reserves fallback work within the original invocation's absolute provider deadline. */
    public Lease reserve(
            int orgId, AiInvocation invocation, String serializedPrompt, AiRequestDeadline deadline) {
        long remainingNanos = deadline.remainingNanos();
        if (remainingNanos <= 0) {
            throw new AiProviderCallerDeadlineExceededException();
        }
        if (remainingNanos > MAX_PROVIDER_DURATION.toNanos()) {
            throw new IllegalArgumentException("AI provider deadline exceeds the maximum duration");
        }
        long reservedTokens = estimatedTokenCeiling(invocation, serializedPrompt);
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        LocalDate usageDay = now.toLocalDate();
        Reservation reservation = controlAccess.execute(() -> operations.reserve(
                orgId,
                usageDay,
                reservedTokens,
                UUID.randomUUID().toString(),
                now,
                now.plusNanos(remainingNanos).plus(SETTLEMENT_MARGIN)));
        return new Lease(this, reservation, deadline);
    }

    /** Reserves a conservative ceiling for callers without an enriched serialized envelope. */
    public Lease reserve(int orgId, AiInvocation invocation) {
        return reserve(orgId, invocation, invocation.prompt().getSystemPrompt());
    }

    /** Recovers expired reservations in separate transactions, charging dispatched work durably. */
    @Scheduled(fixedDelay = 1, initialDelay = 1, timeUnit = TimeUnit.MINUTES)
    public void sweepExpiredReservations() {
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        for (int batch = 0; batch < MAX_SWEEP_BATCHES; batch++) {
            List<String> expiredIds = controlAccess.execute(() -> operations.expiredReservationIds(now));
            if (expiredIds.isEmpty()) break;
            for (String reservationId : expiredIds) {
                controlAccess.execute(() -> {
                    operations.expireReservation(reservationId, now);
                    return null;
                });
            }
        }
        LocalDateTime cutoff = now.minus(SETTLEMENT_RETENTION);
        for (int batch = 0; batch < MAX_SWEEP_BATCHES; batch++) {
            int purged = controlAccess.execute(() -> operations.purgeSettledReservations(cutoff));
            if (purged == 0) break;
        }
    }

    private AiRequestDeadline deadline(AiInvocation invocation) {
        long timeoutMillis = aiProperties.getRequestTimeoutMs();
        if (timeoutMillis <= 0) {
            throw new IllegalStateException("AI request timeout must be positive");
        }
        Duration remaining = Duration.ofMillis(Math.min(timeoutMillis, MAX_PROVIDER_DURATION.toMillis()));
        Instant callerDeadline = invocation.callerDeadline();
        if (callerDeadline != null) {
            Duration callerRemaining = Duration.between(clock.instant(), callerDeadline);
            if (callerRemaining.compareTo(remaining) < 0) remaining = callerRemaining;
        }
        if (remaining.isNegative() || remaining.isZero()) {
            throw new AiProviderCallerDeadlineExceededException();
        }
        return AiRequestDeadline.afterNanos(remaining.toNanos());
    }

    private void markDispatched(Reservation reservation) {
        if (!reservation.metered()) return;
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        controlAccess.execute(() -> {
            operations.markDispatched(reservation.id(), now);
            return null;
        });
    }

    private void settle(Reservation reservation, long consumedTokens) {
        if (!reservation.metered()) return;
        controlAccess.execute(() -> {
            operations.settle(reservation.id(), consumedTokens);
            return null;
        });
    }

    private void release(Reservation reservation) {
        if (!reservation.metered()) return;
        controlAccess.execute(() -> {
            operations.release(reservation.id());
            return null;
        });
    }

    static long estimatedTokenCeiling(AiInvocation invocation) {
        long estimate = invocation.maxTokens();
        estimate = saturatedAdd(
                estimate,
                invocation.prompt().getSystemPrompt().getBytes(StandardCharsets.UTF_8).length);
        for (var message : invocation.prompt().getMessages()) {
            estimate = saturatedAdd(
                    estimate,
                    message.getContent().getBytes(StandardCharsets.UTF_8).length);
        }
        for (var image : invocation.images()) {
            estimate = saturatedAdd(estimate, image.size());
        }
        return Math.max(1, estimate);
    }

    static long estimatedTokenCeiling(AiInvocation invocation, String serializedPrompt) {
        long estimate = invocation.maxTokens();
        estimate = saturatedAdd(
                estimate,
                serializedPrompt.getBytes(StandardCharsets.UTF_8).length);
        for (var image : invocation.images()) {
            estimate = saturatedAdd(estimate, image.size());
        }
        return Math.max(1, estimate);
    }

    private static long saturatedAdd(long first, long second) {
        return second > Long.MAX_VALUE - first ? Long.MAX_VALUE : first + second;
    }

    /** Exactly-once settlement or release for one provider-call reservation. */
    public static final class Lease implements AutoCloseable {
        private final AiOrganizationBudgetCoordinator coordinator;
        private final Reservation reservation;
        private final AiRequestDeadline deadline;
        private Long settlementTokens;
        private boolean dispatched;
        private boolean closed;

        private Lease(
                AiOrganizationBudgetCoordinator coordinator, Reservation reservation, AiRequestDeadline deadline) {
            this.coordinator = coordinator;
            this.reservation = reservation;
            this.deadline = deadline;
        }

        /** Returns the absolute deadline shared by the lease and all provider attempts. */
        public AiRequestDeadline deadline() {
            return deadline;
        }

        /** Marks the final handoff to provider transport after pre-dispatch checks pass. */
        public synchronized void markDispatched() {
            if (closed) {
                throw new IllegalStateException("Provider dispatch requires an active budget reservation");
            }
            if (!dispatched) {
                coordinator.markDispatched(reservation);
                dispatched = true;
            }
        }

        /** Replaces the reservation with actual input and output token usage. */
        public synchronized void settle(int inputTokens, int outputTokens) {
            if (closed) return;
            long reportedTokens = saturatedAdd(
                    Math.max(0, inputTokens), Math.max(0, outputTokens));
            settlementTokens = reportedTokens == 0 && reservation.metered()
                    ? reservation.reservedTokens()
                    : reportedTokens;
            coordinator.settle(reservation, settlementTokens);
            closed = true;
        }

        /** Charges interrupted dispatches conservatively; only pre-dispatch failures are released. */
        @Override
        public synchronized void close() {
            if (closed) return;
            if (settlementTokens == null && dispatched) {
                settlementTokens = reservation.reservedTokens();
            }
            if (settlementTokens == null) {
                coordinator.release(reservation);
            } else {
                coordinator.settle(reservation, settlementTokens);
            }
            closed = true;
        }
    }
}
