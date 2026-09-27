package ooo.klae.connex.backend.ai.assistant;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.assistant.AiAssistantToolCatalog.ToolTier;
import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteToolRequest.CreateTask;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.services.TaskService;
import ooo.klae.connex.backend.tenant.Permission;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Immediately creates one task on a person or deal, assigned to the member whose turn it is.
 *
 * <p>The framework holds the task board root and then the target — the person {@code FOR SHARE},
 * because linking a task does not change it — before this tool calls {@link TaskService#create}.
 * The inverse deletes the task only while it still matches the state this write created.
 */
@Component
@RequiredArgsConstructor
public class AiAssistantCreateTaskWriteTool implements AiAssistantWriteTool {
    private static final String NAME = "create_task";
    private static final String EXECUTED = "executed";
    private static final String TASK = "task";
    private static final Set<String> TARGET_KINDS = Set.of("person", "deal");

    private final TaskService taskService;
    private final AiAssistantDateResolver dateResolver;
    private final ObjectMapper objectMapper;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ToolTier tier() {
        return ToolTier.AUTO;
    }

    @Override
    public Class<? extends AiAssistantWriteToolRequest> requestType() {
        return CreateTask.class;
    }

    @Override
    public Set<String> acceptedTargetKinds() {
        return TARGET_KINDS;
    }

    @Override
    public Set<String> declaredWritableFields() {
        return Set.of(
                "task.description", "task.dueDate", "task.assignedTo", "task.person", "task.deal");
    }

    @Override
    public Set<Permission> requiredPermissions(String targetKind) {
        return Set.of(Permission.TASK_CREATE, Permission.TASK_DELETE);
    }

    @Override
    public Lock lock(String targetKind) {
        return new Lock(
                true,
                "person".equals(targetKind) ? TargetLock.PERSON_SHARE : TargetLock.RECORD_UPDATE);
    }

    @Override
    public List<PrincipalRequest> principals(AiAssistantWriteToolRequest request) {
        return List.of();
    }

    @Override
    public Outcome apply(Execution execution) {
        if (!(execution.row().request() instanceof CreateTask request)) {
            throw new IllegalStateException("Assistant tool request is invalid");
        }
        Task task = new Task();
        task.setDescription(request.description());
        LocalDate dueDate = dateResolver.resolveDate(request.dueDate());
        task.setDueDate(dueDate == null ? null : dueDate.toString());
        User assignee = new User();
        assignee.setId(execution.authority().userId());
        task.setAssignedTo(assignee);
        link(task, execution.row().target());
        Task created = taskService.create(task);
        Map<String, Object> outcome = new LinkedHashMap<>();
        outcome.put("status", EXECUTED);
        outcome.put("recordType", TASK);
        outcome.put("description", created.getDescription());
        if (created.getDueDate() != null) {
            outcome.put("dueDate", created.getDueDate());
        }
        return new Outcome(
                outcome,
                new Inverse(
                        TASK,
                        created.getId(),
                        AiAssistantWriteTool.fingerprint(objectMapper, taskState(created)),
                        true,
                        Map.of()),
                new ReadBack("taskId", null, created.getId()));
    }

    @Override
    public void undo(Authority authority, Inverse inverse) {
        if (!TASK.equals(inverse.entityKind())) {
            throw new ConflictException("Assistant tool undo metadata is invalid");
        }
        taskService.deleteIf(
                inverse.entityId(),
                current -> inverse.fingerprint().equals(
                        AiAssistantWriteTool.fingerprint(objectMapper, taskState(current))));
    }

    @Override
    public boolean inverseAvailable() {
        return true;
    }

    @Override
    public Diff diff(Review review) {
        return null;
    }

    @Override
    public String requestSummary(Review review) {
        return "Create a task";
    }

    @Override
    public String outcomeSummary(Review review) {
        return "Task created";
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

    private static void link(Task task, Target target) {
        if ("person".equals(target.kind())) {
            Person person = new Person();
            person.setId(target.id());
            task.setPerson(person);
        } else {
            Deal deal = new Deal();
            deal.setId(target.id());
            task.setDeal(deal);
        }
    }

    private static Map<String, Object> taskState(Task task) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("description", task.getDescription());
        state.put("completed", task.isCompleted());
        state.put("status", task.getStatus());
        state.put("position", task.getPosition());
        state.put("dueDate", task.getDueDate());
        state.put("assignedToId", task.getAssignedTo() == null ? 0 : task.getAssignedTo().getId());
        state.put("personId", task.getPerson() == null ? 0 : task.getPerson().getId());
        state.put("dealId", task.getDeal() == null ? 0 : task.getDeal().getId());
        return state;
    }
}
