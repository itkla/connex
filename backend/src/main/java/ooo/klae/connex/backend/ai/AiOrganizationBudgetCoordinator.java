package ooo.klae.connex.backend.ai;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
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

/** Cluster-coordinated organization daily token-budget reservation lifecycle. */
@Service
@RequiredArgsConstructor
public class AiOrganizationBudgetCoordinator {
    private static final Duration RESERVATION_TTL = Duration.ofMinutes(10);

    private final AiBudgetControlOperations operations;
    private final AiBudgetControlAccess controlAccess;
    private final Clock clock;

    /** Reserves a conservative token ceiling before a provider call. */
    public Lease reserve(int orgId, AiInvocation invocation, String serializedPrompt) {
        long reservedTokens = estimatedTokenCeiling(invocation, serializedPrompt);
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        LocalDate usageDay = now.toLocalDate();
        Reservation reservation = controlAccess.execute(() -> operations.reserve(
                orgId,
                usageDay,
                reservedTokens,
                UUID.randomUUID().toString(),
                now,
                now.plus(RESERVATION_TTL)));
        return new Lease(this, reservation);
    }

    /** Reserves a conservative ceiling for callers without an enriched serialized envelope. */
    public Lease reserve(int orgId, AiInvocation invocation) {
        return reserve(orgId, invocation, invocation.prompt().getSystemPrompt());
    }

    /** Recovers expired reservations in separate transactions, charging dispatched work durably. */
    @Scheduled(fixedDelay = 1, initialDelay = 1, timeUnit = TimeUnit.MINUTES)
    public void sweepExpiredReservations() {
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        List<String> expiredIds = controlAccess.execute(() -> operations.expiredReservationIds(now));
        for (String reservationId : expiredIds) {
            controlAccess.execute(() -> {
                operations.expireReservation(reservationId, now);
                return null;
            });
        }
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
        private Long settlementTokens;
        private boolean dispatched;
        private boolean closed;

        private Lease(AiOrganizationBudgetCoordinator coordinator, Reservation reservation) {
            this.coordinator = coordinator;
            this.reservation = reservation;
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
