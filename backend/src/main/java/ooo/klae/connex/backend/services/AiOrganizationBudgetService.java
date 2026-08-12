package ooo.klae.connex.backend.services;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.AiBudgetControlAccess;
import ooo.klae.connex.backend.ai.AiBudgetControlOperations;
import ooo.klae.connex.backend.ai.AiBudgetControlOperations.Snapshot;
import ooo.klae.connex.backend.dto.AiOrganizationBudgetDto;
import ooo.klae.connex.backend.dto.AiOrganizationBudgetRequest;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;

/** Organization-administrator configuration and reporting for the shared daily AI budget. */
@Service
@RequiredArgsConstructor
public class AiOrganizationBudgetService {
    private static final long MAX_DAILY_TOKEN_LIMIT = 1_000_000_000_000L;

    private final AiBudgetControlOperations operations;
    private final AiBudgetControlAccess controlAccess;
    private final WorkspaceService workspaceService;
    private final OrgMemberService orgMemberService;
    private final Clock clock;

    /** Returns the current UTC-day budget and member-by-feature usage. */
    public AiOrganizationBudgetDto getForWorkspace(int workspaceId, int actorId) {
        int orgId = requireAdministrator(workspaceId, actorId);
        return snapshot(orgId);
    }

    /** Replaces the organization daily token limit, where zero means unlimited. */
    public AiOrganizationBudgetDto save(
            int workspaceId,
            int actorId,
            AiOrganizationBudgetRequest request) {
        int orgId = requireAdministrator(workspaceId, actorId);
        if (request == null || request.dailyUsageLimit() == null
                || request.dailyUsageLimit() < 0
                || request.dailyUsageLimit() > MAX_DAILY_TOKEN_LIMIT) {
            throw new BadRequestException("Organization AI budget is invalid");
        }
        controlAccess.execute(() -> {
            operations.saveLimit(
                    workspaceId,
                    orgId,
                    actorId,
                    request.dailyUsageLimit());
            return null;
        });
        return snapshot(orgId);
    }

    private AiOrganizationBudgetDto snapshot(int orgId) {
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        LocalDate usageDay = now.toLocalDate();
        Snapshot snapshot = controlAccess.execute(() -> operations.snapshot(orgId, usageDay, now));
        long committed = saturatedAdd(snapshot.consumedTokens(), snapshot.reservedTokens());
        long remaining = snapshot.dailyTokenLimit() == 0
                ? 0
                : Math.max(0, snapshot.dailyTokenLimit() - Math.min(snapshot.dailyTokenLimit(), committed));
        boolean exhausted = snapshot.dailyTokenLimit() > 0 && remaining == 0;
        return new AiOrganizationBudgetDto(
                orgId,
                usageDay,
                snapshot.dailyTokenLimit(),
                snapshot.consumedTokens(),
                snapshot.reservedTokens(),
                remaining,
                exhausted,
                snapshot.usage());
    }

    private int requireAdministrator(int workspaceId, int actorId) {
        if (workspaceService.getCurrentWorkspaceId() != workspaceId) {
            throw new ForbiddenException("AI budget is restricted to the active workspace");
        }
        int orgId = workspaceService.getCurrentOrgId();
        orgMemberService.requireOrgAdmin(orgId, actorId);
        return orgId;
    }

    private static long saturatedAdd(long first, long second) {
        return second > Long.MAX_VALUE - first ? Long.MAX_VALUE : first + second;
    }
}
