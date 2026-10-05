package ooo.klae.connex.backend.ai.assistant;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.assistant.AiAssistantDateResolver.ResolvedDateTime;
import ooo.klae.connex.backend.ai.assistant.AiAssistantToolCatalog.ToolTier;
import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteToolRequest.CreateActivity;
import ooo.klae.connex.backend.beans.Activity;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.services.ActivityService;
import ooo.klae.connex.backend.tenant.Permission;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Immediately logs one activity on a person or deal.
 *
 * <p>The framework holds the target {@code FOR UPDATE} before this tool calls
 * {@link ActivityService#create}. A meeting logged on a person first reads, through the
 * framework's {@link AiAssistantWriteTool.ScheduleConflicts}, the activities already on that
 * person's calendar inside the meeting's window, so the outcome reports them — and the model is
 * told only how many there were and whether the list was cut short. The read-back compares the
 * record the returned activity links to with the target the framework resolved. It is structural:
 * {@link ActivityService#create} returns the very activity this tool built, carrying the link this
 * tool set, so the comparison cannot diverge while that holds and reads nothing back from the
 * database. The inverse deletes the activity only while it still matches the state this write
 * created.
 */
@Component
@RequiredArgsConstructor
public class AiAssistantCreateActivityWriteTool implements AiAssistantWriteTool {
    private static final String NAME = "create_activity";
    private static final String EXECUTED = "executed";
    private static final String ACTIVITY = "activity";
    private static final int DEFAULT_MEETING_MINUTES = 60;
    private static final Set<String> TARGET_KINDS = Set.of("person", "deal");

    private final ActivityService activityService;
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
        return CreateActivity.class;
    }

    @Override
    public Set<String> acceptedTargetKinds() {
        return TARGET_KINDS;
    }

    @Override
    public Set<String> declaredWritableFields() {
        return Set.of(
                "activity.type", "activity.subject", "activity.notes", "activity.timestamp",
                "activity.person", "activity.deal");
    }

    @Override
    public Set<Permission> requiredPermissions(String targetKind) {
        return Set.of(Permission.ACTIVITY_CREATE, Permission.ACTIVITY_DELETE);
    }

    @Override
    public Lock lock(String targetKind) {
        return new Lock(false, TargetLock.RECORD_UPDATE);
    }

    @Override
    public List<PrincipalRequest> principals(
            AiAssistantWriteToolRequest request, MemberDirectory directory) {
        return List.of();
    }

    @Override
    public Outcome apply(Execution execution) {
        if (!(execution.row().request() instanceof CreateActivity request)) {
            throw new IllegalStateException("Assistant tool request is invalid");
        }
        Target target = execution.row().target();
        ResolvedDateTime start = dateResolver.resolveDateTime(request.start());
        int durationMinutes = request.durationMinutes() == null
                ? DEFAULT_MEETING_MINUTES
                : request.durationMinutes();
        LocalDateTime endUtc = start.utc().plusMinutes(durationMinutes);
        List<?> conflicts = List.of();
        boolean conflictsTruncated = false;
        if ("meeting".equalsIgnoreCase(request.type()) && "person".equals(target.kind())) {
            AiAssistantToolResult conflictResult =
                    execution.scheduleConflicts().find(start.utc(), endUtc);
            Object conflictData = conflictResult.data().get("conflicts");
            conflicts = conflictData instanceof List<?> list ? list : List.of();
            conflictsTruncated = Boolean.TRUE.equals(
                    conflictResult.data().get("conflictsTruncated"));
        }
        Activity activity = new Activity();
        activity.setType(request.type());
        activity.setSubject(request.subject());
        activity.setNotes(request.notes());
        activity.setTimestamp(start.mysqlUtc());
        link(activity, target);
        Activity created = activityService.create(activity);
        Map<String, Object> outcome = new LinkedHashMap<>();
        outcome.put("status", EXECUTED);
        outcome.put("recordType", ACTIVITY);
        outcome.put("type", created.getType());
        outcome.put("subject", created.getSubject());
        outcome.put("start", created.getTimestamp());
        outcome.put("timezone", start.timezone().getId());
        outcome.put("conflicts", conflicts);
        outcome.put("conflictsTruncated", conflictsTruncated);
        return new Outcome(
                outcome,
                new Inverse(
                        ACTIVITY,
                        created.getId(),
                        AiAssistantWriteTool.fingerprint(objectMapper, activityState(created)),
                        true,
                        Map.of()),
                new ReadBack(
                        target.kind() + "Id",
                        target.id(),
                        linkedId(created, target.kind())));
    }

    @Override
    public void undo(Authority authority, Inverse inverse) {
        if (!ACTIVITY.equals(inverse.entityKind())) {
            throw new ConflictException("Assistant tool undo metadata is invalid");
        }
        activityService.deleteIf(
                inverse.entityId(),
                current -> inverse.fingerprint().equals(
                        AiAssistantWriteTool.fingerprint(objectMapper, activityState(current))));
    }

    @Override
    public boolean inverseAvailable() {
        return true;
    }

    @Override
    public Set<ReviewInput> reviewInputs() {
        return Set.of();
    }

    @Override
    public Diff diff(Review review) {
        return null;
    }

    @Override
    public String requestSummary(Review review) {
        return "Create an activity";
    }

    @Override
    public String outcomeSummary(Review review) {
        return "Activity created";
    }

    /**
     * Reports the logged activity and, for a meeting, only the count of the conflicts found and
     * whether that list was cut short — never the conflicting activities themselves.
     */
    @Override
    public Map<String, Object> modelOutcome(JsonNode storedOutcome) {
        Map<String, Object> result = new LinkedHashMap<>();
        AiAssistantWriteTool.copyText(storedOutcome, result, "recordType");
        AiAssistantWriteTool.copyText(storedOutcome, result, "type");
        AiAssistantWriteTool.copyText(storedOutcome, result, "subject");
        AiAssistantWriteTool.copyText(storedOutcome, result, "start");
        AiAssistantWriteTool.copyText(storedOutcome, result, "timezone");
        result.put("conflictCount", storedOutcome.path("conflicts").size());
        result.put("conflictsTruncated", storedOutcome.path("conflictsTruncated").asBoolean());
        return result;
    }

    @Override
    public List<String> memberOutcomeFields() {
        return List.of("type", "subject", "start");
    }

    private static void link(Activity activity, Target target) {
        if ("person".equals(target.kind())) {
            Person person = new Person();
            person.setId(target.id());
            activity.setPerson(person);
        } else {
            Deal deal = new Deal();
            deal.setId(target.id());
            activity.setDeal(deal);
        }
    }

    /** The record the created activity is linked to, as the activity service returned it. */
    private static Integer linkedId(Activity activity, String targetKind) {
        if ("person".equals(targetKind)) {
            return activity.getPerson() == null ? null : activity.getPerson().getId();
        }
        return activity.getDeal() == null ? null : activity.getDeal().getId();
    }

    private static Map<String, Object> activityState(Activity activity) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("type", activity.getType());
        state.put("subject", activity.getSubject());
        state.put("notes", activity.getNotes());
        state.put("timestamp", activity.getTimestamp());
        state.put("personId", activity.getPerson() == null ? 0 : activity.getPerson().getId());
        state.put("dealId", activity.getDeal() == null ? 0 : activity.getDeal().getId());
        return state;
    }
}
