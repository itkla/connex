package ooo.klae.connex.backend.ai;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.AiOrganizationBudget;
import ooo.klae.connex.backend.beans.AiOrganizationBudgetReservation;
import ooo.klae.connex.backend.beans.AiOrganizationBudgetUsage;
import ooo.klae.connex.backend.dto.AiUsageBreakdownDto;
import ooo.klae.connex.backend.exceptions.AiBudgetExhaustedException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.mappers.AiOrganizationBudgetMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.services.AuditService;
import ooo.klae.connex.backend.services.OrgMemberService;

/** Transactional control-plane row locks for the shared organization AI budget ledger. */
@Service
@RequiredArgsConstructor
public class AiBudgetControlOperations {
    private final AiOrganizationBudgetMapper budgetMapper;
    private final UserMapper userMapper;
    private final WorkspaceMapper workspaceMapper;
    private final OrganizationMapper organizationMapper;
    private final OrgMemberService orgMemberService;
    private final AuditService auditService;

    /** Reserves a conservative provider-call token ceiling or returns an unmetered marker. */
    @Transactional
    public Reservation reserve(
            int orgId,
            LocalDate usageDay,
            long requestedTokens,
            String reservationId,
            LocalDateTime now,
            LocalDateTime expiresAt) {
        AiOrganizationBudget budget = budgetMapper.getForUpdate(orgId);
        if (budget == null || budget.getDailyTokenLimit() == 0) {
            return Reservation.unmetered(orgId, usageDay);
        }
        budgetMapper.deleteExpiredReservations(now);
        budgetMapper.ensureUsage(orgId, usageDay);
        AiOrganizationBudgetUsage usage = budgetMapper.getUsageForUpdate(orgId, usageDay);
        if (usage == null) {
            throw new IllegalStateException("Organization AI budget usage row is unavailable");
        }
        long reserved = budgetMapper.sumReservedTokens(orgId, usageDay);
        long available = Math.max(
                0,
                budget.getDailyTokenLimit() - Math.min(
                        budget.getDailyTokenLimit(),
                        saturatedAdd(usage.getConsumedTokens(), reserved)));
        if (requestedTokens > available) {
            throw new AiBudgetExhaustedException();
        }
        if (budgetMapper.insertReservation(
                reservationId, orgId, usageDay, requestedTokens, expiresAt) != 1) {
            throw new IllegalStateException("Organization AI budget reservation was not created");
        }
        return new Reservation(reservationId, orgId, usageDay, requestedTokens, true);
    }

    /** Consumes actual provider tokens and removes the matching reservation exactly once. */
    @Transactional
    public void settle(String reservationId, long consumedTokens) {
        AiOrganizationBudgetReservation reservation =
                budgetMapper.getReservationForUpdate(reservationId);
        if (reservation == null) {
            return;
        }
        budgetMapper.ensureUsage(reservation.getOrgId(), reservation.getUsageDay());
        if (consumedTokens > 0 && budgetMapper.addConsumedTokens(
                reservation.getOrgId(), reservation.getUsageDay(), consumedTokens) != 1) {
            throw new IllegalStateException("Organization AI budget usage was not updated");
        }
        budgetMapper.deleteReservation(reservationId);
    }

    /** Releases a provider-call reservation that never produced billable token counts. */
    @Transactional
    public void release(String reservationId) {
        budgetMapper.deleteReservation(reservationId);
    }

    /** Returns the current limit, ledger state, and audit-derived daily usage. */
    @Transactional
    public Snapshot snapshot(int orgId, LocalDate usageDay, LocalDateTime now) {
        budgetMapper.deleteExpiredReservations(now);
        AiOrganizationBudget budget = budgetMapper.get(orgId);
        long limit = budget == null ? 0 : budget.getDailyTokenLimit();
        long consumed = budgetMapper.getConsumedTokens(orgId, usageDay);
        long reserved = budgetMapper.sumReservedTokens(orgId, usageDay);
        List<AiUsageBreakdownDto> usage = budgetMapper.listDailyUsage(orgId, usageDay);
        return new Snapshot(limit, consumed, reserved, usage);
    }

    /** Replaces the configured organization daily token limit. */
    @Transactional
    public void saveLimit(
            int workspaceId,
            int orgId,
            int actorId,
            long dailyTokenLimit) {
        if (userMapper.lockByIdForShare(actorId) == null) {
            throw administratorRequired();
        }
        Integer lockedOrgId = workspaceMapper.lockActiveWorkspaceForShare(workspaceId);
        if (!Objects.equals(lockedOrgId, orgId)
                || organizationMapper.lockActiveByIdForShare(orgId) == null
                || workspaceMapper.lockActiveMembership(workspaceId, actorId) == null) {
            throw administratorRequired();
        }
        orgMemberService.requireOrgAdminForUpdate(orgId, actorId);
        budgetMapper.upsert(orgId, dailyTokenLimit);
        auditService.recordStrictScoped(
                "org.ai_budget.save",
                "organization",
                orgId,
                workspaceId,
                orgId,
                "Organization " + orgId,
                "Updated organization AI daily token budget",
                Map.of("dailyUsageLimit", dailyTokenLimit));
    }

    private ForbiddenException administratorRequired() {
        return new ForbiddenException("Requires an organization administrator role");
    }

    private static long saturatedAdd(long first, long second) {
        return second > Long.MAX_VALUE - first ? Long.MAX_VALUE : first + second;
    }

    /** One provider-call budget reservation. */
    public record Reservation(
            String id,
            int orgId,
            LocalDate usageDay,
            long reservedTokens,
            boolean metered) {

        private static Reservation unmetered(int orgId, LocalDate usageDay) {
            return new Reservation(null, orgId, usageDay, 0, false);
        }
    }

    /** Current organization daily budget ledger projection. */
    public record Snapshot(
            long dailyTokenLimit,
            long consumedTokens,
            long reservedTokens,
            List<AiUsageBreakdownDto> usage) {

        public Snapshot {
            usage = List.copyOf(usage);
        }
    }
}
