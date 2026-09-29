package ooo.klae.connex.backend.ai.assistant;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.assistant.AiAssistantToolCatalog.ToolTier;
import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteToolRequest.SetResponseDue;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.services.LeadResponseSlaService;
import ooo.klae.connex.backend.tenant.Permission;
import tools.jackson.databind.JsonNode;

/**
 * Starts a first-response deadline on a contact, only after the member approves it.
 *
 * <p>Starting a first-response clock changes an existing record, and {@code docs/PRODUCT.md}
 * enumerates only the immediate writes an assistant may make; a deadline is not one of them, so
 * this tool is confirm-tier. It writes through
 * {@link LeadResponseSlaService#startFirstResponseClock}, the one method the workflow engine's
 * {@code set_response_due} action already calls, which asserts the contact update permission,
 * refuses a contact the workspace does not own or may no longer process, records its audit row and
 * reports whether it started a clock. The framework holds the
 * contact {@code FOR UPDATE}, refuses the approval if the contact was written after the proposal,
 * and runs the owner-scope gate before this tool calls it.
 *
 * <p>The deadline is counted in whole hours from the approval, not from the proposal, because that
 * is when the service starts the clock. It is a bounded integer the model works out from the
 * member's wording rather than a date or time it transcribes: a date or time the member typed with
 * seven or more digits is redacted before the model sees it, so a transcribed one could only be
 * the redaction marker or a guess, and an integer argument can carry neither. The framework's
 * redaction-marker refusal is not yet on {@code main}; this tool needs none, because the catalog
 * refuses any argument here that is not an integer.
 *
 * <p>A contact whose clock is already running, answered or breached is left exactly as it is: the
 * service never extends a running deadline. The card states that before the approval, from the
 * deadline the contact holds now, and the approval's outcome says whether a clock was started.
 *
 * <p>The read-back is {@link ReadBack#structural structural} and verifies nothing: the service
 * reports only whether it started a clock, never the deadline it wrote, and the permitted-method
 * allowlist grants this tool no read of the contact, so there is no identifier to compare.
 *
 * <p>The write records no inverse. A deadline is the record of how fast the workspace answered, and
 * withdrawing it would erase the start of the very clock the SLA exists to keep, so the card never
 * offers undo.
 */
@Component
@RequiredArgsConstructor
public class AiAssistantSetResponseDueWriteTool implements AiAssistantWriteTool {
    private static final String NAME = "set_response_due";
    private static final String RESPONSE_DUE_FIELD = "responseDue";
    private static final String DUE_IN_HOURS_REQUEST = "due_in_hours";
    private static final String DUE_IN_HOURS = "dueInHours";
    private static final String FIRST_RESPONSE_DUE_AT = "firstResponseDueAt";
    private static final String CHANGED = "changed";
    private static final String REQUEST_COMPLETED = "Request completed";
    private static final Set<String> TARGET_KINDS = Set.of("person");

    private final LeadResponseSlaService leadResponseSlaService;

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
        return SetResponseDue.class;
    }

    @Override
    public Set<String> acceptedTargetKinds() {
        return TARGET_KINDS;
    }

    @Override
    public Set<String> declaredWritableFields() {
        return Set.of("person.firstResponseDueAt", "person.firstResponseStartedAt");
    }

    @Override
    public Set<Permission> requiredPermissions(String targetKind) {
        if (!"person".equals(targetKind)) {
            throw new BadRequestException("Unsupported assistant record kind");
        }
        return Set.of(Permission.PERSON_UPDATE);
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
        if (!(execution.row().request() instanceof SetResponseDue request)) {
            throw new IllegalStateException("Assistant tool request is invalid");
        }
        Target target = execution.row().target();
        boolean changed = leadResponseSlaService.startFirstResponseClock(
                target.id(), request.dueInHours());
        Map<String, Object> outcome = new LinkedHashMap<>();
        outcome.put("status", "executed");
        outcome.put("recordType", target.kind());
        outcome.put(DUE_IN_HOURS, request.dueInHours());
        outcome.put(CHANGED, changed);
        return new Outcome(outcome, null, ReadBack.structural("personId", target.id()));
    }

    @Override
    public boolean inverseAvailable() {
        return false;
    }

    @Override
    public Set<String> sharedOutcomeFlags() {
        return Set.of(CHANGED);
    }

    @Override
    public Set<ReviewInput> reviewInputs() {
        return Set.of(ReviewInput.FIELDS);
    }

    /**
     * The deadline the contact holds now and the hours the proposal would give it.
     *
     * <p>The current value is the running clock's UTC deadline, or nothing when no clock runs. A
     * contact that already holds one would be left exactly as it is, so the change is unchanged and
     * the card offers no apply. The proposed value is the whole number of hours from the approval,
     * which the client states in the member's own words.
     */
    @Override
    public Diff diff(Review review) {
        Integer hours = requestedHours(review.request());
        if (hours == null) {
            return null;
        }
        String current = review.target() == null
                ? null
                : review.target().field(FIRST_RESPONSE_DUE_AT);
        return new Diff(
                RESPONSE_DUE_FIELD,
                current,
                false,
                Integer.toString(hours),
                current == null ? DiffState.CHANGED : DiffState.UNCHANGED);
    }

    @Override
    public String requestSummary(Review review) {
        Integer hours = review.detailsReadable() ? requestedHours(review.request()) : null;
        return hours == null
                ? "Set a first-response deadline"
                : "Set first-response deadline in hours: " + hours;
    }

    @Override
    public String outcomeSummary(Review review) {
        JsonNode changed = review.outcome() == null ? null : review.outcome().get(CHANGED);
        if (changed == null || !changed.isBoolean()) {
            return REQUEST_COMPLETED;
        }
        return changed.asBoolean()
                ? "First-response deadline set"
                : "A first-response deadline was already set";
    }

    @Override
    public Map<String, Object> modelOutcome(JsonNode storedOutcome) {
        Map<String, Object> result = new LinkedHashMap<>();
        AiAssistantWriteTool.copyText(storedOutcome, result, "recordType");
        JsonNode hours = storedOutcome.get(DUE_IN_HOURS);
        if (hours != null && hours.isInt()) {
            result.put(DUE_IN_HOURS, hours.intValue());
        }
        result.put(CHANGED, storedOutcome.path(CHANGED).asBoolean());
        return result;
    }

    @Override
    public List<String> memberOutcomeFields() {
        return List.of();
    }

    private static Integer requestedHours(JsonNode request) {
        JsonNode hours = request == null ? null : request.get(DUE_IN_HOURS_REQUEST);
        return hours != null && hours.isInt() && hours.intValue() > 0 ? hours.intValue() : null;
    }
}
