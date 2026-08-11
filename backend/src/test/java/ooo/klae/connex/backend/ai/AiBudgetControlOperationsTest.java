package ooo.klae.connex.backend.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.beans.AiOrganizationBudget;
import ooo.klae.connex.backend.beans.AiOrganizationBudgetReservation;
import ooo.klae.connex.backend.beans.AiOrganizationBudgetUsage;
import ooo.klae.connex.backend.exceptions.AiBudgetExhaustedException;
import ooo.klae.connex.backend.mappers.AiOrganizationBudgetMapper;

class AiBudgetControlOperationsTest {
    private static final LocalDate DAY = LocalDate.of(2026, 8, 10);
    private static final LocalDateTime NOW = DAY.atStartOfDay();

    private final AiOrganizationBudgetMapper mapper = mock(AiOrganizationBudgetMapper.class);
    private final AiBudgetControlOperations operations = new AiBudgetControlOperations(mapper);

    @Test
    void databaseLedgerRefusesAReservationThatWouldExceedTheDailyLimit() {
        AiOrganizationBudget budget = budget(100);
        AiOrganizationBudgetUsage usage = usage(60);
        when(mapper.getForUpdate(3)).thenReturn(budget);
        when(mapper.getUsageForUpdate(3, DAY)).thenReturn(usage);
        when(mapper.sumReservedTokens(3, DAY)).thenReturn(20L);

        assertThrows(AiBudgetExhaustedException.class, () -> operations.reserve(
                3, DAY, 21, "reservation", NOW, NOW.plusMinutes(10)));
    }

    @Test
    void reservationAndSettlementUseTheLockedSharedLedger() {
        when(mapper.getForUpdate(3)).thenReturn(budget(100));
        when(mapper.getUsageForUpdate(3, DAY)).thenReturn(usage(60));
        when(mapper.sumReservedTokens(3, DAY)).thenReturn(20L);
        when(mapper.insertReservation(
                "reservation", 3, DAY, 20, NOW.plusMinutes(10))).thenReturn(1);

        AiBudgetControlOperations.Reservation reserved = operations.reserve(
                3, DAY, 20, "reservation", NOW, NOW.plusMinutes(10));

        assertTrue(reserved.metered());
        AiOrganizationBudgetReservation stored = new AiOrganizationBudgetReservation();
        stored.setReservationId("reservation");
        stored.setOrgId(3);
        stored.setUsageDay(DAY);
        stored.setReservedTokens(20);
        when(mapper.getReservationForUpdate("reservation")).thenReturn(stored);
        when(mapper.addConsumedTokens(3, DAY, 14)).thenReturn(1);

        operations.settle("reservation", 14);

        verify(mapper).addConsumedTokens(3, DAY, 14);
        verify(mapper).deleteReservation("reservation");
    }

    @Test
    void absentBudgetIsExplicitlyUnmetered() {
        AiBudgetControlOperations.Reservation reservation = operations.reserve(
                3, DAY, 20, "reservation", NOW, NOW.plusMinutes(10));

        assertFalse(reservation.metered());
        assertEquals(0, reservation.reservedTokens());
    }

    private static AiOrganizationBudget budget(long limit) {
        AiOrganizationBudget budget = new AiOrganizationBudget();
        budget.setOrgId(3);
        budget.setDailyTokenLimit(limit);
        return budget;
    }

    private static AiOrganizationBudgetUsage usage(long consumed) {
        AiOrganizationBudgetUsage usage = new AiOrganizationBudgetUsage();
        usage.setOrgId(3);
        usage.setConsumedTokens(consumed);
        return usage;
    }
}
