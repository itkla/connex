package ooo.klae.connex.backend.ai.assistant;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.assistant.AiAssistantToolCatalog.ToolTier;
import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteToolRequest.RescheduleTask;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.services.TaskService;
import ooo.klae.connex.backend.tenant.Permission;
import tools.jackson.databind.JsonNode;

/**
 * Reschedules a task only after approval of its canonical state fingerprint.
 *
 * <p>The framework owns target authorization, freshness and locking. TaskService retains its
 * domain permission checks and records the audit. Description follows the same screened and
 * masked tool-result path as list_tasks; no identifier or additional text class reaches the model.
 * Completion additionally retains the service's assignee-only rule. Neither tool offers undo.
 */
@Component
@RequiredArgsConstructor
public class AiAssistantRescheduleTaskWriteTool implements AiAssistantWriteTool {
    private static final String NAME = "reschedule_task";
    private final TaskService taskService;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ToolTier tier() {
        return ToolTier.CONFIRM;
    }

    @Override
    public Class<? extends AiAssistantWriteToolRequest> requestType() {
        return RescheduleTask.class;
    }

    @Override
    public Set<String> acceptedTargetKinds() {
        return Set.of("task");
    }

    @Override
    public Set<String> declaredWritableFields() {
        return Set.of("task.dueDate");
    }

    @Override
    public Set<Permission> requiredPermissions(String targetKind) {
        return Set.of(Permission.TASK_UPDATE);
    }

    @Override
    public Lock lock(String targetKind) {
        return new Lock(false, TargetLock.TASK_ROW);
    }

    @Override
    public Freshness freshness() {
        return Freshness.TARGET_FINGERPRINT;
    }

    @Override
    public List<PrincipalRequest> principals(
            AiAssistantWriteToolRequest request, MemberDirectory directory) {
        return List.of();
    }

    /** Refuses an impossible calendar date before a proposal can be stored. */
    @Override
    public Resolution resolve(Target target, AiAssistantWriteToolRequest request) {
        if (!(request instanceof RescheduleTask reschedule)) {
            throw new IllegalStateException("Assistant tool request is invalid");
        }
        try {
            LocalDate.parse(reschedule.dueDate());
        } catch (DateTimeParseException exception) {
            throw AiAssistantLoopException.refusedArguments("invalid_tool_arguments");
        }
        return null;
    }

    @Override
    public Outcome apply(Execution execution) {
        if (!(execution.row().request() instanceof RescheduleTask request)) {
            throw new IllegalStateException("Assistant tool request is invalid");
        }
        Target target = execution.row().target();
        Task returned = taskService.reschedule(target.id(), request.dueDate());
        Map<String, Object> outcome = new LinkedHashMap<>();
        outcome.put("status", "executed");
        outcome.put("recordType", "task");
        outcome.put("description", returned.getDescription());
        outcome.put("dueDate", returned.getDueDate());
        return new Outcome(outcome, null, new ReadBack("taskId", target.id(), returned.getId()));
    }

    @Override
    public boolean inverseAvailable() {
        return false;
    }

    @Override
    public Set<ReviewInput> reviewInputs() {
        return Set.of(ReviewInput.FIELDS);
    }

    @Override
    public Diff diff(Review review) {
        if (review.target() == null) {
            return new Diff("dueDate", null, false, null, DiffState.UNRESOLVED);
        }
        JsonNode date = review.request() == null ? null : review.request().get("due_date");
        if (date == null || !date.isString() || date.asString().isBlank()) {
            return new Diff("dueDate", null, false, null, DiffState.UNRESOLVED);
        }
        String current = review.target().field("dueDate");
        return new Diff("dueDate", current, false, date.asString(),
                Objects.equals(current, date.asString()) ? DiffState.UNCHANGED : DiffState.CHANGED);
    }

    @Override
    public String requestSummary(Review review) {
        return "Reschedule the task";
    }

    @Override
    public String outcomeSummary(Review review) {
        return "Task rescheduled";
    }

    @Override
    public Map<String, Object> modelOutcome(JsonNode storedOutcome) {
        Map<String, Object> result = new LinkedHashMap<>();
        AiAssistantWriteTool.copyText(storedOutcome, result, "recordType");
        AiAssistantWriteTool.copyText(storedOutcome, result, "description");
        AiAssistantWriteTool.copyText(storedOutcome, result, "dueDate");
        return result;
    }

    @Override
    public List<String> memberOutcomeFields() {
        return List.of("description", "dueDate");
    }
}
