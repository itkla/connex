package ooo.klae.connex.backend.ai;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

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
    public Lease reserve(int orgId, AiInvocation invocation) {
        long reservedTokens = estimatedTokenCeiling(invocation);
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

    private static long saturatedAdd(long first, long second) {
        return second > Long.MAX_VALUE - first ? Long.MAX_VALUE : first + second;
    }

    /** Exactly-once settlement or release for one provider-call reservation. */
    public static final class Lease implements AutoCloseable {
        private final AiOrganizationBudgetCoordinator coordinator;
        private final Reservation reservation;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Lease(AiOrganizationBudgetCoordinator coordinator, Reservation reservation) {
            this.coordinator = coordinator;
            this.reservation = reservation;
        }

        /** Replaces the reservation with actual input and output token usage. */
        public void settle(int inputTokens, int outputTokens) {
            if (closed.compareAndSet(false, true)) {
                coordinator.settle(
                        reservation,
                        saturatedAdd(Math.max(0, inputTokens), Math.max(0, outputTokens)));
            }
        }

        /** Releases a reservation when no provider usage was returned. */
        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                coordinator.release(reservation);
            }
        }
    }
}
