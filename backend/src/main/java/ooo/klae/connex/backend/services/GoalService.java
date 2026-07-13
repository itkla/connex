package ooo.klae.connex.backend.services;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.ReportGoal;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.ReportGoalDto;
import ooo.klae.connex.backend.dto.ReportGoalRequest;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.DuplicateResourceException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.GoalMapper;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;

/** Business logic for workspace-scoped report goals and owner-label hydration. */
@Service
@RequiredArgsConstructor
public class GoalService {
    private static final String WON_REVENUE = "won_revenue";

    private final GoalMapper goalMapper;
    private final WorkspaceService workspaceService;
    private final AuthService authService;
    private final AuditService auditService;

    /** Returns every goal in the active workspace. */
    @RequirePermission(Permission.GOAL_READ)
    public List<ReportGoalDto> list() {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Map<Integer, String> ownerLabels = ownerLabels(workspaceId);
        return goalMapper.getGoals(workspaceId).stream()
                .map(goal -> toDto(goal, ownerLabels))
                .toList();
    }

    /** Returns one goal in the active workspace. */
    @RequirePermission(Permission.GOAL_READ)
    public ReportGoalDto get(int id) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        return toDto(requireGoal(workspaceId, id), ownerLabels(workspaceId));
    }

    /** Creates a workspace-wide or owner-scoped goal. */
    @Transactional
    @RequirePermission(Permission.GOAL_MANAGE)
    public ReportGoalDto create(ReportGoalRequest request) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Map<Integer, String> ownerLabels = ownerLabels(workspaceId);
        ValidatedGoal validated = validate(request, ownerLabels);
        assertUnique(workspaceId, validated, null);
        ReportGoal goal = new ReportGoal();
        goal.setWorkspaceId(workspaceId);
        goal.setCreatedBy(authService.getCurrentUser().getId());
        apply(goal, validated);
        try {
            goalMapper.insert(goal);
        } catch (DuplicateKeyException exception) {
            throw duplicateGoal();
        }
        auditService.record("goal.create", "goal", goal.getId(), auditLabel(goal),
                "Created report goal", null);
        return toDto(requireGoal(workspaceId, goal.getId()), ownerLabels);
    }

    /** Replaces a goal in the active workspace. */
    @Transactional
    @RequirePermission(Permission.GOAL_MANAGE)
    public ReportGoalDto update(int id, ReportGoalRequest request) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        ReportGoal goal = requireGoal(workspaceId, id);
        Map<Integer, String> ownerLabels = ownerLabels(workspaceId);
        ValidatedGoal validated = validate(request, ownerLabels);
        assertUnique(workspaceId, validated, id);
        apply(goal, validated);
        try {
            if (goalMapper.update(goal) == 0) {
                throw new ResourceNotFoundException("Report goal not found with id: " + id);
            }
        } catch (DuplicateKeyException exception) {
            throw duplicateGoal();
        }
        auditService.record("goal.update", "goal", goal.getId(), auditLabel(goal),
                "Updated report goal", null);
        return toDto(requireGoal(workspaceId, id), ownerLabels);
    }

    /** Deletes a goal in the active workspace. */
    @Transactional
    @RequirePermission(Permission.GOAL_MANAGE)
    public void delete(int id) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        ReportGoal goal = requireGoal(workspaceId, id);
        if (goalMapper.delete(workspaceId, id) == 0) {
            throw new ResourceNotFoundException("Report goal not found with id: " + id);
        }
        auditService.record("goal.delete", "goal", id, auditLabel(goal),
                "Deleted report goal", null);
    }

    private ReportGoal requireGoal(int workspaceId, int id) {
        ReportGoal goal = goalMapper.getGoal(workspaceId, id);
        if (goal == null) {
            throw new ResourceNotFoundException("Report goal not found with id: " + id);
        }
        return goal;
    }

    private ValidatedGoal validate(ReportGoalRequest request, Map<Integer, String> ownerLabels) {
        if (request == null) {
            throw new BadRequestException("Report goal is required");
        }
        String metric = normalize(request.metric());
        String periodType = normalize(request.periodType());
        String currency = request.currency() == null
                ? null
                : request.currency().trim().toUpperCase(Locale.ROOT);
        if (!WON_REVENUE.equals(metric)) {
            throw new BadRequestException("Invalid goal metric: " + request.metric());
        }
        if (!List.of("month", "quarter").contains(periodType)) {
            throw new BadRequestException("Invalid goal period type: " + request.periodType());
        }
        if (currency == null || !currency.matches("[A-Z]{3,8}")) {
            throw new BadRequestException("Goal currency must contain 3 to 8 letters");
        }
        if (request.targetValue() == null || request.targetValue().signum() < 0
                || request.targetValue().scale() > 2 || request.targetValue().precision() - request.targetValue().scale() > 13) {
            throw new BadRequestException("Goal target must be a non-negative DECIMAL(15,2) value");
        }
        LocalDate periodStart = request.periodStart();
        if (periodStart == null || periodStart.getDayOfMonth() != 1
                || "quarter".equals(periodType) && (periodStart.getMonthValue() - 1) % 3 != 0) {
            throw new BadRequestException("Goal period start must be the first day of its month or calendar quarter");
        }
        if (request.ownerId() != null && !ownerLabels.containsKey(request.ownerId())) {
            throw new BadRequestException("Goal owner must be an active workspace member");
        }
        return new ValidatedGoal(request.ownerId(), metric, periodType, periodStart, request.targetValue(), currency);
    }

    private void assertUnique(int workspaceId, ValidatedGoal validated, Integer currentId) {
        boolean duplicate = goalMapper.getGoalsForPeriod(
                        workspaceId, validated.metric(), validated.periodType(), validated.periodStart()).stream()
                .anyMatch(goal -> !Objects.equals(goal.getId(), currentId)
                        && Objects.equals(goal.getOwnerId(), validated.ownerId())
                        && validated.currency().equalsIgnoreCase(goal.getCurrency()));
        if (duplicate) {
            throw duplicateGoal();
        }
    }

    private static DuplicateResourceException duplicateGoal() {
        return new DuplicateResourceException("periodStart",
                "A goal already exists for this scope, period, metric, and currency");
    }

    private static void apply(ReportGoal goal, ValidatedGoal validated) {
        goal.setOwnerId(validated.ownerId());
        goal.setMetric(validated.metric());
        goal.setPeriodType(validated.periodType());
        goal.setPeriodStart(validated.periodStart());
        goal.setTargetValue(validated.targetValue());
        goal.setCurrency(validated.currency());
    }

    private static ReportGoalDto toDto(ReportGoal goal, Map<Integer, String> ownerLabels) {
        return new ReportGoalDto(
                goal.getId(), goal.getOwnerId(), goal.getOwnerId() == null ? null : ownerLabels.get(goal.getOwnerId()),
                goal.getMetric(),
                goal.getPeriodType(), goal.getPeriodStart(), goal.getTargetValue(), goal.getCurrency(),
                goal.getCreatedBy(), goal.getCreatedAt(), goal.getUpdatedAt());
    }

    private Map<Integer, String> ownerLabels(int workspaceId) {
        return workspaceService.getMembers(workspaceId).stream()
                .collect(Collectors.toUnmodifiableMap(User::getId, User::getDisplayName, (left, right) -> left));
    }

    private static String auditLabel(ReportGoal goal) {
        return goal.getMetric() + " " + goal.getPeriodStart() + " " + goal.getCurrency();
    }

    private static String normalize(String value) {
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    private record ValidatedGoal(
            Integer ownerId,
            String metric,
            String periodType,
            LocalDate periodStart,
            java.math.BigDecimal targetValue,
            String currency) {
    }
}
