package ooo.klae.connex.backend.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.ai.masking.MaskingContext;
import ooo.klae.connex.backend.ai.masking.PromptAssembly;

class AiOrganizationBudgetCoordinatorTest {
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
                Clock.fixed(Instant.parse("2026-08-10T00:00:00Z"), ZoneOffset.UTC));
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
                Clock.fixed(Instant.parse("2026-08-10T00:00:00Z"), ZoneOffset.UTC));
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
}
