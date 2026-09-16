package ooo.klae.connex.backend.ai;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
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
    @Transactional(propagation = Propagation.REQUIRES_NEW)
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

    /** Commits dispatch before model egress, refusing expired or already settled leases. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markDispatched(String reservationId, LocalDateTime now) {
        AiOrganizationBudgetReservation reservation = lockReservation(reservationId);
        if (reservation == null || "settled".equals(reservation.getState())
                || !reservation.getExpiresAt().isAfter(now)) {
            throw new IllegalStateException("Provider dispatch requires a live budget reservation");
        }
        if ("dispatched".equals(reservation.getState())) {
            return;
        }
        if (!"reserved".equals(reservation.getState())
                || budgetMapper.markReservationDispatched(reservationId) != 1) {
            throw new IllegalStateException("Organization AI budget dispatch was not recorded");
        }
    }

    /** Atomically consumes provider tokens and retains an exactly-once settlement record. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void settle(String reservationId, long consumedTokens) {
        if (consumedTokens < 0) {
            throw new IllegalArgumentException("Organization AI usage must not be negative");
        }
        AiOrganizationBudgetReservation reservation = lockReservation(reservationId);
        if (reservation == null || "settled".equals(reservation.getState())) {
            return;
        }
        consume(reservation, consumedTokens);
    }

    /** Releases only durably pre-dispatch work, including after an ambiguous dispatch commit. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(String reservationId) {
        AiOrganizationBudgetReservation reservation = lockReservation(reservationId);
        if (reservation == null || "settled".equals(reservation.getState())) {
            return;
        }
        releaseOrConsume(reservation);
    }

    /** Discovers a bounded expiry batch without taking cross-organization range locks. */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public List<String> expiredReservationIds(LocalDateTime now) {
        return budgetMapper.listExpiredReservationIds(now);
    }

    /** Rechecks one expired lease under budget, usage, then reservation locks. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void expireReservation(String reservationId, LocalDateTime now) {
        AiOrganizationBudgetReservation reservation = lockReservation(reservationId);
        if (reservation == null || "settled".equals(reservation.getState())
                || reservation.getExpiresAt().isAfter(now)) {
            return;
        }
        releaseOrConsume(reservation);
    }

    private AiOrganizationBudgetReservation lockReservation(String reservationId) {
        AiOrganizationBudgetReservation discovered = budgetMapper.getReservation(reservationId);
        if (discovered == null) {
            return null;
        }
        if (budgetMapper.getForUpdate(discovered.getOrgId()) == null) {
            throw new IllegalStateException("Organization AI budget row is unavailable");
        }
        budgetMapper.ensureUsage(discovered.getOrgId(), discovered.getUsageDay());
        if (budgetMapper.getUsageForUpdate(discovered.getOrgId(), discovered.getUsageDay()) == null) {
            throw new IllegalStateException("Organization AI budget usage row is unavailable");
        }
        return budgetMapper.getReservationForUpdate(reservationId);
    }

    private void releaseOrConsume(AiOrganizationBudgetReservation reservation) {
        if ("reserved".equals(reservation.getState())) {
            budgetMapper.deleteReservation(reservation.getReservationId());
        } else {
            consume(reservation, reservation.getReservedTokens());
        }
    }

    private void consume(AiOrganizationBudgetReservation reservation, long consumedTokens) {
        if (consumedTokens > 0 && budgetMapper.addConsumedTokens(
                reservation.getOrgId(), reservation.getUsageDay(), consumedTokens) != 1) {
            throw new IllegalStateException("Organization AI budget usage was not updated");
        }
        if (budgetMapper.markReservationSettled(reservation.getReservationId(), consumedTokens) != 1) {
            throw new IllegalStateException("Organization AI budget settlement was not recorded");
        }
    }

    /** Returns the current limit, ledger state, and audit-derived daily usage. */
    @Transactional
    public Snapshot snapshot(int orgId, LocalDate usageDay, LocalDateTime now) {
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
