package ooo.klae.connex.backend.ai.assistant;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.assistant.AiAssistantToolCatalog.ToolTier;
import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteTool.Diff;
import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteTool.DiffState;
import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteTool.RecordSnapshot;
import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteTool.Review;
import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteTool.ReviewInput;
import ooo.klae.connex.backend.ai.masking.SpecialCareTextScreen;
import ooo.klae.connex.backend.ai.provider.AiProviderCapabilities;
import ooo.klae.connex.backend.beans.AiChatMessage;
import ooo.klae.connex.backend.beans.AiChatSession;
import ooo.klae.connex.backend.beans.AiChatToolCall;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.AiAssistantToolCallReadDto;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.ActivityMapper;
import ooo.klae.connex.backend.mappers.AiChatMapper;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.NoteMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.PipelineMapper;
import ooo.klae.connex.backend.mappers.TaskMapper;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Projects durable assistant write-tool state through the current viewer's live authority. */
@Service
@RequiredArgsConstructor
public class AiAssistantToolCallReadService {
    private static final int MAX_TOOL_CALLS = 100;
    private static final int MAX_OUTCOME_VALUES = 4;
    private static final String ACTIVE = "active";
    private static final String PROPOSED = "proposed";
    private static final String EXECUTED = "executed";
    private static final String UNASSIGNED = "unassigned";
    private static final String OWNER_FIELD = "owner";
    private static final Set<String> CREATED_RECORD_KINDS = Set.of("activity", "task", "note");
    /**
     * One turn's tool-call idempotency key, with the optional ordinal a shared step renders.
     *
     * <p>Anchored and still strict: a malformed key is rejected exactly as before, the step number
     * stays bounded by the loop's own backstop and the ordinal by the per-step call ceiling, so a
     * key claiming a position no step could have produced is refused rather than read. An executed
     * or proposed write is always the sole call of its step and never carries the suffix. A write
     * refused whole with its batch does — its failed row is keyed by its ordinal — but it holds the
     * model's raw arguments rather than a prepared write's tool, tier and target, so the stored-row
     * checks drop it however its key parses. The suffix group exists so a suffixed row parses
     * instead of being dropped silently for its key alone.
     */
    private static final Pattern TURN_STEP_KEY = Pattern.compile(
            "^turn-([1-9][0-9]*)-step-([1-9][0-9]*)(?:-call-([1-9][0-9]*))?$");

    private final AiAssistantToolCatalog toolCatalog;
    private final AiAssistantWriteToolRegistry writeToolRegistry;
    private final AiChatMapper chatMapper;
    private final WorkspaceService workspaceService;
    private final PersonMapper personMapper;
    private final CompanyMapper companyMapper;
    private final DealMapper dealMapper;
    private final PipelineMapper pipelineMapper;
    private final ActivityMapper activityMapper;
    private final TaskMapper taskMapper;
    private final NoteMapper noteMapper;
    private final AiAssistantSessionReadAudit sessionReadAudit;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /** Returns up to 100 safe write-tool cards in one authorized session. */
    @Transactional
    public List<AiAssistantToolCallReadDto> list(int sessionId, boolean pendingOnly) {
        Viewer viewer = currentViewer();
        AiChatSession session = requireReadableSession(viewer, sessionId);
        return list(viewer, session, pendingOnly, true);
    }

    /** Returns up to 100 safe write-tool cards for one retained administrative read. */
    @Transactional
    @RequirePermission(Permission.AI_SESSION_ADMIN)
    public List<AiAssistantToolCallReadDto> listRetained(
            int sessionId, boolean pendingOnly) {
        Viewer viewer = currentViewer();
        AiChatSession session = requireRetainedSession(viewer, sessionId);
        List<AiAssistantToolCallReadDto> projected = list(
                viewer, session, pendingOnly, false);
        sessionReadAudit.record(sessionId, "retained");
        return projected;
    }

    /** Returns one safe write-tool card in an authorized session. */
    @Transactional
    public AiAssistantToolCallReadDto get(int sessionId, int toolCallId) {
        Viewer viewer = currentViewer();
        AiChatSession session = requireReadableSession(viewer, sessionId);
        return get(viewer, session, toolCallId, true);
    }

    /** Returns one safe write-tool card for a retained administrative read. */
    @Transactional
    @RequirePermission(Permission.AI_SESSION_ADMIN)
    public AiAssistantToolCallReadDto getRetained(int sessionId, int toolCallId) {
        Viewer viewer = currentViewer();
        AiChatSession session = requireRetainedSession(viewer, sessionId);
        AiAssistantToolCallReadDto projected = get(viewer, session, toolCallId, false);
        sessionReadAudit.record(sessionId, "retained");
        return projected;
    }

    private List<AiAssistantToolCallReadDto> list(
            Viewer viewer,
            AiChatSession session,
            boolean pendingOnly,
            boolean mutationsAvailable) {
        List<StoredToolCall> stored = chatMapper.listToolCallsBySession(
                viewer.workspaceId(), session.getId(), pendingOnly, MAX_TOOL_CALLS).stream()
                .map(this::readStored)
                .filter(Objects::nonNull)
                .filter(call -> !pendingOnly || call.tier() == ToolTier.CONFIRM)
                .toList();
        return project(viewer, session, stored, mutationsAvailable);
    }

