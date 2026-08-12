package ooo.klae.connex.backend.ai.assistant;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.AiRestrictionEpoch;
import ooo.klae.connex.backend.ai.assistant.AiAssistantDateResolver.ResolvedDateTime;
import ooo.klae.connex.backend.ai.assistant.AiAssistantToolCatalog.ToolTier;
import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteToolRequest.AddTag;
import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteToolRequest.AssignOwner;
import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteToolRequest.ChangeDealStage;
import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteToolRequest.CreateActivity;
import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteToolRequest.CreateNote;
import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteToolRequest.CreateTask;
import ooo.klae.connex.backend.ai.assistant.AiChatResourceRegistry.ResourceRef;
import ooo.klae.connex.backend.beans.Activity;
import ooo.klae.connex.backend.beans.AiChatSession;
import ooo.klae.connex.backend.beans.AiChatToolCall;
import ooo.klae.connex.backend.beans.AiChatTurn;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Note;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.Tag;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.AiAssistantToolCallDto;
import ooo.klae.connex.backend.dto.AiAssistantToolProposalDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.AiChatMapper;
import ooo.klae.connex.backend.services.ActivityService;
import ooo.klae.connex.backend.services.AiWorkspaceGovernanceService;
import ooo.klae.connex.backend.services.CompanyService;
import ooo.klae.connex.backend.services.DealService;
import ooo.klae.connex.backend.services.NoteService;
import ooo.klae.connex.backend.services.PersonService;
import ooo.klae.connex.backend.services.PipelineService;
import ooo.klae.connex.backend.services.TagService;
import ooo.klae.connex.backend.services.TaskService;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.tenant.Permission;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Executes validated assistant writes through native domain services and owns approval-safe replay. */
@Service
@RequiredArgsConstructor
public class AiAssistantWriteToolService {
    private static final String ACTIVE = "active";
    private static final String RUNNING = "running";
    private static final String PROPOSED = "proposed";
    private static final String EXECUTED = "executed";
    private static final String REJECTED = "rejected";
    private static final String SHARED = "shared";
    private static final Duration UNDO_WINDOW = Duration.ofMinutes(10);
    private static final int DEFAULT_MEETING_MINUTES = 60;

    private final AiAssistantToolCatalog toolCatalog;
    private final AiAssistantToolExecutor readToolExecutor;
    private final AiAssistantDateResolver dateResolver;
    private final AiChatMapper chatMapper;
    private final WorkspaceService workspaceService;
    private final ActivityService activityService;
    private final TaskService taskService;
    private final NoteService noteService;
    private final TagService tagService;
    private final PersonService personService;
    private final CompanyService companyService;
    private final DealService dealService;
    private final PipelineService pipelineService;
    private final AiRestrictionEpoch restrictionEpoch;
    private final AiAssistantAccessFence accessFence;
    private final AiWorkspaceGovernanceService governanceService;
    private final ObjectMapper objectMapper;
    private final Validator validator;
    private final Clock clock;

    /** Converts one provider tool call into a typed and server-resolved durable proposal. */
    public AiAssistantPreparedWrite prepare(
            String name,
            JsonNode args,
            AiChatResourceRegistry resources,
            long expectedRestrictionEpoch) {
        if (!toolCatalog.isWrite(name) || !toolCatalog.isExecutable(name)) {
            throw AiAssistantLoopException.malformed("unknown_write_tool");
        }
        readToolExecutor.validateReferences(name, args, resources);
        AiAssistantWriteToolRequest request = readRequest(name, args);
        ResourceRef target = resources.resolve(request.handle(), acceptedKinds(name));
        ObjectNode storedRequest = objectMapper.valueToTree(request);
        storedRequest.put("handle", "r1");
        Map<String, Object> targetData = new LinkedHashMap<>();
        targetData.put("kind", target.kind());
        targetData.put("id", target.id());
        Map<String, Object> durable = new LinkedHashMap<>();
        durable.put("tool", name);
        durable.put("tier", toolCatalog.tier(name).name().toLowerCase());
        durable.put("restrictionEpoch", expectedRestrictionEpoch);
        durable.put("target", targetData);
        durable.put("request", storedRequest);
        return new AiAssistantPreparedWrite(
                name,
                toolCatalog.tier(name),
                target.kind(),
                target.id(),
                serialize(durable));
    }

