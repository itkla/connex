package ooo.klae.connex.backend.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.annotation.Transactional;

import ooo.klae.connex.backend.beans.AiOrganizationBudget;
import ooo.klae.connex.backend.dto.AiUsageBreakdownDto;
import ooo.klae.connex.backend.beans.AiOrganizationBudgetReservation;
import ooo.klae.connex.backend.beans.AiOrganizationBudgetUsage;
import ooo.klae.connex.backend.exceptions.AiBudgetExhaustedException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.mappers.AiOrganizationBudgetMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.services.AuditService;
import ooo.klae.connex.backend.services.OrgMemberService;

class AiBudgetControlOperationsTest {
    private static final LocalDate DAY = LocalDate.of(2026, 8, 10);
    private static final LocalDateTime NOW = DAY.atStartOfDay();

    private final AiOrganizationBudgetMapper mapper = mock(AiOrganizationBudgetMapper.class);
    private final UserMapper userMapper = mock(UserMapper.class);
    private final WorkspaceMapper workspaceMapper = mock(WorkspaceMapper.class);
    private final OrganizationMapper organizationMapper = mock(OrganizationMapper.class);
    private final OrgMemberService orgMemberService = mock(OrgMemberService.class);
    private final AuditService auditService = mock(AuditService.class);
    private final AiBudgetControlOperations operations = new AiBudgetControlOperations(
            mapper,
            userMapper,
            workspaceMapper,
            organizationMapper,
            orgMemberService,
            auditService);

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
        verify(mapper, never()).listExpiredReservationIds(NOW);
        InOrder admissionOrder = inOrder(mapper);
        admissionOrder.verify(mapper).getForUpdate(3);
        admissionOrder.verify(mapper).getUsageForUpdate(3, DAY);
        admissionOrder.verify(mapper).insertReservation(
                "reservation", 3, DAY, 20, NOW.plusMinutes(10));
        AiOrganizationBudgetReservation stored = new AiOrganizationBudgetReservation();
        stored.setReservationId("reservation");
        stored.setOrgId(3);
        stored.setUsageDay(DAY);
        stored.setReservedTokens(20);
        stored.setState("dispatched");
        when(mapper.getReservation("reservation")).thenReturn(stored);
        when(mapper.markReservationSettled("reservation", 14)).thenReturn(1);
        when(mapper.getReservationForUpdate("reservation")).thenReturn(stored);
        when(mapper.addConsumedTokens(3, DAY, 14)).thenReturn(1);

        operations.settle("reservation", 14);