    private AiAssistantToolCallReadDto get(
            Viewer viewer,
            AiChatSession session,
            int toolCallId,
            boolean mutationsAvailable) {
        AiChatToolCall toolCall = chatMapper.getToolCallBySession(
                viewer.workspaceId(), session.getId(), toolCallId);
        StoredToolCall stored = toolCall == null ? null : readStored(toolCall);
        if (stored == null) {
            throw inaccessible();
        }
        List<AiAssistantToolCallReadDto> projected = project(
                viewer, session, List.of(stored), mutationsAvailable);
        if (projected.isEmpty()) {
            throw inaccessible();
        }
        return projected.getFirst();
    }

    private List<AiAssistantToolCallReadDto> project(
            Viewer viewer,
            AiChatSession session,
            List<StoredToolCall> stored,
            boolean mutationsAvailable) {
        Set<Permission> viewerPermissions = workspaceService.permissionsFor(
                viewer.workspaceId(), viewer.userId());
        Map<RecordKey, RecordSnapshot> visibleTargets = visibleTargets(
                viewer.workspaceId(), stored);
        List<User> assignableOwners = stored.stream().anyMatch(call ->
                readsInput(call, ReviewInput.MEMBERS)
                        && detailsReadable(call, viewer.userId(), visibleTargets))
                ? workspaceService.getMembers(viewer.workspaceId())
                : List.of();
        List<Stage> stages = stored.stream().anyMatch(call ->
                readsInput(call, ReviewInput.STAGES)
                        && detailsReadable(call, viewer.userId(), visibleTargets))
                ? pipelineMapper.getAllStages(viewer.workspaceId())
                : List.of();
        Map<Integer, Integer> assistantMessages = assistantMessages(
                viewer.workspaceId(), session.getId(), stored);
        Map<Integer, AiAssistantToolCallReadDto.CreatedRecord> createdRecords = liveCreatedRecords(
                viewer, stored, visibleTargets);
        boolean undoAvailable = mutationsAvailable && ACTIVE.equals(session.getStatus());
        List<AiAssistantToolCallReadDto> projected = new ArrayList<>();
        for (StoredToolCall call : stored) {
            String status = publicStatus(call.toolCall());
            if (status == null) {
                continue;
            }
            UndoProjection undo = undoProjection(
                    call, status, viewer.userId(), viewerPermissions, undoAvailable);
            if (undo.undone()) {
                status = "undone";
            }
            RecordKey targetKey = new RecordKey(call.targetKind(), call.targetId());
            RecordSnapshot visibleTarget = visibleTargets.get(targetKey);
            AiAssistantToolCallReadDto.Target target = visibleTarget == null
                    ? new AiAssistantToolCallReadDto.Target(call.targetKind(), null, null)
                    : new AiAssistantToolCallReadDto.Target(
                            call.targetKind(), call.targetId(), visibleTarget.label());
            Integer messageId = assistantMessages.get(call.turnId());
            boolean readable = detailsReadable(call, viewer.userId(), visibleTargets);
            Optional<AiAssistantWriteTool> declared =
                    writeToolRegistry.find(call.toolCall().getToolName());
            Review review = declared.isPresent()
                    ? review(declared.get(), call, status, readable, visibleTarget,
                            assignableOwners, stages, viewerPermissions)
                    : null;
            projected.add(new AiAssistantToolCallReadDto(
                    call.toolCall().getId(),
                    call.toolCall().getToolName(),
                    call.tier().name().toLowerCase(),
                    status,
                    target,
                    declared.isPresent()
                            ? summary(review, declared.get()::requestSummary)
                            : requestSummary(call, readable, assignableOwners),
                    outcomeSummary(call, status, declared, review),
                    readable
                            ? change(
                                    call, status, visibleTarget, assignableOwners,
                                    viewerPermissions, declared, review)
                            : null,
                    readable
                            ? outcomeValues(call, status)
                            : List.of(),
                    readable && EXECUTED.equals(status)
                            ? createdRecords.get(call.toolCall().getId())
                            : null,
                    messageId,
                    call.turnId(),
                    undo.expiresAt(),
                    undo.available(),
                    call.toolCall().getCreatedAt(),
                    call.toolCall().getUpdatedAt(),
                    call.toolCall().getExecutedAt()));
        }
        return List.copyOf(projected);
    }

    /**
     * Whether this viewer may read one proposal's own record values.
     *
     * <p>Both halves are load-bearing, and they are asserted here and nowhere else: the viewer must
     * be the member who asked for the proposal, so a shared session's other participants read the
     * request without learning the record behind it, and the target must be one this workspace can
     * currently show them. Everything carrying a record value — the resolved request summary, the
     * before and after values, the values a completed action wrote, and the record it created —
     * passes through this one predicate, because three copies of an agreeing rule is one edit away
     * from a silent leak.
     */
    private static boolean detailsReadable(
            StoredToolCall call,
            int viewerUserId,
            Map<RecordKey, RecordSnapshot> visibleTargets) {
        return visibleTargets.get(new RecordKey(call.targetKind(), call.targetId())) != null
                && Objects.equals(call.toolCall().getRequestedByUserId(), viewerUserId);
    }

