package ooo.klae.connex.backend.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import ooo.klae.connex.backend.ai.masking.MaskingContext;
import ooo.klae.connex.backend.ai.masking.PromptAssembly;
import ooo.klae.connex.backend.ai.provider.AiProviderCallerDeadlineExceededException;

class AiOrganizationBudgetCoordinatorTest {
    @Test
    void interruptedDispatchChargesTheReservedCeilingExactlyOnce() {
        AiBudgetControlOperations operations = mock(AiBudgetControlOperations.class);
        AiOrganizationBudgetCoordinator coordinator = meteredCoordinator(operations);
        AiOrganizationBudgetCoordinator.Lease lease = coordinator.reserve(3, invocation());

        lease.markDispatched();
        lease.close();
        lease.close();
        lease.settle(7, 5);

        verify(operations).markDispatched("reservation", LocalDateTime.of(2026, 8, 10, 0, 0));
        verify(operations).settle("reservation", 100);
        verify(operations, never()).release(anyString());
    }

    @Test
    void interruptedDispatchRetriesFailedConservativeSettlement() {
        AiBudgetControlOperations operations = mock(AiBudgetControlOperations.class);
        AiOrganizationBudgetCoordinator coordinator = meteredCoordinator(operations);
        AiOrganizationBudgetCoordinator.Lease lease = coordinator.reserve(3, invocation());
        doThrow(new IllegalStateException("temporary database failure"))
                .doNothing().when(operations).settle("reservation", 100);

        lease.markDispatched();
        assertThrows(IllegalStateException.class, lease::close);
        lease.close();

        verify(operations, times(2)).settle("reservation", 100);
        verify(operations, never()).release(anyString());
    }

    @Test
    void failureBeforeDispatchReleasesTheReservationAndPreventsLaterDispatch() {
        AiBudgetControlOperations operations = mock(AiBudgetControlOperations.class);
        AiOrganizationBudgetCoordinator coordinator = meteredCoordinator(operations);
        AiOrganizationBudgetCoordinator.Lease lease = coordinator.reserve(3, invocation());

        lease.close();

        assertThrows(IllegalStateException.class, lease::markDispatched);
        verify(operations).release("reservation");
        verify(operations, never()).settle(anyString(), anyLong());
    }

    @Test
    void scheduledSweepUsesTheControlPlaneAndUtcClock() {
        AiBudgetControlOperations operations = mock(AiBudgetControlOperations.class);
        AiOrganizationBudgetCoordinator coordinator = meteredCoordinator(operations);

        when(operations.expiredReservationIds(LocalDateTime.of(2026, 8, 10, 0, 0)))
                .thenReturn(List.of("reservation"), List.of());

        coordinator.sweepExpiredReservations();

        verify(operations).expireReservation("reservation", LocalDateTime.of(2026, 8, 10, 0, 0));
        verify(operations).purgeSettledReservations(LocalDateTime.of(2026, 8, 3, 0, 0));
    }

    @Test
    void missingProviderUsageSettlesAtReservedCeiling() {
        AiBudgetControlOperations operations = mock(AiBudgetControlOperations.class);
        AiBudgetControlAccess controlAccess = mock(AiBudgetControlAccess.class);
        doAnswer(invocation -> {
            Supplier<?> work = invocation.getArgument(0);
            return work.get();
        }).when(controlAccess).execute(any());
        when(operations.reserve(
                eq(3),
                any(LocalDate.class),
                anyLong(),
                anyString(),
                any(LocalDateTime.class),
                any(LocalDateTime.class)))
                .thenReturn(new AiBudgetControlOperations.Reservation(
                        "reservation", 3, LocalDate.of(2026, 8, 10), 100, true));
        AiOrganizationBudgetCoordinator coordinator = new AiOrganizationBudgetCoordinator(
                operations,
                controlAccess,
                Clock.fixed(Instant.parse("2026-08-10T00:00:00Z"), ZoneOffset.UTC), new AiProperties());
        AiInvocation invocation = new AiInvocation(
                AiFeature.ASSISTANT_CHAT,
                new MaskingContext(),
                PromptAssembly.builder().system("system").userTurn("user").build(),
                64,
                0.1);

        AiOrganizationBudgetCoordinator.Lease lease = coordinator.reserve(3, invocation);
        lease.settle(0, 0);

        verify(operations).settle("reservation", 100);
    }