        verify(mapper).addConsumedTokens(3, DAY, 14);
        InOrder settlementOrder = inOrder(mapper);
        settlementOrder.verify(mapper).getReservation("reservation");
        settlementOrder.verify(mapper).getForUpdate(3);
        settlementOrder.verify(mapper).getUsageForUpdate(3, DAY);
        settlementOrder.verify(mapper).getReservationForUpdate("reservation");
        settlementOrder.verify(mapper).addConsumedTokens(3, DAY, 14);
        settlementOrder.verify(mapper).markReservationSettled("reservation", 14);
        verify(mapper, never()).deleteReservation("reservation");
    }

    @Test
    void snapshotsLeaveGlobalExpiryCleanupToTheSweep() {
        operations.snapshot(3, DAY, NOW);

        verify(mapper, never()).listExpiredReservationIds(NOW);
        operations.expiredReservationIds(NOW);
        verify(mapper).listExpiredReservationIds(NOW);
    }

    @Test
    void snapshotsReconcileConservativeConsumptionWithoutInventingActorOrFeatureAttribution() {
        AiUsageBreakdownDto successful = new AiUsageBreakdownDto(11, "Member", "deal_brief", 7, 5);
        when(mapper.getConsumedTokens(3, DAY)).thenReturn(32L);
        when(mapper.listDailyUsage(3, DAY)).thenReturn(List.of(successful));

        AiBudgetControlOperations.Snapshot snapshot = operations.snapshot(3, DAY, NOW);

        assertEquals(List.of(successful, new AiUsageBreakdownDto(
                null, "Conservative / unattributed charges", "unattributed", 20, 0)), snapshot.usage());
        assertEquals(snapshot.consumedTokens(), snapshot.usage().stream()
                .mapToLong(entry -> entry.inputUsage() + entry.outputUsage()).sum());
        verify(mapper).getConsumedTokens(3, DAY);
        verify(mapper).listDailyUsage(3, DAY);
    }

    @Test
    void snapshotsDoNotAddNegativeChargesForUnmeteredAuditsOrOverflowedTotals() {
        when(mapper.getConsumedTokens(3, DAY)).thenReturn(20L);
        List<AiUsageBreakdownDto> usage = List.of(
                new AiUsageBreakdownDto(11, "Member", "deal_brief", Long.MAX_VALUE, 1));
        when(mapper.listDailyUsage(3, DAY)).thenReturn(usage);

        assertEquals(usage, operations.snapshot(3, DAY, NOW).usage());
    }

    @Test
    void retentionUsesItsOwnBoundedMapperTransactionWithoutLedgerLocks() throws Exception {
        when(mapper.deleteSettledReservationsBefore(NOW.minusDays(7))).thenReturn(100);

        assertEquals(100, operations.purgeSettledReservations(NOW.minusDays(7)));
        verify(mapper).deleteSettledReservationsBefore(NOW.minusDays(7));
        verify(mapper, never()).getForUpdate(3);
        assertEquals(org.springframework.transaction.annotation.Propagation.REQUIRES_NEW,
                AiBudgetControlOperations.class.getMethod("purgeSettledReservations", LocalDateTime.class)
                        .getAnnotation(Transactional.class).propagation());
    }

    @Test
    void releaseAfterAnAmbiguousDispatchCommitChargesTheDurableDispatch() {
        AiOrganizationBudgetReservation stored = storedReservation("dispatched");
        when(mapper.markReservationSettled("reservation", 20)).thenReturn(1);
        when(mapper.addConsumedTokens(3, DAY, 20)).thenReturn(1);

        operations.release(stored.getReservationId());

        verify(mapper).addConsumedTokens(3, DAY, 20);
        verify(mapper).markReservationSettled("reservation", 20);
        verify(mapper, never()).deleteReservation("reservation");
    }

    @Test
    void expirySettlementPreventsLateDispatchAndDuplicateConsumption() {
        storedReservation("settled");

        assertThrows(IllegalStateException.class, () -> operations.markDispatched("reservation", NOW));
        operations.settle("reservation", 20);
        operations.release("reservation");
        operations.expireReservation("reservation", NOW.plusMinutes(20));

        verify(mapper, never()).addConsumedTokens(3, DAY, 20);
        verify(mapper, never()).deleteReservation("reservation");
    }

    @Test
    void lateSettlementAfterTombstonePurgeCannotChargeAgainOrDispatch() {
        operations.settle("purged", 20);
        operations.release("purged");
        assertThrows(IllegalStateException.class, () -> operations.markDispatched("purged", NOW));

        verify(mapper, never()).addConsumedTokens(3, DAY, 20);
        verify(mapper, never()).markReservationSettled("purged", 20);
    }

    @Test
    void expiredPendingReservationCannotBeginDispatch() {
        storedReservation("reserved");

        assertThrows(IllegalStateException.class, () -> operations.markDispatched(
                "reservation", NOW.plusMinutes(20)));

        verify(mapper, never()).markReservationDispatched("reservation");
    }

    private AiOrganizationBudgetReservation storedReservation(String state) {
        AiOrganizationBudgetReservation stored = new AiOrganizationBudgetReservation();
        stored.setReservationId("reservation");
        stored.setOrgId(3);
        stored.setUsageDay(DAY);
        stored.setReservedTokens(20);
        stored.setExpiresAt(NOW.plusMinutes(10));
        stored.setState(state);
        when(mapper.getReservation("reservation")).thenReturn(stored);
        when(mapper.getReservationForUpdate("reservation")).thenReturn(stored);
        when(mapper.getForUpdate(3)).thenReturn(budget(100));
        when(mapper.getUsageForUpdate(3, DAY)).thenReturn(usage(0));
        return stored;
    }

    @Test
    void absentBudgetIsExplicitlyUnmetered() {
        AiBudgetControlOperations.Reservation reservation = operations.reserve(
                3, DAY, 20, "reservation", NOW, NOW.plusMinutes(10));

        assertFalse(reservation.metered());
        assertEquals(0, reservation.reservedTokens());
    }

    @Test
    void permissionRevokedBetweenPrecheckAndLockedBudgetWriteBlocksMutation() {
        when(userMapper.lockByIdForShare(11)).thenReturn(11);
        when(workspaceMapper.lockActiveWorkspaceForShare(7)).thenReturn(3);
        when(workspaceMapper.lockActiveMembership(7, 11)).thenReturn(11);
        when(organizationMapper.lockActiveByIdForShare(3)).thenReturn(3);
        doThrow(new ForbiddenException("Requires an organization administrator role"))
                .when(orgMemberService).requireOrgAdminForUpdate(3, 11);

        assertThrows(
                ForbiddenException.class,
                () -> operations.saveLimit(7, 3, 11, 1_000));

        verify(mapper, never()).upsert(3, 1_000);
        verifyNoInteractions(auditService);
    }

    @Test
    void budgetWriteLocksAuthorityAndAuditsInsideItsTransaction() throws Exception {
        when(userMapper.lockByIdForShare(11)).thenReturn(11);
        when(workspaceMapper.lockActiveWorkspaceForShare(7)).thenReturn(3);
        when(workspaceMapper.lockActiveMembership(7, 11)).thenReturn(11);
        when(organizationMapper.lockActiveByIdForShare(3)).thenReturn(3);

        operations.saveLimit(7, 3, 11, 1_000);

        InOrder order = inOrder(
                userMapper,
                workspaceMapper,
                organizationMapper,
                orgMemberService,
                mapper,
                auditService);
        order.verify(userMapper).lockByIdForShare(11);
        order.verify(workspaceMapper).lockActiveWorkspaceForShare(7);
        order.verify(organizationMapper).lockActiveByIdForShare(3);
        order.verify(workspaceMapper).lockActiveMembership(7, 11);
        order.verify(orgMemberService).requireOrgAdminForUpdate(3, 11);
        order.verify(mapper).upsert(3, 1_000);
        order.verify(auditService).recordStrictScoped(
                "org.ai_budget.save",
                "organization",
                3,
                7,
                3,
                "Organization 3",
                "Updated organization AI daily token budget",
                java.util.Map.of("dailyUsageLimit", 1_000L));
        assertTrue(AiBudgetControlOperations.class
                .getMethod("saveLimit", int.class, int.class, int.class, long.class)
                .isAnnotationPresent(Transactional.class));
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
