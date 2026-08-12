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

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.annotation.Transactional;

import ooo.klae.connex.backend.beans.AiOrganizationBudget;
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