    @Test
    void reservationCeilingUsesUtf8BytesForMultilingualPrompts() {
        String system = "簡潔に回答";
        String user = "関係性を要約";
        AiInvocation invocation = new AiInvocation(
                AiFeature.ASSISTANT_CHAT,
                new MaskingContext(),
                PromptAssembly.builder().system(system).userTurn(user).build(),
                64,
                0.1);

        assertEquals(
                64L
                        + system.getBytes(StandardCharsets.UTF_8).length
                        + user.getBytes(StandardCharsets.UTF_8).length,
                AiOrganizationBudgetCoordinator.estimatedTokenCeiling(invocation));
    }

    @Test
    void failedSettlementIsRetriedByCloseInsteadOfReleased() {
        AiBudgetControlOperations operations = mock(AiBudgetControlOperations.class);
        AiBudgetControlAccess controlAccess = mock(AiBudgetControlAccess.class);
        doAnswer(invocation -> {
            Supplier<?> work = invocation.getArgument(0);
            return work.get();
        }).when(controlAccess).execute(any());
        when(operations.reserve(
                eq(3),
                any(LocalDate.class),
                anyLong(),
                anyString(),
                any(LocalDateTime.class),
                any(LocalDateTime.class)))
                .thenReturn(new AiBudgetControlOperations.Reservation(
                        "reservation", 3, LocalDate.of(2026, 8, 10), 100, true));
        doThrow(new IllegalStateException("temporary database failure"))
                .doNothing()
                .when(operations).settle("reservation", 12);
        AiOrganizationBudgetCoordinator coordinator = new AiOrganizationBudgetCoordinator(
                operations,
                controlAccess,
                Clock.fixed(Instant.parse("2026-08-10T00:00:00Z"), ZoneOffset.UTC), new AiProperties());
        AiInvocation invocation = new AiInvocation(
                AiFeature.ASSISTANT_CHAT,
                new MaskingContext(),
                PromptAssembly.builder().system("system").userTurn("user").build(),
                64,
                0.1);
        AiOrganizationBudgetCoordinator.Lease lease = coordinator.reserve(3, invocation);

        assertThrows(IllegalStateException.class, () -> lease.settle(7, 5));
        lease.close();

        verify(operations, times(2)).settle("reservation", 12);
        verify(operations, times(0)).release("reservation");
    }

    @Test
    void sweepDrainsMultipleExpiryAndRetentionBatches() {
        AiBudgetControlOperations operations = mock(AiBudgetControlOperations.class);
        AiOrganizationBudgetCoordinator coordinator = meteredCoordinator(operations);
        LocalDateTime now = LocalDateTime.of(2026, 8, 10, 0, 0);
        List<String> first = IntStream.range(0, 100).mapToObj(i -> "first-" + i).toList();
        when(operations.expiredReservationIds(now)).thenReturn(first, List.of("last"), List.of());
        when(operations.purgeSettledReservations(now.minusDays(7))).thenReturn(100, 5, 0);

        coordinator.sweepExpiredReservations();

        verify(operations, times(3)).expiredReservationIds(now);
        verify(operations, times(101)).expireReservation(anyString(), eq(now));
        verify(operations).expireReservation("last", now);
        verify(operations, times(3)).purgeSettledReservations(now.minusDays(7));
    }

    @Test
    void sweepCapsExpiryAndRetentionWorkEvenWhenOtherReplicasKeepReturningTheSameRows() {
        AiBudgetControlOperations operations = mock(AiBudgetControlOperations.class);
        AiOrganizationBudgetCoordinator coordinator = meteredCoordinator(operations);
        LocalDateTime now = LocalDateTime.of(2026, 8, 10, 0, 0);
        List<String> batch = IntStream.range(0, 100).mapToObj(i -> "reservation-" + i).toList();
        when(operations.expiredReservationIds(now)).thenReturn(batch);
        when(operations.purgeSettledReservations(now.minusDays(7))).thenReturn(100);

        coordinator.sweepExpiredReservations();

        verify(operations, times(100)).expiredReservationIds(now);
        verify(operations, times(10_000)).expireReservation(anyString(), eq(now));
        verify(operations, times(100)).purgeSettledReservations(now.minusDays(7));
    }