    /** Builds the model-visible replay or approval-required result for a durable proposal. */
    public AiAssistantToolResult proposalResult(
            AiAssistantPreparedWrite write, AiAssistantToolProposal proposal) {
        if (proposal.resultJson() != null && !proposal.resultJson().isBlank()) {
            AiChatToolCall toolCall = new AiChatToolCall();
            toolCall.setId(proposal.id());
            toolCall.setToolName(write.toolName());
            toolCall.setStatus(proposal.status());
            toolCall.setResultJson(proposal.resultJson());
            return execution(toolCall).toolResult();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("toolCallId", proposal.id());
        result.put("tool", write.toolName());
        result.put("tier", write.tier().name().toLowerCase());
        result.put("status", write.tier() == ToolTier.CONFIRM
                ? "approval_required"
                : proposal.status());
        return new AiAssistantToolResult(result, List.of());
    }

    /** Returns every pending confirm-tier proposal visible in one authorized session. */
    @Transactional(readOnly = true)
    public List<AiAssistantToolProposalDto> listPendingProposals(int sessionId) {
        Actor actor = currentActor();
        requireReadableSession(actor, sessionId);
        return chatMapper.listPendingToolCallsBySession(actor.workspaceId(), sessionId).stream()
                .map(toolCall -> new ProposalRead(toolCall, readStored(toolCall)))
                .filter(proposal -> proposal.write().tier() == ToolTier.CONFIRM)
                .map(proposal -> proposalDto(proposal.toolCall(), proposal.write()))
                .toList();
    }

    /** Returns one pending confirm-tier proposal from an authorized session. */
    @Transactional(readOnly = true)
    public AiAssistantToolProposalDto getPendingProposal(int sessionId, int toolCallId) {
        Actor actor = currentActor();
        requireReadableSession(actor, sessionId);
        AiChatToolCall toolCall = chatMapper.getToolCallBySession(
                actor.workspaceId(), sessionId, toolCallId);
        if (toolCall == null || !PROPOSED.equals(toolCall.getStatus())) {
            throw inaccessible();
        }
        StoredWrite write = readStored(toolCall);
        if (write.tier() != ToolTier.CONFIRM) {
            throw inaccessible();
        }
        return proposalDto(toolCall, write);
    }

    /** Executes or replays one auto-tier proposal while the originating turn remains active. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public WriteExecution executeAuto(AiChatQueuedTurn turn, int toolCallId) {
        accessFence.retainReadFenceUntilTransactionCompletion(turn.workspaceId());
        requireMutationAllowed(turn.workspaceId(), turn.userId());
        AiChatToolCall toolCall = lockAuthorizedToolCall(
                turn.workspaceId(), turn.userId(), turn.sessionId(), toolCallId, null);
        AiChatTurn storedTurn = chatMapper.getTurnByIdForUpdate(
                turn.workspaceId(), turn.sessionId(), turn.turnId());
        if (storedTurn == null
                || !Objects.equals(storedTurn.getRequestedByUserId(), turn.userId())
                || !RUNNING.equals(storedTurn.getStatus())) {
            throw new ConflictException("Assistant turn is no longer active");
        }
        if (EXECUTED.equals(toolCall.getStatus())) {
            return execution(toolCall);
        }
        if (toolCall.getMessageId() != turn.userMessageId()) {
            throw new ConflictException("Assistant tool replay is not executable");
        }
        requireStatus(toolCall, PROPOSED);
        StoredWrite write = readStored(toolCall);
        if (write.tier() != ToolTier.AUTO) {
            throw new ConflictException("Assistant tool requires approval");
        }
        requirePermissions(write);
        PreparedMutation mutation;
        try {
            mutation = lockMutationTarget(write);
        } catch (ResourceNotFoundException exception) {
            if (restrictionEpoch.current(turn.workspaceId()) != turn.restrictionEpoch()) {
                throw new AiAssistantLoopException(
                        "restrictions_changed", "restrictions_changed");
            }
            throw exception;
        }
        if (!restrictionEpoch.retainReadFenceUntilTransactionCompletionIfCurrent(
                turn.workspaceId(), turn.restrictionEpoch())) {
            throw new AiAssistantLoopException("restrictions_changed", "restrictions_changed");
        }
        ExecutionOutcome outcome = execute(write, null, mutation);
        String resultJson = resultEnvelope(write, outcome, null);
        if (chatMapper.updateToolCall(
                turn.workspaceId(), toolCall.getMessageId(), toolCall.getId(),
                EXECUTED, resultJson, turn.userId()) != 1) {
            throw new ConflictException("Assistant tool was already decided");
        }
        toolCall.setStatus(EXECUTED);
        toolCall.setResultJson(resultJson);
        return execution(toolCall);
    }

    /** Explicitly approves and executes one confirm-tier proposal. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AiAssistantToolCallDto approve(int sessionId, int toolCallId) {
        Actor actor = currentActor();
        accessFence.retainReadFenceUntilTransactionCompletion(actor.workspaceId());
        requireMutationAllowed(actor.workspaceId(), actor.userId());
        OwnerAssignment owner = preliminaryOwnerAssignment(actor, sessionId, toolCallId);
        AiChatToolCall toolCall = lockAuthorizedToolCall(
                actor.workspaceId(), actor.userId(), sessionId, toolCallId,
                owner == null ? null : owner.userId());
        if (EXECUTED.equals(toolCall.getStatus())) {
            return dto(toolCall);
        }
        requireStatus(toolCall, PROPOSED);
        StoredWrite write = readStored(toolCall);
        if (write.tier() != ToolTier.CONFIRM) {
            throw new ConflictException("Assistant tool does not require approval");
        }
        requirePermissions(write);
        PreparedMutation mutation = lockMutationTarget(write);
        if (!restrictionEpoch.retainReadFenceUntilTransactionCompletionIfCurrent(
                actor.workspaceId(), write.restrictionEpoch())) {
            throw new ConflictException("Assistant proposal restrictions changed");
        }
        ExecutionOutcome outcome = execute(write, owner, mutation);
        Map<String, Object> approval = new LinkedHashMap<>();
        approval.put("status", "approved");
        approval.put("at", clock.instant().toString());
        String resultJson = resultEnvelope(write, outcome, approval);
        if (chatMapper.updateToolCall(
                actor.workspaceId(), toolCall.getMessageId(), toolCall.getId(),
                EXECUTED, resultJson, actor.userId()) != 1) {
            throw new ConflictException("Assistant tool was already decided");
        }
        toolCall.setStatus(EXECUTED);
        toolCall.setResultJson(resultJson);
        return dto(toolCall);
    }

    /** Explicitly rejects one pending confirm-tier proposal without executing its domain action. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AiAssistantToolCallDto reject(int sessionId, int toolCallId) {
        Actor actor = currentActor();
        accessFence.retainReadFenceUntilTransactionCompletion(actor.workspaceId());
        requireActiveMembership(actor.workspaceId(), actor.userId());
        AiChatToolCall toolCall = lockAuthorizedToolCall(
                actor.workspaceId(), actor.userId(), sessionId, toolCallId, null);
        if (REJECTED.equals(toolCall.getStatus())) {
            return dto(toolCall);
        }
        requireStatus(toolCall, PROPOSED);
        StoredWrite write = readStored(toolCall);
        if (write.tier() != ToolTier.CONFIRM) {
            throw new ConflictException("Assistant tool does not require approval");
        }
        Map<String, Object> approval = new LinkedHashMap<>();
        approval.put("status", REJECTED);
        approval.put("at", clock.instant().toString());
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("tier", "confirm");
        envelope.put("approval", approval);
        String resultJson = serialize(envelope);
        if (chatMapper.updateToolCall(
                actor.workspaceId(), toolCall.getMessageId(), toolCall.getId(),
                REJECTED, resultJson, actor.userId()) != 1) {
            throw new ConflictException("Assistant tool was already decided");
        }
        toolCall.setStatus(REJECTED);
        toolCall.setResultJson(resultJson);
        return dto(toolCall);
    }

    /** Applies a bounded inverse only while the auto-created record still matches its fingerprint. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AiAssistantToolCallDto undo(int sessionId, int toolCallId) {
        Actor actor = currentActor();
        accessFence.retainReadFenceUntilTransactionCompletion(actor.workspaceId());
        requireActiveMembership(actor.workspaceId(), actor.userId());
        AiChatToolCall toolCall = lockAuthorizedToolCall(
                actor.workspaceId(), actor.userId(), sessionId, toolCallId, null);
        requireStatus(toolCall, EXECUTED);
        StoredWrite write = readStored(toolCall);
        if (write.tier() != ToolTier.AUTO) {
            throw new ConflictException("Assistant tool cannot be undone");
        }
        ObjectNode envelope = resultObject(toolCall);
        ObjectNode undo = requiredObject(envelope, "undo");
        String undoStatus = text(undo, "status");
        if ("undone".equals(undoStatus)) {
            return dto(toolCall);
        }
        if (!"available".equals(undoStatus)) {
            throw new ConflictException("Assistant tool has no owned inverse");
        }
        Instant expiresAt = Instant.parse(text(undo, "expiresAt"));
        if (clock.instant().isAfter(expiresAt)) {
            throw new ConflictException("Assistant tool undo window has expired");
        }
        requirePermissions(write);
        undo(write, undo);
        undo.put("status", "undone");
        undo.put("undoneAt", clock.instant().toString());
        String resultJson = serialize(envelope);
        if (chatMapper.updateExecutedToolResult(
                actor.workspaceId(), toolCall.getId(), resultJson, actor.userId()) != 1) {
            throw new ConflictException("Assistant tool could not be undone");
        }
        toolCall.setResultJson(resultJson);
        return dto(toolCall);
    }

    private ExecutionOutcome execute(
            StoredWrite write,
            OwnerAssignment owner,
            PreparedMutation mutation) {
        requireTargetAccessible(write);
        return switch (write.toolName()) {
            case "create_activity" -> createActivity(write);
            case "create_task" -> createTask(write);
            case "create_note" -> createNote(write);
            case "add_tag" -> addTag(write);
            case "change_deal_stage" -> changeDealStage(mutation);
            case "assign_owner" -> assignOwner(write, requireOwnerAssignment(owner));
            default -> throw new BadRequestException("Unsupported assistant write tool");
        };
    }

    private void requireTargetAccessible(StoredWrite write) {
        switch (write.targetKind()) {
            case "person" -> personService.getPersonById(write.targetId());
            case "company" -> companyService.getCompanyById(write.targetId());
            case "deal" -> dealService.getDealById(write.targetId());
            default -> throw new BadRequestException("Unsupported assistant record kind");
        }
    }

    private ExecutionOutcome createActivity(StoredWrite write) {
        CreateActivity request = request(write, CreateActivity.class);
        ResolvedDateTime start = dateResolver.resolveDateTime(request.start());
        int durationMinutes = request.durationMinutes() == null
                ? DEFAULT_MEETING_MINUTES
                : request.durationMinutes();
        LocalDateTime endUtc = start.utc().plusMinutes(durationMinutes);
        List<?> conflicts = List.of();
        if ("meeting".equalsIgnoreCase(request.type()) && "person".equals(write.targetKind())) {
            Object conflictData = readToolExecutor.findScheduleConflicts(
                    write.targetId(), start.utc(), endUtc).data().get("conflicts");
            conflicts = conflictData instanceof List<?> list ? list : List.of();
        }
        Activity activity = new Activity();
        activity.setType(request.type());
        activity.setSubject(request.subject());
        activity.setNotes(request.notes());
        activity.setTimestamp(start.mysqlUtc());
        link(activity, write);
        Activity created = activityService.create(activity);
        Map<String, Object> outcome = new LinkedHashMap<>();
        outcome.put("status", EXECUTED);
        outcome.put("recordType", "activity");
        outcome.put("type", created.getType());
        outcome.put("subject", created.getSubject());
        outcome.put("start", created.getTimestamp());
        outcome.put("timezone", start.timezone().getId());
        outcome.put("conflicts", conflicts);
        return new ExecutionOutcome(outcome, undoData(
                "activity", created.getId(), fingerprint(activityState(created)), true));
    }

    private ExecutionOutcome createTask(StoredWrite write) {
        CreateTask request = request(write, CreateTask.class);
        Task task = new Task();
        task.setDescription(request.description());
        LocalDate dueDate = dateResolver.resolveDate(request.dueDate());
        task.setDueDate(dueDate == null ? null : dueDate.toString());
        User actor = new User();
        actor.setId(workspaceService.getCurrentUserId());
        task.setAssignedTo(actor);
        link(task, write);
        Task created = taskService.create(task);
        Map<String, Object> outcome = new LinkedHashMap<>();
        outcome.put("status", EXECUTED);
        outcome.put("recordType", "task");
        outcome.put("description", created.getDescription());
        put(outcome, "dueDate", created.getDueDate());
        return new ExecutionOutcome(outcome, undoData(
                "task", created.getId(), fingerprint(taskState(created)), true));
    }

    private ExecutionOutcome createNote(StoredWrite write) {
        CreateNote request = request(write, CreateNote.class);
        Note note = new Note();
        note.setContent(request.content());
        note.setTitle(request.title());
        note.setVisibility(request.visibility());
        link(note, write);
        Note created = noteService.create(note);
        Map<String, Object> outcome = new LinkedHashMap<>();
        outcome.put("status", EXECUTED);
        outcome.put("recordType", "note");
        put(outcome, "title", created.getTitle());
        outcome.put("visibility", created.getVisibility());
        return new ExecutionOutcome(outcome, undoData(
                "note", created.getId(), fingerprint(noteState(created)), true));
    }

    private ExecutionOutcome addTag(StoredWrite write) {
        AddTag request = request(write, AddTag.class);
        Tag tag = uniqueTag(request.tag());
        boolean changed = addTag(write, tag.getId());
        Map<String, Object> outcome = new LinkedHashMap<>();
        outcome.put("status", EXECUTED);
        outcome.put("recordType", write.targetKind());
        outcome.put("tag", tag.getName());
        outcome.put("changed", changed);
        Map<String, Object> undo = undoData(
                "tag", write.targetId(), "present:" + tag.getId(), false);
        undo.put("tagId", tag.getId());
        return new ExecutionOutcome(outcome, undo);
    }

    private ExecutionOutcome changeDealStage(PreparedMutation mutation) {
        if (mutation.stageChange() == null || mutation.stage() == null) {
            throw new ConflictException("Prepared deal stage mutation is unavailable");
        }
        Deal changed = dealService.changeStage(mutation.stageChange());
        Map<String, Object> outcome = new LinkedHashMap<>();
        outcome.put("status", EXECUTED);
        outcome.put("recordType", "deal");
        outcome.put("stage", mutation.stage().getName());
        put(outcome, "closedAt", changed.getClosedAt());
        return new ExecutionOutcome(outcome, null);
    }

    private ExecutionOutcome assignOwner(StoredWrite write, OwnerAssignment owner) {
        switch (write.targetKind()) {
            case "person" -> personService.updateOwner(write.targetId(), owner.userId());
            case "company" -> companyService.updateOwner(write.targetId(), owner.userId());
            case "deal" -> dealService.updateOwner(write.targetId(), owner.userId());
            default -> throw new BadRequestException("Unsupported owner target");
        }
        Map<String, Object> outcome = new LinkedHashMap<>();
        outcome.put("status", EXECUTED);
        outcome.put("recordType", write.targetKind());
        outcome.put("owner", owner.label());
        return new ExecutionOutcome(outcome, null);
    }

    private void undo(StoredWrite write, ObjectNode undo) {
        String expected = text(undo, "fingerprint");
        int entityId = integer(undo, "entityId");
        switch (text(undo, "entityKind")) {
            case "activity" -> activityService.deleteIf(
                    entityId,
                    current -> expected.equals(fingerprint(activityState(current))));
            case "task" -> taskService.deleteIf(
                    entityId,
                    current -> expected.equals(fingerprint(taskState(current))));
            case "note" -> noteService.deleteIf(
                    entityId,
                    current -> expected.equals(fingerprint(noteState(current))));
            case "tag" -> throw new ConflictException("Assistant tag undo is unavailable");
            default -> throw new ConflictException("Assistant tool undo metadata is invalid");
        }
    }

    private AiChatToolCall lockAuthorizedToolCall(
            int workspaceId,
            int userId,
            int sessionId,
            int toolCallId,
            Integer targetUserId) {
        java.util.stream.Stream.of(userId, targetUserId)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .forEach(memberId -> workspaceService.lockAndRequireMember(
                        workspaceId, memberId));
        workspaceService.requirePermission(workspaceId, userId, Permission.AI_USE);
        AiChatSession session = chatMapper.getSessionByIdForUpdate(workspaceId, userId, sessionId);
        if (session == null || !ACTIVE.equals(session.getStatus())) {
            throw inaccessible();
        }
        if (!Objects.equals(session.getCreatedByUserId(), userId)
                && (!SHARED.equals(session.getVisibility())
                        || !chatMapper.isParticipant(workspaceId, sessionId, userId))) {
            throw inaccessible();
        }
        AiChatToolCall toolCall = chatMapper.getToolCallBySessionForUpdate(
                workspaceId, sessionId, toolCallId);
        if (toolCall == null || !Objects.equals(toolCall.getRequestedByUserId(), userId)) {
            throw inaccessible();
        }
        return toolCall;
    }

    private void requireMutationAllowed(int workspaceId, int userId) {
        requireActiveMembership(workspaceId, userId);
        if (!governanceService.isEnabled(workspaceId)) {
            throw new ForbiddenException("AI is disabled for this workspace");
        }
    }

    private void requireActiveMembership(int workspaceId, int userId) {
        if (!workspaceService.isMember(workspaceId, userId)) {
            throw inaccessible();
        }
    }

    private OwnerAssignment preliminaryOwnerAssignment(
            Actor actor, int sessionId, int toolCallId) {
        workspaceService.requirePermission(
                actor.workspaceId(), actor.userId(), Permission.AI_USE);
        AiChatSession session = chatMapper.getAccessibleSessionById(
                actor.workspaceId(), actor.userId(), sessionId);
        AiChatToolCall toolCall = chatMapper.getToolCallBySession(
                actor.workspaceId(), sessionId, toolCallId);
        if (session == null || toolCall == null
                || !Objects.equals(toolCall.getRequestedByUserId(), actor.userId())) {
            throw inaccessible();
        }
        if (!PROPOSED.equals(toolCall.getStatus())) {
            return null;
        }
        StoredWrite write = readStored(toolCall);
        if (!"assign_owner".equals(write.toolName())) {
            return null;
        }
        return resolveOwnerAssignment(request(write, AssignOwner.class).owner());
    }

    private void requireReadableSession(Actor actor, int sessionId) {
        workspaceService.requirePermission(
                actor.workspaceId(), actor.userId(), Permission.AI_USE);
        if (chatMapper.getAccessibleSessionById(
                actor.workspaceId(), actor.userId(), sessionId) == null) {
            throw inaccessible();
        }
    }

    private PreparedMutation lockMutationTarget(StoredWrite write) {
        if ("change_deal_stage".equals(write.toolName())) {
            Stage stage = resolveStage(write);
            return new PreparedMutation(
                    dealService.lockStageChangeRowsForUpdate(
                            write.targetId(), stage.getId()),
                    stage);
        }
        switch (write.targetKind()) {
            case "person" -> personService.lockProcessablePersonForUpdate(write.targetId());
            case "company" -> companyService.lockOwnedCompanyForUpdate(write.targetId());
            case "deal" -> dealService.lockDealForUpdate(write.targetId());
            default -> throw new BadRequestException("Unsupported assistant record kind");
        }
        return new PreparedMutation(null, null);
    }

    private void requirePermissions(StoredWrite write) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int userId = workspaceService.getCurrentUserId();
        for (Permission permission : permissions(write)) {
            workspaceService.requirePermission(workspaceId, userId, permission);
        }
    }

    private static Set<Permission> permissions(StoredWrite write) {
        return switch (write.toolName()) {
            case "create_activity" -> Set.of(Permission.ACTIVITY_CREATE, Permission.ACTIVITY_DELETE);
            case "create_task" -> Set.of(Permission.TASK_CREATE, Permission.TASK_DELETE);
            case "create_note" -> Set.of(Permission.NOTE_CREATE, Permission.NOTE_DELETE);
            case "add_tag", "assign_owner" -> Set.of(updatePermission(write.targetKind()));
            case "change_deal_stage" -> Set.of(Permission.DEAL_UPDATE);
            default -> throw new BadRequestException("Unsupported assistant write tool");
        };
    }

    private static Permission updatePermission(String kind) {
        return switch (kind) {
            case "person" -> Permission.PERSON_UPDATE;
            case "company" -> Permission.COMPANY_UPDATE;
            case "deal" -> Permission.DEAL_UPDATE;
            default -> throw new BadRequestException("Unsupported assistant record kind");
        };
    }

    private StoredWrite readStored(AiChatToolCall toolCall) {
        try {
            JsonNode root = objectMapper.readTree(toolCall.getArgumentsJson());
            String toolName = text(root, "tool");
            ToolTier tier = ToolTier.valueOf(text(root, "tier").toUpperCase());
            long expectedRestrictionEpoch = longInteger(root, "restrictionEpoch");
            JsonNode target = root.get("target");
            if (target == null || !target.isObject()) {
                throw new IllegalStateException("Assistant tool target is invalid");
            }
            String targetKind = text(target, "kind");
            int targetId = integer(target, "id");
            JsonNode request = root.get("request");
            if (!toolCall.getToolName().equals(toolName)
                    || tier != toolCatalog.tier(toolName)
                    || !acceptedKinds(toolName).contains(targetKind)
                    || targetId <= 0
                    || request == null || !request.isObject()) {
                throw new IllegalStateException("Assistant tool proposal is invalid");
            }
            readRequest(toolName, request);
            return new StoredWrite(
                    toolName, tier, targetKind, targetId,
                    expectedRestrictionEpoch, request);
        } catch (JacksonException | IllegalArgumentException exception) {
            throw new IllegalStateException("Assistant tool proposal could not be read", exception);
        }
    }

    private AiAssistantWriteToolRequest readRequest(String name, JsonNode args) {
        try {
            AiAssistantWriteToolRequest request = switch (name) {
                case "create_activity" -> objectMapper.treeToValue(args, CreateActivity.class);
                case "create_task" -> objectMapper.treeToValue(args, CreateTask.class);
                case "create_note" -> objectMapper.treeToValue(args, CreateNote.class);
                case "add_tag" -> objectMapper.treeToValue(args, AddTag.class);
                case "change_deal_stage" -> objectMapper.treeToValue(args, ChangeDealStage.class);
                case "assign_owner" -> objectMapper.treeToValue(args, AssignOwner.class);
                default -> throw AiAssistantLoopException.malformed("unknown_write_tool");
            };
            return validate(request);
        } catch (JacksonException exception) {
            throw AiAssistantLoopException.malformed("invalid_tool_arguments");
        }
    }

    private <T extends AiAssistantWriteToolRequest> T validate(@Valid T request) {
        Set<ConstraintViolation<T>> violations = validator.validate(request);
        if (!violations.isEmpty()) {
            throw AiAssistantLoopException.malformed("invalid_tool_arguments");
        }
        return request;
    }

    private <T extends AiAssistantWriteToolRequest> T request(
            StoredWrite write, Class<T> type) {
        try {
            return validate(objectMapper.treeToValue(write.request(), type));
        } catch (JacksonException exception) {
            throw new IllegalStateException("Assistant tool request could not be read", exception);
        }
    }

    private String resultEnvelope(
            StoredWrite write, ExecutionOutcome outcome, Map<String, Object> approval) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("tier", write.tier().name().toLowerCase());
        if (approval != null) {
            envelope.put("approval", approval);
        }
        envelope.put("outcome", outcome.publicData());
        if (outcome.undo() != null) {
            envelope.put("undo", outcome.undo());
        }
        return serialize(envelope);
    }

    private Map<String, Object> undoData(
            String entityKind, int entityId, String fingerprint, boolean available) {
        Map<String, Object> undo = new LinkedHashMap<>();
        undo.put("status", available ? "available" : "unavailable");
        undo.put("expiresAt", clock.instant().plus(UNDO_WINDOW).toString());
        undo.put("entityKind", entityKind);
        undo.put("entityId", entityId);
        undo.put("fingerprint", fingerprint);
        return undo;
    }

    private WriteExecution execution(AiChatToolCall toolCall) {
        AiAssistantToolCallDto dto = dto(toolCall);
        Map<String, Object> result = objectMapper.convertValue(
                dto.result(), new tools.jackson.core.type.TypeReference<Map<String, Object>>() { });
        result.put("toolCallId", dto.id());
        result.put("tier", dto.tier());
        result.put("status", dto.status());
        result.put("undoAvailable", dto.undoAvailable());
        put(result, "undoExpiresAt", dto.undoExpiresAt());
        return new WriteExecution(dto, new AiAssistantToolResult(result, List.of()));
    }

    private AiAssistantToolCallDto dto(AiChatToolCall toolCall) {
        JsonNode result = toolCall.getResultJson() == null
                ? objectMapper.createObjectNode()
                : resultObject(toolCall);
        JsonNode outcome = result.has("outcome")
                ? result.get("outcome")
                : objectMapper.createObjectNode();
        JsonNode undo = result.get("undo");
        String status = toolCall.getStatus();
        boolean undoAvailable = false;
        String undoExpiresAt = null;
        if (undo != null && undo.isObject()) {
            String undoStatus = text(undo, "status");
            undoExpiresAt = text(undo, "expiresAt");
            if ("undone".equals(undoStatus)) {
                status = "undone";
            } else if ("available".equals(undoStatus)) {
                undoAvailable = !clock.instant().isAfter(Instant.parse(undoExpiresAt));
            }
        }
        String tier = result.has("tier") ? text(result, "tier") : "confirm";
        return new AiAssistantToolCallDto(
                toolCall.getId(), toolCall.getToolName(), tier, status,
                outcome, undoAvailable, undoExpiresAt);
    }

    private ObjectNode resultObject(AiChatToolCall toolCall) {
        try {
            JsonNode node = objectMapper.readTree(toolCall.getResultJson());
            if (node instanceof ObjectNode object) {
                return object;
            }
            throw new IllegalStateException("Assistant tool result is invalid");
        } catch (JacksonException exception) {
            throw new IllegalStateException("Assistant tool result could not be read", exception);
        }
    }

    private boolean addTag(StoredWrite write, int tagId) {
        return switch (write.targetKind()) {
            case "person" -> personService.addTag(write.targetId(), tagId);
            case "company" -> companyService.addTag(write.targetId(), tagId);
            case "deal" -> dealService.addTag(write.targetId(), tagId);
            default -> throw new BadRequestException("Unsupported tag target");
        };
    }

    private Tag uniqueTag(String name) {
        List<Tag> matches = tagService.getAllTags().stream()
                .filter(tag -> tag.getName() != null && tag.getName().equalsIgnoreCase(name.trim()))
                .toList();
        if (matches.size() != 1) {
            throw new ResourceNotFoundException("Tag is unavailable or ambiguous");
        }
        return matches.getFirst();
    }

    private Stage resolveStage(StoredWrite write) {
        ChangeDealStage request = request(write, ChangeDealStage.class);
        Deal deal = dealService.getDealById(write.targetId());
        List<Stage> matches = pipelineService.getAllStages().stream()
                .filter(stage -> stage.getPipeline() != null
                        && Objects.equals(deal.getPipelineId(), stage.getPipeline().getId()))
                .filter(stage -> stage.getName() != null
                        && stage.getName().equalsIgnoreCase(request.stage().trim()))
                .toList();
        if (matches.size() != 1) {
            throw new ResourceNotFoundException("Deal stage is unavailable or ambiguous");
        }
        return matches.getFirst();
    }

    private AiAssistantToolProposalDto proposalDto(
            AiChatToolCall toolCall, StoredWrite write) {
        ObjectNode arguments = objectMapper.createObjectNode();
        switch (write.toolName()) {
            case "assign_owner" -> arguments.put(
                    "owner",
                    resolveOwnerAssignment(request(write, AssignOwner.class).owner()).label());
            case "change_deal_stage" -> arguments.put("stage", resolveStage(write).getName());
            default -> throw inaccessible();
        }
        return new AiAssistantToolProposalDto(
                toolCall.getId(),
                write.toolName(),
                write.tier().name().toLowerCase(),
                toolCall.getStatus(),
                proposalTarget(write),
                arguments);
    }

    private AiAssistantToolProposalDto.Target proposalTarget(StoredWrite write) {
        return switch (write.targetKind()) {
            case "person" -> {
                Person person = personService.getPersonById(write.targetId());
                if (person.getSuspendedAt() != null
                        || person.getProvisionCeasedAt() != null
                        || person.getArchivedAt() != null) {
                    throw inaccessible();
                }
                yield new AiAssistantToolProposalDto.Target(
                        "person", person.getId(), requireLabel(person.getName()));
            }
            case "company" -> {
                Company company = companyService.getCompanyById(write.targetId());
                yield new AiAssistantToolProposalDto.Target(
                        "company", company.getId(), requireLabel(company.getName()));
            }
            case "deal" -> {
                Deal deal = dealService.getDealById(write.targetId());
                yield new AiAssistantToolProposalDto.Target(
                        "deal", deal.getId(), requireLabel(deal.getName()));
            }
            default -> throw inaccessible();
        };
    }

    private static String requireLabel(String label) {
        if (label == null || label.isBlank()) {
            throw inaccessible();
        }
        return label;
    }

    private OwnerAssignment resolveOwnerAssignment(String owner) {
        if ("unassigned".equalsIgnoreCase(owner.trim())) {
            return new OwnerAssignment(null, "unassigned");
        }
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        List<User> matches = workspaceService.getMembers(workspaceId).stream()
                .filter(user -> user.getDisplayName() != null
                        && user.getDisplayName().equalsIgnoreCase(owner.trim())
                        || user.getUsername() != null
                        && user.getUsername().equalsIgnoreCase(owner.trim()))
                .toList();
        if (matches.size() != 1) {
            throw new ResourceNotFoundException("Owner is unavailable or ambiguous");
        }
        User match = matches.getFirst();
        String label = match.getDisplayName() == null || match.getDisplayName().isBlank()
                ? match.getUsername()
                : match.getDisplayName();
        return new OwnerAssignment(match.getId(), label);
    }

    private static OwnerAssignment requireOwnerAssignment(OwnerAssignment owner) {
        if (owner == null) {
            throw new IllegalStateException("Assistant owner assignment was not resolved");
        }
        return owner;
    }

    private static Map<String, Object> activityState(Activity activity) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("type", activity.getType());
        state.put("subject", activity.getSubject());
        state.put("notes", activity.getNotes());
        state.put("timestamp", activity.getTimestamp());
        state.put("personId", id(activity.getPerson()));
        state.put("dealId", id(activity.getDeal()));
        return state;
    }

    private static Map<String, Object> taskState(Task task) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("description", task.getDescription());
        state.put("completed", task.isCompleted());
        state.put("status", task.getStatus());
        state.put("position", task.getPosition());
        state.put("dueDate", task.getDueDate());
        state.put("assignedToId", id(task.getAssignedTo()));
        state.put("personId", id(task.getPerson()));
        state.put("dealId", id(task.getDeal()));
        return state;
    }

    private static Map<String, Object> noteState(Note note) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("content", note.getContent());
        state.put("title", note.getTitle());
        state.put("visibility", note.getVisibility());
        state.put("personId", id(note.getPerson()));
        state.put("dealId", id(note.getDeal()));
        return state;
    }

    private String fingerprint(Map<String, Object> state) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(
                    serialize(state).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void link(Activity activity, StoredWrite write) {
        if ("person".equals(write.targetKind())) {
            Person person = new Person();
            person.setId(write.targetId());
            activity.setPerson(person);
        } else {
            Deal deal = new Deal();
            deal.setId(write.targetId());
            activity.setDeal(deal);
        }
    }

    private static void link(Task task, StoredWrite write) {
        if ("person".equals(write.targetKind())) {
            Person person = new Person();
            person.setId(write.targetId());
            task.setPerson(person);
        } else {
            Deal deal = new Deal();
            deal.setId(write.targetId());
            task.setDeal(deal);
        }
    }

    private static void link(Note note, StoredWrite write) {
        if ("person".equals(write.targetKind())) {
            Person person = new Person();
            person.setId(write.targetId());
            note.setPerson(person);
        } else {
            Deal deal = new Deal();
            deal.setId(write.targetId());
            note.setDeal(deal);
        }
    }

    private static Set<String> acceptedKinds(String toolName) {
        return switch (toolName) {
            case "create_activity", "create_task", "create_note" -> Set.of("person", "deal");
            case "add_tag", "assign_owner" -> Set.of("person", "company", "deal");
            case "change_deal_stage" -> Set.of("deal");
            default -> Set.of();
        };
    }

    private Actor currentActor() {
        return new Actor(
                workspaceService.getCurrentWorkspaceId(), workspaceService.getCurrentUserId());
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Assistant tool metadata could not be serialized", exception);
        }
    }

    private static ObjectNode requiredObject(ObjectNode parent, String name) {
        JsonNode node = parent.get(name);
        if (node instanceof ObjectNode object) {
            return object;
        }
        throw new ConflictException("Assistant tool cannot be undone");
    }

    private static String text(JsonNode node, String name) {
        JsonNode value = node == null ? null : node.get(name);
        if (value == null || !value.isString() || value.asString().isBlank()) {
            throw new IllegalStateException("Assistant tool metadata is invalid");
        }
        return value.asString();
    }

    private static int integer(JsonNode node, String name) {
        JsonNode value = node == null ? null : node.get(name);
        if (value == null || !value.canConvertToInt()) {
            throw new IllegalStateException("Assistant tool metadata is invalid");
        }
        return value.asInt();
    }

    private static long longInteger(JsonNode node, String name) {
        JsonNode value = node == null ? null : node.get(name);
        if (value == null || !value.canConvertToLong() || value.asLong() < 0) {
            throw new IllegalStateException("Assistant tool metadata is invalid");
        }
        return value.asLong();
    }

    private static void requireStatus(AiChatToolCall toolCall, String status) {
        if (!status.equals(toolCall.getStatus())) {
            throw new ConflictException("Assistant tool was already decided");
        }
    }

    private static ResourceNotFoundException inaccessible() {
        return new ResourceNotFoundException("AI assistant session is not accessible");
    }

    private static void put(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    private static int id(Person person) {
        return person == null ? 0 : person.getId();
    }

    private static int id(Deal deal) {
        return deal == null ? 0 : deal.getId();
    }

    private static int id(User user) {
        return user == null ? 0 : user.getId();
    }

    /** Auto-tier execution result for the next model step and API clients. */
    public record WriteExecution(
            AiAssistantToolCallDto toolCall,
            AiAssistantToolResult toolResult) {
    }

    private record Actor(int workspaceId, int userId) {
    }

    private record StoredWrite(
            String toolName,
            ToolTier tier,
            String targetKind,
            int targetId,
            long restrictionEpoch,
            JsonNode request) {
    }

    private record ProposalRead(
            AiChatToolCall toolCall,
            StoredWrite write) {
    }

    private record OwnerAssignment(
            Integer userId,
            String label) {
    }

    private record PreparedMutation(
            DealService.LockedStageChange stageChange,
            Stage stage) {
    }

    private record ExecutionOutcome(
            Map<String, Object> publicData,
            Map<String, Object> undo) {
    }
}