    private StoredToolCall readStored(AiChatToolCall toolCall) {
        if (toolCall.getArgumentsJson() == null) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(toolCall.getArgumentsJson());
            if (root == null || !root.isObject()) {
                return null;
            }
            String toolName = text(root, "tool");
            ToolTier tier = ToolTier.valueOf(text(root, "tier").toUpperCase(Locale.ROOT));
            JsonNode target = root.get("target");
            String targetKind = text(target, "kind");
            int targetId = positiveInteger(target, "id");
            String requestValue = switch (toolName) {
                case "assign_owner" -> text(root.get("request"), "owner");
                case "change_deal_stage" -> text(root.get("request"), "stage");
                default -> null;
            };
            int turnId = turnId(toolCall.getIdempotencyKey());
            if (!toolCall.getToolName().equals(toolName)
                    || !toolCatalog.isExecutable(toolName)
                    || !toolCatalog.isWrite(toolName)
                    || tier != toolCatalog.tier(toolName)
                    || (tier != ToolTier.AUTO && tier != ToolTier.CONFIRM)
                    || !acceptsTarget(toolName, targetKind)) {
                return null;
            }
            return new StoredToolCall(
                    toolCall, tier, targetKind, targetId, turnId, requestValue,
                    root.get("request"));
        } catch (JacksonException | IllegalArgumentException exception) {
            return null;
        }
    }

    private Map<RecordKey, RecordSnapshot> visibleTargets(
            int workspaceId, List<StoredToolCall> stored) {
        Set<RecordKey> requested = new LinkedHashSet<>();
        stored.stream()
                .map(call -> new RecordKey(call.targetKind(), call.targetId()))
                .forEach(requested::add);
        Map<RecordKey, RecordSnapshot> visible = new LinkedHashMap<>();
        List<Integer> personIds = ids(requested, "person");
        List<Integer> companyIds = ids(requested, "company");
        List<Integer> dealIds = ids(requested, "deal");
        if (!personIds.isEmpty()) {
            for (Person person : personMapper.getByIds(workspaceId, personIds)) {
                if (isProcessable(person)) {
                    putVisible(visible, "person", person.getId(), new RecordSnapshot(
                            person.getName(), null, person.getOwnerId(), null,
                            person.getUpdatedAt()));
                }
            }
        }
        if (!companyIds.isEmpty()) {
            for (Company company : companyMapper.getByIds(workspaceId, companyIds)) {
                putVisible(visible, "company", company.getId(), new RecordSnapshot(
                        company.getName(), null, company.getOwnerId(), null,
                        company.getUpdatedAt()));
            }
        }
        if (!dealIds.isEmpty()) {
            for (Deal deal : dealMapper.getByIds(workspaceId, dealIds)) {
                putVisible(visible, "deal", deal.getId(), new RecordSnapshot(
                        deal.getName(), deal.getPipelineId(), deal.getOwnerId(),
                        deal.getStageId(), deal.getUpdatedAt()));
            }
        }
        return Map.copyOf(visible);
    }

    private Map<Integer, Integer> assistantMessages(
            int workspaceId, int sessionId, List<StoredToolCall> stored) {
        List<Integer> turnIds = stored.stream()
                .map(StoredToolCall::turnId)
                .distinct()
                .toList();
        if (turnIds.isEmpty()) {
            return Map.of();
        }
        Map<Integer, Integer> messages = new LinkedHashMap<>();
        for (AiChatMessage message : chatMapper.listAssistantMessagesBySessionAndTurnIds(
                workspaceId, sessionId, turnIds, MAX_TOOL_CALLS)) {
            Integer turnId = assistantTurnId(message.getStructuredJson());
            if (turnId != null) {
                messages.putIfAbsent(turnId, message.getId());
            }
        }
        return Map.copyOf(messages);
    }

    private Integer assistantTurnId(String structuredJson) {
        if (structuredJson == null) {
            return null;
        }
        try {
            JsonNode metadata = objectMapper.readTree(structuredJson);
            JsonNode turnId = metadata == null ? null : metadata.get("turnId");
            if (turnId == null || !turnId.isIntegralNumber()
                    || !turnId.canConvertToInt() || turnId.asInt() <= 0) {
                return null;
            }
            return turnId.asInt();
        } catch (JacksonException exception) {
            return null;
        }
    }

    private UndoProjection undoProjection(
            StoredToolCall call,
            String status,
            int viewerUserId,
            Set<Permission> viewerPermissions,
            boolean mutationsAvailable) {
        if (call.tier() != ToolTier.AUTO
                || !"executed".equals(status)
                || call.toolCall().getResultJson() == null) {
            return UndoProjection.NONE;
        }
        try {
            JsonNode result = objectMapper.readTree(call.toolCall().getResultJson());
            JsonNode undo = result == null ? null : result.get("undo");
            if (undo == null || !undo.isObject()) {
                return UndoProjection.NONE;
            }
            JsonNode undoStatus = undo.get("status");
            if (undoStatus == null || !undoStatus.isString()) {
                return UndoProjection.NONE;
            }
            if ("undone".equals(undoStatus.asString())) {
                return new UndoProjection(false, canonicalInstant(undo.get("expiresAt")), true);
            }
            if (!"available".equals(undoStatus.asString())) {
                return new UndoProjection(false, canonicalInstant(undo.get("expiresAt")), false);
            }
            String expiresAt = canonicalInstant(undo.get("expiresAt"));
            boolean available = mutationsAvailable
                    && expiresAt != null
                    && Objects.equals(call.toolCall().getRequestedByUserId(), viewerUserId)
                    && hasUndoPermissions(call, viewerPermissions)
                    && !clock.instant().isAfter(Instant.parse(expiresAt));
            return new UndoProjection(available, expiresAt, false);
        } catch (JacksonException exception) {
            return UndoProjection.NONE;
        }
    }

    private static String canonicalInstant(JsonNode value) {
        if (value == null || !value.isString()) {
            return null;
        }
        try {
            return Instant.parse(value.asString()).toString();
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    /**
     * Whether the viewer could apply this call's inverse.
     *
     * <p>A declared tool is offered undo only when it can ever be undone and the viewer holds the
     * permissions its write requires on that target — the same set the undo itself asserts. A tool
     * that records an inverse it cannot apply, such as a tag association it may not have created,
     * is therefore never offered one.
     */
    private boolean hasUndoPermissions(StoredToolCall call, Set<Permission> viewerPermissions) {
        Optional<AiAssistantWriteTool> declared =
                writeToolRegistry.find(call.toolCall().getToolName());
        if (declared.isPresent()) {
            return declared.get().inverseAvailable()
                    && viewerPermissions.containsAll(
                            declared.get().requiredPermissions(call.targetKind()));
        }
        return switch (call.toolCall().getToolName()) {
            case "create_activity" -> viewerPermissions.containsAll(
                    Set.of(Permission.ACTIVITY_CREATE, Permission.ACTIVITY_DELETE));
            case "create_note" -> viewerPermissions.containsAll(
                    Set.of(Permission.NOTE_CREATE, Permission.NOTE_DELETE));
            default -> false;
        };
    }

    private static String publicStatus(AiChatToolCall toolCall) {
        return switch (toolCall.getStatus()) {
            case "proposed", "executed", "rejected", "failed" -> toolCall.getStatus();
            default -> null;
        };
    }

    private static String requestSummary(
            StoredToolCall call,
            boolean detailsReadable,
            List<User> assignableOwners) {
        String toolName = call.toolCall().getToolName();
        if (detailsReadable) {
            String resolved = switch (toolName) {
                case "assign_owner" -> ownerSummary(call.requestValue(), assignableOwners);
                default -> null;
            };
            if (resolved != null) {
                return resolved;
            }
        }
        return switch (toolName) {
            case "create_activity" -> "Create an activity";
            case "create_note" -> "Create a note";
            case "add_tag" -> "Add an existing tag";
            case "assign_owner" -> "Assign an owner";
            default -> "Run a write tool";
        };
    }

    private static String ownerSummary(String requestedOwner, List<User> assignableOwners) {
        if (UNASSIGNED.equalsIgnoreCase(requestedOwner.trim())) {
            return "Remove the current owner";
        }
        String label = requestedOwnerName(requestedOwner, assignableOwners);
        return label == null ? null : "Assign owner: " + label;
    }

    private static String requestedOwnerName(
            String requestedOwner, List<User> assignableOwners) {
        User matched = requestedOwner(requestedOwner, assignableOwners);
        return matched == null ? null : memberName(matched);
    }

    private static User requestedOwner(String requestedOwner, List<User> assignableOwners) {
        String normalizedOwner = requestedOwner.trim();
        List<User> matches = assignableOwners.stream()
                .filter(user -> user.getDisplayName() != null
                        && user.getDisplayName().equalsIgnoreCase(normalizedOwner)
                        || user.getUsername() != null
                        && user.getUsername().equalsIgnoreCase(normalizedOwner))
                .toList();
        return matches.size() == 1 ? matches.getFirst() : null;
    }

    private String outcomeSummary(
            StoredToolCall call,
            String status,
            Optional<AiAssistantWriteTool> declared,
            Review review) {
        AiChatToolCall toolCall = call.toolCall();
        return switch (status) {
            case "proposed" -> null;
            case "rejected" -> "Request rejected";
            case "failed" -> "Request failed";
            case "undone" -> "Created record removed";
            case "executed" -> declared.isPresent()
                    ? summary(review, declared.get()::outcomeSummary)
                    : switch (toolCall.getToolName()) {
                case "create_activity" -> "Activity created";
                case "create_note" -> "Note created";
                case "add_tag" -> addTagOutcomeSummary(toolCall.getResultJson());
                case "assign_owner" -> "unassigned".equalsIgnoreCase(call.requestValue().trim())
                        ? "Owner removed"
                        : "Owner assigned";
                default -> "Request completed";
            };
            default -> null;
        };
    }

    /**
     * The exact before and after values one pending proposal would write, or null when there is no
     * such change to state.
     *
     * <p>Every value here is workspace record data resolved server-side, never a value the model
     * chose: the proposed owner and stage are matched against the workspace's own members and
     * stages, and an unmatched one is reported as unresolved rather than echoed back. The caller has
     * already established that the viewer requested this proposal and can currently read its target,
     * which is what keeps a before-value out of a shared participant's transcript.
     */
    private AiAssistantToolCallReadDto.Change change(
            StoredToolCall call,
            String status,
            RecordSnapshot target,
            List<User> assignableOwners,
            Set<Permission> viewerPermissions,
            Optional<AiAssistantWriteTool> declared,
            Review review) {
        if (call.tier() != ToolTier.CONFIRM || !PROPOSED.equals(status)) {
            return null;
        }
        if (declared.isPresent()) {
            Diff diff = declared.get().diff(review);
            if (diff == null) {
                return null;
            }
            return new AiAssistantToolCallReadDto.Change(
                    diff.field(),
                    diff.currentValue(),
                    diff.currentValueUnresolved(),
                    diff.proposedValue(),
                    diff.state() == DiffState.UNRESOLVED
                            ? "unresolved"
                            : changeState(
                                    call, target, diff.state() == DiffState.UNCHANGED,
                                    viewerPermissions,
                                    declared.get().requiredPermissions(call.targetKind())));
        }
        return switch (call.toolCall().getToolName()) {
            case "assign_owner" -> ownerChange(
                    call, target, assignableOwners, viewerPermissions);
            default -> null;
        };
    }

    /**
     * The batched, viewer-authorized read state a declared tool projects one card from.
     *
     * <p>{@link #detailsReadable} is applied here, for every declared tool, rather than trusted to
     * each tool: a viewer who may not read the details gets a review holding no target, request,
     * outcome, members or stages, so no summary a tool writes can carry a record value to them.
     * Otherwise the target snapshot is present while the viewer can currently see it, the stored
     * outcome only for an executed call, and the members and stages only when the tool declared
     * them; the tool never reads anything this service did not already load for the page of cards.
     */
    private Review review(
            AiAssistantWriteTool tool,
            StoredToolCall call,
            String status,
            boolean readable,
            RecordSnapshot target,
            List<User> assignableOwners,
            List<Stage> stages,
            Set<Permission> viewerPermissions) {
        if (!readable) {
            return withheld(call.targetKind(), call.targetId(), viewerPermissions);
        }
        Set<ReviewInput> inputs = tool.reviewInputs();
        return new Review(
                call.targetKind(),
                call.targetId(),
                true,
                target,
                call.request(),
                EXECUTED.equals(status) ? storedOutcome(call.toolCall()) : null,
                inputs.contains(ReviewInput.MEMBERS) ? assignableOwners : List.of(),
                inputs.contains(ReviewInput.STAGES) ? stages : List.of(),
                viewerPermissions);
    }

    private static Review withheld(
            String targetKind, int targetId, Set<Permission> viewerPermissions) {
        return new Review(
                targetKind, targetId, false, null, null, null, List.of(), List.of(),
                viewerPermissions);
    }

    /**
     * A declared tool's summary, screened like every other member-visible value.
     *
     * <p>A detailed summary the special-care screen excludes, or one the tool declines to give, is
     * replaced by the summary the tool gives a viewer who may not read the details.
     */
    private static String summary(Review review, Function<Review, String> summarize) {
        String summary = summarize.apply(review);
        if (review.detailsReadable()
                && (summary == null || SpecialCareTextScreen.screen(summary).excluded())) {
            return summarize.apply(withheld(
                    review.targetKind(), review.targetId(), review.viewerPermissions()));
        }
        return summary;
    }

    /**
     * Whether one card's projection reads a batched input: a declared tool that asked for it, or
     * the legacy owner arm, which reads the member list.
     */
    private boolean readsInput(StoredToolCall call, ReviewInput input) {
        String toolName = call.toolCall().getToolName();
        Optional<AiAssistantWriteTool> declared = writeToolRegistry.find(toolName);
        if (declared.isPresent()) {
            return declared.get().reviewInputs().contains(input);
        }
        return input == ReviewInput.MEMBERS && "assign_owner".equals(toolName);
    }

    private JsonNode storedOutcome(AiChatToolCall toolCall) {
        if (toolCall.getResultJson() == null) {
            return null;
        }
        try {
            JsonNode result = objectMapper.readTree(toolCall.getResultJson());
            JsonNode outcome = result == null ? null : result.get("outcome");
            return outcome != null && outcome.isObject() ? outcome : null;
        } catch (JacksonException exception) {
            return null;
        }
    }

    /**
     * An owner proposal's before and after values, compared as the record itself stores them.
     *
     * <p>Whether the change would do anything is decided on owner ids, never on the names this
     * workspace can print for them. A record owned by someone who has left cannot be named here, and
     * comparing that absent name against an unassign proposal's absent name would call a real
     * removal "already the current value" and withhold the control — refusing exactly the cleanup
     * the member came to do. The name is for reading; the id is what decides.
     */
    private AiAssistantToolCallReadDto.Change ownerChange(
            StoredToolCall call,
            RecordSnapshot target,
            List<User> assignableOwners,
            Set<Permission> viewerPermissions) {
        String current = currentOwnerName(assignableOwners, target.ownerId());
        boolean currentUnresolved = target.ownerId() != null && current == null;
        String requested = call.requestValue().trim();
        if (UNASSIGNED.equalsIgnoreCase(requested)) {
            return new AiAssistantToolCallReadDto.Change(
                    OWNER_FIELD, current, currentUnresolved, null,
                    changeState(
                            call, target, target.ownerId() == null, viewerPermissions,
                            updatePermissions(call.targetKind())));
        }
        User proposed = requestedOwner(requested, assignableOwners);
        String proposedName = proposed == null ? null : memberName(proposed);
        if (proposedName == null) {
            return new AiAssistantToolCallReadDto.Change(
                    OWNER_FIELD, current, currentUnresolved, null, "unresolved");
        }
        return new AiAssistantToolCallReadDto.Change(
                OWNER_FIELD, current, currentUnresolved, proposedName,
                changeState(
                        call,
                        target,
                        target.ownerId() != null && target.ownerId() == proposed.getId(),
                        viewerPermissions,
                        updatePermissions(call.targetKind())));
    }

    /**
     * Whether a reviewed change can still be applied as reviewed.
     *
     * <p>Approval revalidates permissions, membership, restrictions, and locked record state at
     * execution time and refuses on its own terms; this states the same conclusions early, so a
     * lost permission, a change that would now do nothing, or a record edited since the proposal
     * was made is read <em>before</em> pressing apply rather than after being refused. A record
     * that moved is a refusal, not a caution: approval will not re-baseline a proposal onto values
     * it was never reviewed against, so the member asks for the change again. Timestamps that
     * cannot be read are never reported as a change, because claiming a record moved when nothing
     * established that would hold back a change that is perfectly applicable.
     *
     * @param unchanged whether the record already holds the proposed value, decided by the callers
     *     on the ids the record stores rather than on the names this workspace can print for them
     * @param required the permissions the approval itself asserts: a declared tool's
     *     {@code requiredPermissions} for the target kind, or the target's update permission
     */
    private String changeState(
            StoredToolCall call,
            RecordSnapshot target,
            boolean unchanged,
            Set<Permission> viewerPermissions,
            Set<Permission> required) {
        if (required.isEmpty() || !viewerPermissions.containsAll(required)) {
            return "permissionLost";
        }
        if (unchanged) {
            return "unchanged";
        }
        return AiAssistantProposalFreshness.changedSince(
                target.updatedAt(), call.toolCall().getCreatedAt())
                ? "recordChanged"
                : "ready";
    }

    private static Set<Permission> updatePermissions(String kind) {
        return switch (kind) {
            case "person" -> Set.of(Permission.PERSON_UPDATE);
            case "company" -> Set.of(Permission.COMPANY_UPDATE);
            case "deal" -> Set.of(Permission.DEAL_UPDATE);
            default -> Set.of();
        };
    }

    /**
     * The record's owner as this workspace can currently name them.
     *
     * <p>A record owned by someone who is no longer an active member has no name to state here, and
     * is reported the same way an unowned record is: nothing is claimed about a person the
     * workspace's own member list no longer contains.
     */
    private static String currentOwnerName(List<User> members, Integer userId) {
        if (userId == null) {
            return null;
        }
        for (User member : members) {
            if (member.getId() == userId) {
                return memberName(member);
            }
        }
        return null;
    }

    private static String memberName(User member) {
        String label = member.getDisplayName() == null || member.getDisplayName().isBlank()
                ? member.getUsername()
                : member.getDisplayName();
        return label == null || label.isBlank() ? null : label;
    }

    /**
     * The values a completed action actually wrote, named by field so the client states them in the
     * member's own language.
     *
     * <p>Read through a per-tool allowlist rather than by copying the stored outcome, because that
     * envelope also carries private record content and undo metadata that no card may render. Free
     * text the model authored is additionally screened and dropped when the screen excludes it, on
     * the same rule the answer document follows.
     */
    private List<AiAssistantToolCallReadDto.OutcomeValue> outcomeValues(
            StoredToolCall call, String status) {
        if (!EXECUTED.equals(status) || call.toolCall().getResultJson() == null) {
            return List.of();
        }
        JsonNode outcome;
        try {
            JsonNode result = objectMapper.readTree(call.toolCall().getResultJson());
            outcome = result == null ? null : result.get("outcome");
        } catch (JacksonException exception) {
            return List.of();
        }
        if (outcome == null || !outcome.isObject()) {
            return List.of();
        }
        List<AiAssistantToolCallReadDto.OutcomeValue> values = new ArrayList<>();
        for (String field : outcomeFields(call.toolCall().getToolName())) {
            JsonNode value = outcome.get(field);
            if (value == null || !value.isString()) {
                continue;
            }
            String text = value.asString().strip();
            if (text.isEmpty() || SpecialCareTextScreen.screen(text).excluded()) {
                continue;
            }
            values.add(new AiAssistantToolCallReadDto.OutcomeValue(field, text));
            if (values.size() == MAX_OUTCOME_VALUES) {
                break;
            }
        }
        return List.copyOf(values);
    }

    /**
     * The records completed actions created and this workspace still holds, by tool-call id.
     *
     * <p>The durable inverse states what was created, but it is a record of the past: an activity,
     * task, or note is deletable through its own controller long after the undo window closed, and
     * the tool call stays {@code executed} either way. Offering "open the task" over a deleted task
     * is a link to a not-found page, so the inverse is resolved against the workspace's live rows
     * and a created record that is gone is reported as absent — the card then names the record the
     * action was about instead, which is the link that still resolves.
     *
     * <p>Resolution is batched per kind, so a transcript's worth of cards costs one query per kind
     * rather than one per card, and it is scoped to this viewer: a note they may not read is not a
     * record this card offers to open.
     */
    private Map<Integer, AiAssistantToolCallReadDto.CreatedRecord> liveCreatedRecords(
            Viewer viewer,
            List<StoredToolCall> stored,
            Map<RecordKey, RecordSnapshot> visibleTargets) {
        Map<Integer, AiAssistantToolCallReadDto.CreatedRecord> candidates = new LinkedHashMap<>();
        for (StoredToolCall call : stored) {
            if (!detailsReadable(call, viewer.userId(), visibleTargets)) {
                continue;
            }
            AiAssistantToolCallReadDto.CreatedRecord candidate = createdRecord(
                    call, publicStatus(call.toolCall()));
            if (candidate != null) {
                candidates.put(call.toolCall().getId(), candidate);
            }
        }
        if (candidates.isEmpty()) {
            return Map.of();
        }
        Set<RecordKey> live = liveCreatedRecordKeys(viewer, candidates.values());
        candidates.values().removeIf(candidate ->
                !live.contains(new RecordKey(candidate.kind(), candidate.id())));
        return Map.copyOf(candidates);
    }

    private Set<RecordKey> liveCreatedRecordKeys(
            Viewer viewer, Collection<AiAssistantToolCallReadDto.CreatedRecord> candidates) {
        Set<RecordKey> live = new LinkedHashSet<>();
        List<Integer> activityIds = createdIds(candidates, "activity");
        List<Integer> taskIds = createdIds(candidates, "task");
        List<Integer> noteIds = createdIds(candidates, "note");
        if (!activityIds.isEmpty()) {
            for (Integer id : activityMapper.getVisibleIdsIn(viewer.workspaceId(), activityIds)) {
                live.add(new RecordKey("activity", id));
            }
        }
        if (!taskIds.isEmpty()) {
            for (Integer id : taskMapper.getVisibleIdsIn(viewer.workspaceId(), taskIds)) {
                live.add(new RecordKey("task", id));
            }
        }
        if (!noteIds.isEmpty()) {
            for (Integer id : noteMapper.getVisibleNoteIdsIn(
                    viewer.workspaceId(), noteIds, viewer.userId())) {
                live.add(new RecordKey("note", id));
            }
        }
        return live;
    }

    private static List<Integer> createdIds(
            Collection<AiAssistantToolCallReadDto.CreatedRecord> candidates, String kind) {
        return candidates.stream()
                .filter(candidate -> kind.equals(candidate.kind()))
                .map(AiAssistantToolCallReadDto.CreatedRecord::id)
                .distinct()
                .toList();
    }

    /**
     * The record one completed action's own inverse says it created.
     *
     * <p>Taken from the durable inverse the action recorded for itself, which is the workspace's own
     * statement of what was created rather than anything the model said. Only the kinds that inverse
     * actually creates are reported: a tag addition records the record it tagged, not a new record,
     * and reporting it here would send the member to a record they already had a link to. An
     * undone action reports nothing, because its record is gone.
     */
    private AiAssistantToolCallReadDto.CreatedRecord createdRecord(
            StoredToolCall call, String status) {
        if (!EXECUTED.equals(status) || call.toolCall().getResultJson() == null) {
            return null;
        }
        try {
            JsonNode result = objectMapper.readTree(call.toolCall().getResultJson());
            JsonNode undo = result == null ? null : result.get("undo");
            if (undo == null || !undo.isObject()) {
                return null;
            }
            JsonNode kind = undo.get("entityKind");
            JsonNode id = undo.get("entityId");
            if (kind == null || !kind.isString()
                    || !CREATED_RECORD_KINDS.contains(kind.asString())
                    || id == null || !id.isIntegralNumber()
                    || !id.canConvertToInt() || id.asInt() <= 0) {
                return null;
            }
            return new AiAssistantToolCallReadDto.CreatedRecord(kind.asString(), id.asInt());
        } catch (JacksonException exception) {
            return null;
        }
    }

    private List<String> outcomeFields(String toolName) {
        Optional<AiAssistantWriteTool> declared = writeToolRegistry.find(toolName);
        if (declared.isPresent()) {
            return declared.get().memberOutcomeFields();
        }
        return switch (toolName) {
            case "create_activity" -> List.of("type", "subject", "start");
            case "create_note" -> List.of("title", "visibility");
            case "add_tag" -> List.of("tag");
            case "assign_owner" -> List.of(OWNER_FIELD);
            default -> List.of();
        };
    }

    private String addTagOutcomeSummary(String resultJson) {
        if (resultJson == null) {
            return "Request completed";
        }
        try {
            JsonNode result = objectMapper.readTree(resultJson);
            JsonNode outcome = result == null ? null : result.get("outcome");
            JsonNode changed = outcome == null ? null : outcome.get("changed");
            if (changed == null || !changed.isBoolean()) {
                return "Request completed";
            }
            return changed.asBoolean() ? "Tag added" : "Tag was already present";
        } catch (JacksonException exception) {
            return "Request completed";
        }
    }

    private boolean acceptsTarget(String toolName, String kind) {
        Optional<AiAssistantWriteTool> declared = writeToolRegistry.find(toolName);
        if (declared.isPresent()) {
            return declared.get().acceptedTargetKinds().contains(kind);
        }
        return switch (toolName) {
            case "create_activity", "create_note" ->
                    "person".equals(kind) || "deal".equals(kind);
            case "add_tag", "assign_owner" ->
                    "person".equals(kind) || "company".equals(kind) || "deal".equals(kind);
            default -> false;
        };
    }

    private static int turnId(String idempotencyKey) {
        Matcher matcher = TURN_STEP_KEY.matcher(
                idempotencyKey == null ? "" : idempotencyKey);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Assistant tool association is invalid");
        }
        int turnId = Integer.parseInt(matcher.group(1));
        int stepNumber = Integer.parseInt(matcher.group(2));
        String callOrdinal = matcher.group(3);
        if (stepNumber > AiChatAgentLoopService.HARD_MAX_STEPS
                || (callOrdinal != null
                        && Integer.parseInt(callOrdinal)
                                > AiProviderCapabilities.MAX_PARALLEL_TOOL_CALLS)) {
            throw new IllegalArgumentException("Assistant tool association is invalid");
        }
        return turnId;
    }

    private static String text(JsonNode node, String name) {
        JsonNode value = node == null ? null : node.get(name);
        if (value == null || !value.isString() || value.asString().isBlank()) {
            throw new IllegalArgumentException("Assistant tool metadata is invalid");
        }
        return value.asString();
    }

    private static int positiveInteger(JsonNode node, String name) {
        JsonNode value = node == null ? null : node.get(name);
        if (value == null || !value.isIntegralNumber()
                || !value.canConvertToInt() || value.asInt() <= 0) {
            throw new IllegalArgumentException("Assistant tool metadata is invalid");
        }
        return value.asInt();
    }

    private static List<Integer> ids(Set<RecordKey> requested, String kind) {
        return requested.stream()
                .filter(record -> kind.equals(record.kind()))
                .map(RecordKey::id)
                .toList();
    }

    private static boolean isProcessable(Person person) {
        return person.getArchivedAt() == null
                && person.getSuspendedAt() == null
                && person.getProvisionCeasedAt() == null;
    }

    private static void putVisible(
            Map<RecordKey, RecordSnapshot> visible,
            String kind,
            int id,
            RecordSnapshot target) {
        if (target.label() != null && !target.label().isBlank()) {
            visible.put(new RecordKey(kind, id), target);
        }
    }

    private Viewer currentViewer() {
        return new Viewer(
                workspaceService.getCurrentWorkspaceId(), workspaceService.getCurrentUserId());
    }

    private AiChatSession requireReadableSession(Viewer viewer, int sessionId) {
        workspaceService.requirePermission(
                viewer.workspaceId(), viewer.userId(), Permission.AI_USE);
        AiChatSession session = chatMapper.getAccessibleSessionById(
                viewer.workspaceId(), viewer.userId(), sessionId);
        if (session == null) {
            throw inaccessible();
        }
        sessionReadAudit.recordAccessible(
                viewer.workspaceId(), viewer.userId(), session);
        return session;
    }

    private AiChatSession requireRetainedSession(Viewer viewer, int sessionId) {
        workspaceService.requirePermission(
                viewer.workspaceId(), viewer.userId(), Permission.AI_SESSION_ADMIN);
        List<Integer> activeMemberIds = activeMemberIds(viewer);
        AiChatSession session = chatMapper.getRetainedSessionById(
                viewer.workspaceId(), viewer.userId(), sessionId, activeMemberIds);
        if (session == null) {
            throw inaccessible();
        }
        List<Integer> revalidatedMemberIds = activeMemberIds(viewer);
        if (session.getCreatedByUserId() != null
                && revalidatedMemberIds.contains(session.getCreatedByUserId())) {
            throw inaccessible();
        }
        return session;
    }

    private List<Integer> activeMemberIds(Viewer viewer) {
        List<Integer> activeMemberIds = workspaceService.getMembers(viewer.workspaceId()).stream()
                .map(User::getId)
                .toList();
        if (!activeMemberIds.contains(viewer.userId())) {
            throw inaccessible();
        }
        return activeMemberIds;
    }

    private static ResourceNotFoundException inaccessible() {
        return new ResourceNotFoundException("AI assistant session is not accessible");
    }

    private record Viewer(int workspaceId, int userId) {
    }

    private record StoredToolCall(
            AiChatToolCall toolCall,
            ToolTier tier,
            String targetKind,
            int targetId,
            int turnId,
            String requestValue,
            JsonNode request) {
    }

    private record RecordKey(String kind, int id) {
    }

    private record UndoProjection(boolean available, String expiresAt, boolean undone) {
        private static final UndoProjection NONE = new UndoProjection(false, null, false);
    }
}