    @Test
    void leaseOutlivesTwentyMinuteProviderDeadlineAndFallbackSharesTheAbsoluteDeadline() {
        AiBudgetControlOperations operations = mock(AiBudgetControlOperations.class);
        AiProperties properties = new AiProperties();
        properties.setRequestTimeoutMs(Duration.ofMinutes(20).toMillis());
        AiOrganizationBudgetCoordinator coordinator = meteredCoordinator(operations, properties);

        AiOrganizationBudgetCoordinator.Lease lease = coordinator.reserve(3, invocation());
        AiOrganizationBudgetCoordinator.Lease fallback = coordinator.reserve(
                3, invocation(), "system", lease.deadline());

        assertSame(lease.deadline(), fallback.deadline());
        ArgumentCaptor<LocalDateTime> expiry = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(operations, times(2)).reserve(eq(3), any(), anyLong(), anyString(), any(), expiry.capture());
        LocalDateTime now = LocalDateTime.of(2026, 8, 10, 0, 0);
        for (LocalDateTime value : expiry.getAllValues()) {
            assertTrue(value.isAfter(now.plusMinutes(20)));
            assertTrue(!value.isAfter(now.plusMinutes(21)));
        }
    }

    @Test
    void leaseUsesCallerDeadlineAndBoundsOversizedProviderConfiguration() {
        AiBudgetControlOperations operations = mock(AiBudgetControlOperations.class);
        AiProperties properties = new AiProperties();
        properties.setRequestTimeoutMs(Long.MAX_VALUE);
        AiOrganizationBudgetCoordinator coordinator = meteredCoordinator(operations, properties);
        AiInvocation original = invocation();
        AiInvocation bounded = new AiInvocation(original.feature(), original.context(), original.prompt(),
                original.maxTokens(), original.temperature(), false, Instant.parse("2026-08-10T00:02:00Z"));

        AiOrganizationBudgetCoordinator.Lease callerLease = coordinator.reserve(3, bounded);
        AiOrganizationBudgetCoordinator.Lease cappedLease = coordinator.reserve(3, original);

        assertTrue(callerLease.deadline().remainingNanos() <= Duration.ofMinutes(2).toNanos());
        assertTrue(cappedLease.deadline().remainingNanos() <= Duration.ofHours(1).toNanos());
        ArgumentCaptor<LocalDateTime> expiry = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(operations, times(2)).reserve(eq(3), any(), anyLong(), anyString(), any(), expiry.capture());
        LocalDateTime now = LocalDateTime.of(2026, 8, 10, 0, 0);
        assertTrue(expiry.getAllValues().get(0).isAfter(now.plusMinutes(2)));
        assertTrue(!expiry.getAllValues().get(0).isAfter(now.plusMinutes(3)));
        assertTrue(expiry.getAllValues().get(1).isAfter(now.plusHours(1)));
        assertTrue(!expiry.getAllValues().get(1).isAfter(now.plusMinutes(61)));
    }

    @Test
    void expiredCallerDeadlineCannotReserveCapacity() {
        AiBudgetControlOperations operations = mock(AiBudgetControlOperations.class);
        AiOrganizationBudgetCoordinator coordinator = meteredCoordinator(operations);
        AiInvocation original = invocation();
        AiInvocation expired = new AiInvocation(original.feature(), original.context(), original.prompt(),
                original.maxTokens(), original.temperature(), false, Instant.parse("2026-08-09T23:59:59Z"));

        assertThrows(AiProviderCallerDeadlineExceededException.class, () -> coordinator.reserve(3, expired));
        verify(operations, never()).reserve(anyInt(), any(), anyLong(), anyString(), any(), any());
    }

    private static AiOrganizationBudgetCoordinator meteredCoordinator(
            AiBudgetControlOperations operations) {
        return meteredCoordinator(operations, new AiProperties());
    }

    private static AiOrganizationBudgetCoordinator meteredCoordinator(
            AiBudgetControlOperations operations, AiProperties properties) {
        AiBudgetControlAccess controlAccess = mock(AiBudgetControlAccess.class);
        doAnswer(call -> {
            Supplier<?> work = call.getArgument(0);
            return work.get();
        }).when(controlAccess).execute(any());
        when(operations.reserve(
                eq(3), any(LocalDate.class), anyLong(), anyString(),
                any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(new AiBudgetControlOperations.Reservation(
                        "reservation", 3, LocalDate.of(2026, 8, 10), 100, true));
        return new AiOrganizationBudgetCoordinator(
                operations, controlAccess,
                Clock.fixed(Instant.parse("2026-08-10T00:00:00Z"), ZoneOffset.UTC), properties);
    }

    private static AiInvocation invocation() {
        return new AiInvocation(
                AiFeature.ASSISTANT_CHAT, new MaskingContext(),
                PromptAssembly.builder().system("system").userTurn("user").build(), 64, 0.1);
    }
}
