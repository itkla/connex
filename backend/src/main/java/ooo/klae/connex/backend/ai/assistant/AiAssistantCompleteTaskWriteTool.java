package ooo.klae.connex.backend.ai.assistant;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.assistant.AiAssistantToolCatalog.ToolTier;
import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteToolRequest.CompleteTask;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.services.TaskService;
import ooo.klae.connex.backend.tenant.Permission;
import tools.jackson.databind.JsonNode;

/**
 * Completes a task only after approval of its canonical state fingerprint.
 *
 * <p>The framework owns target authorization, freshness and locking. TaskService retains its
 * domain permission checks and records the audit. Description follows the same screened and
 * masked tool-result path as list_tasks; no identifier or additional text class reaches the model.
 * Completion additionally retains the service's assignee-only rule. Neither tool offers undo.
 */
@Component
@RequiredArgsConstructor
public class AiAssistantCompleteTaskWriteTool implements AiAssistantWriteTool {
    private static final String NAME = "complete_task";
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
        return CompleteTask.class;
    }

    @Override
    public Set<String> acceptedTargetKinds() {
        return Set.of("task");
    }

    @Override
    public Set<String> declaredWritableFields() {
        return Set.of("task.completed", "task.status", "task.position");
    }

    @Override
    public Set<Permission> requiredPermissions(String targetKind) {
        return Set.of(Permission.TASK_UPDATE);
    }

    @Override
    public Lock lock(String targetKind) {
        return new Lock(true, TargetLock.TASK_ROW);
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

    @Override
    public Outcome apply(Execution execution) {
        if (!(execution.row().request() instanceof CompleteTask request)) {
            throw new IllegalStateException("Assistant tool request is invalid");
        }
        Target target = execution.row().target();
        Task returned = taskService.complete(target.id());
        Map<String, Object> outcome = new LinkedHashMap<>();
        outcome.put("status", "executed");
        outcome.put("recordType", "task");
        outcome.put("description", returned.getDescription());
        outcome.put("completed", returned.isCompleted());
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
            return new Diff("taskStatus", null, false, null, DiffState.UNRESOLVED);
        }
        String current = review.target().field("taskStatus");
        return new Diff("taskStatus", current, false, "done",
                "done".equals(current) ? DiffState.UNCHANGED : DiffState.CHANGED);
    }

    @Override
    public String requestSummary(Review review) {
        return "Complete the task";
    }

    @Override
    public String outcomeSummary(Review review) {
        return "Task completed";
    }

    @Override
    public Map<String, Object> modelOutcome(JsonNode storedOutcome) {
        Map<String, Object> result = new LinkedHashMap<>();
        AiAssistantWriteTool.copyText(storedOutcome, result, "recordType");
        AiAssistantWriteTool.copyText(storedOutcome, result, "description");
        result.put("completed", storedOutcome.path("completed").asBoolean());
        return result;
    }

    @Override
    public List<String> memberOutcomeFields() {
        return List.of("description");
    }
}
