package ooo.klae.connex.backend.ai.assistant;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

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
import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteTool.Authority;
import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteTool.Execution;
import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteTool.Inverse;
import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteTool.Lock;
import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteTool.LockedTarget;
import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteTool.Outcome;
import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteTool.PrincipalRequest;
import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteTool.ReadBack;
import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteTool.Resolution;
import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteTool.Row;
import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteTool.Target;
import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteToolRequest.AddTag;
import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteToolRequest.AssignOwner;
import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteToolRequest.CreateActivity;
import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteToolRequest.CreateNote;
import ooo.klae.connex.backend.ai.assistant.AiChatResourceRegistry.ResourceRef;
import ooo.klae.connex.backend.beans.Activity;
import ooo.klae.connex.backend.beans.AiChatSession;
import ooo.klae.connex.backend.beans.AiChatToolCall;
import ooo.klae.connex.backend.beans.AiChatTurn;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Note;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Tag;
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
import ooo.klae.connex.backend.services.TagService;
import ooo.klae.connex.backend.services.TaskService;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.services.WorkspaceService.LockedPermissionSnapshot;
import ooo.klae.connex.backend.tenant.Permission;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The assistant write framework: executes validated writes through native domain services and owns
 * approval-safe replay.
 *
 * <p>A tool declared as an {@link AiAssistantWriteTool} bean supplies only what differs between
 * tools. Everything a write must never get wrong is performed here, in one order, for every tool:
 * the entry gate of the decision, the principals resolved once before any lock, the locked
 * authorization roots, the session, tool-call and (immediate tier) turn rows, the stored proposal
 * revalidated against the catalog and the registry, the tool's permissions asserted from the locked
 * snapshot, the task board and target locks the tool declares, the restriction fence, proposal
 * freshness for every confirm-tier tool, the permissions re-asserted after the record lock, the
 * owner-scope target gate, the write, the identifier read back off its result, and the fail-closed
 * status write. Tools still on {@link AiAssistantWriteToolRegistry#LEGACY_TOOLS} run through the
 * same order on their existing per-tool arms.
 */
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
    private final AiAssistantWriteToolRegistry writeToolRegistry;
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
    private final AiRestrictionEpoch restrictionEpoch;
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
            return execution(toolCall, true).toolResult();
        }
        return modelResult(
                proposal.id(), write.toolName(), write.tier().name().toLowerCase(),
                write.tier() == ToolTier.CONFIRM ? "approval_required" : proposal.status(),
                null);
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
    public WriteExecution executeAuto(
            AiChatQueuedTurn turn,
            int toolCallId,
            Consumer<AiAssistantToolResult> resultGuard) {
        requireMutationAllowed(turn.workspaceId(), turn.userId());
        AuthorizedToolCall authorized = lockAuthorizedToolCall(
                turn.workspaceId(), turn.userId(), turn.sessionId(), toolCallId, List.of());
        AiChatToolCall toolCall = authorized.toolCall();
        AiChatTurn storedTurn = chatMapper.getTurnByIdForUpdate(
                turn.workspaceId(), turn.sessionId(), turn.turnId());
        if (storedTurn == null
                || !Objects.equals(storedTurn.getRequestedByUserId(), turn.userId())
                || !RUNNING.equals(storedTurn.getStatus())) {
            throw new ConflictException("Assistant turn is no longer active");
        }
        if (EXECUTED.equals(toolCall.getStatus())) {
            return execution(toolCall, true);
        }
        if (toolCall.getMessageId() != turn.userMessageId()) {
            throw new ConflictException("Assistant tool replay is not executable");
        }
        requireStatus(toolCall, PROPOSED);
        StoredWrite write = readStored(toolCall);
        if (write.tier() != ToolTier.AUTO) {
            throw new ConflictException("Assistant tool requires approval");
        }
        requireNoPrincipals(write);
        requirePermissions(authorized.authority(), turn.userId(), write);
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
        requirePermissions(authorized.authority(), turn.userId(), write);
        ExecutionOutcome outcome = execute(
                write,
                new Authority(
                        turn.workspaceId(), turn.userId(), Authority.Kind.TURN_IMMEDIATE,
                        toolCall.getId(), clock.instant()),
                PreliminaryPrincipals.NONE,
                mutation);
        String resultJson = resultEnvelope(write, outcome, null);
        toolCall.setStatus(EXECUTED);
        toolCall.setResultJson(resultJson);
        WriteExecution execution = execution(toolCall, false);
        resultGuard.accept(execution.toolResult());
        if (chatMapper.updateToolCall(
                turn.workspaceId(), toolCall.getMessageId(), toolCall.getId(),
                EXECUTED, resultJson, turn.userId()) != 1) {
            throw new ConflictException("Assistant tool was already decided");
        }
        return execution;
    }

    /** Explicitly approves and executes one confirm-tier proposal. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AiAssistantToolCallDto approve(int sessionId, int toolCallId) {
        Actor actor = currentActor();
        requireMutationAllowed(actor.workspaceId(), actor.userId());
        PreliminaryPrincipals principals = preliminaryPrincipals(actor, sessionId, toolCallId);
        AuthorizedToolCall authorized = lockAuthorizedToolCall(
                actor.workspaceId(), actor.userId(), sessionId, toolCallId,
                principals.userIds());
        AiChatToolCall toolCall = authorized.toolCall();
        if (EXECUTED.equals(toolCall.getStatus())) {
            return dto(toolCall);
        }
        requireStatus(toolCall, PROPOSED);
        StoredWrite write = readStored(toolCall);
        if (write.tier() != ToolTier.CONFIRM) {
            throw new ConflictException("Assistant tool does not require approval");
        }
        requirePermissions(authorized.authority(), actor.userId(), write);
        PreparedMutation mutation = lockMutationTarget(write);
        if (!restrictionEpoch.retainReadFenceUntilTransactionCompletionIfCurrent(
                actor.workspaceId(), write.restrictionEpoch())) {
            throw new ConflictException("Assistant proposal restrictions changed");
        }
        requireTargetUnchangedSinceProposal(toolCall, mutation);
        requirePermissions(authorized.authority(), actor.userId(), write);
        ExecutionOutcome outcome = execute(
                write,
                new Authority(
                        actor.workspaceId(), actor.userId(), Authority.Kind.MEMBER_APPROVAL,
                        toolCall.getId(), clock.instant()),
                principals,
                mutation);
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
        requireActiveMembership(actor.workspaceId(), actor.userId());
        AiChatToolCall toolCall = lockAuthorizedToolCall(
                actor.workspaceId(), actor.userId(), sessionId, toolCallId, List.of()).toolCall();
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
        requireActiveMembership(actor.workspaceId(), actor.userId());
        AuthorizedToolCall authorized = lockAuthorizedToolCall(
                actor.workspaceId(), actor.userId(), sessionId, toolCallId, List.of());
        AiChatToolCall toolCall = authorized.toolCall();
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
        requirePermissions(authorized.authority(), actor.userId(), write);
        Optional<AiAssistantWriteTool> declared = writeToolRegistry.find(write.toolName());
        if (declared.isPresent()) {
            if (!declared.get().inverseAvailable()) {
                throw new ConflictException("Assistant tool has no owned inverse");
            }
            declared.get().undo(
                    new Authority(
                            actor.workspaceId(), actor.userId(), Authority.Kind.MEMBER_UNDO,
                            toolCall.getId(), clock.instant()),
                    new Inverse(
                            text(undo, "entityKind"),
                            integer(undo, "entityId"),
                            text(undo, "fingerprint"),
                            true,
                            Map.of()));
        } else {
            undo(write, undo);
        }
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

    /**
     * Runs the owner-scope target gate, then the write itself.
     *
     * <p>The gate reads the target through the scoped domain getters after every lock is held, so a
     * target the actor's member scope cannot see refuses before any tool runs.
     */
    private ExecutionOutcome execute(
            StoredWrite write,
            Authority authority,
            PreliminaryPrincipals principals,
            PreparedMutation mutation) {
        requireTargetAccessible(write);
        Optional<AiAssistantWriteTool> declared = writeToolRegistry.find(write.toolName());
        if (declared.isPresent()) {
            return apply(declared.get(), write, authority, principals.principals(), mutation);
        }
        return switch (write.toolName()) {
            case "create_activity" -> createActivity(write);
            case "create_note" -> createNote(write);
            case "add_tag" -> addTag(write);
            case "assign_owner" -> assignOwner(write, requireOwnerAssignment(principals.owner()));
            default -> throw new BadRequestException("Unsupported assistant write tool");
        };
    }

    /**
     * Applies one declared tool to its single row and verifies the identifier it wrote.
     *
     * <p>The identifier is read off the write call's own return value and compared with the one
     * resolved before the lock. A divergence is recorded beside the outcome, never inside it, so the
     * outcome the member and the model read keeps its exact keys.
     */
    private ExecutionOutcome apply(
            AiAssistantWriteTool tool,
            StoredWrite write,
            Authority authority,
            List<PrincipalRequest> principals,
            PreparedMutation mutation) {
        Row row = new Row(new Target(write.targetKind(), write.targetId()), write.typedRequest());
        Outcome outcome = tool.apply(new Execution(
                authority,
                row,
                principals,
                mutation.resolution(),
                new LockedTarget(mutation.targetUpdatedAt(), mutation.stageChange())));
        Inverse inverse = outcome.inverse();
        Map<String, Object> undo = null;
        if (inverse != null) {
            undo = undoData(
                    inverse.entityKind(), inverse.entityId(), inverse.fingerprint(),
                    inverse.available());
            undo.putAll(inverse.extra());
        }
        return new ExecutionOutcome(outcome.data(), undo, verification(outcome.readBack()));
    }

    private static Map<String, Object> verification(ReadBack readBack) {
        if (readBack.requested() == null
                || Objects.equals(readBack.requested(), readBack.applied())) {
            return null;
        }
        Map<String, Object> verification = new LinkedHashMap<>();
        verification.put("field", readBack.field());
        verification.put("requested", readBack.requested());
        verification.put("applied", readBack.applied());
        return verification;
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
        boolean conflictsTruncated = false;
        if ("meeting".equalsIgnoreCase(request.type()) && "person".equals(write.targetKind())) {
            AiAssistantToolResult conflictResult = readToolExecutor.findScheduleConflicts(
                    write.targetId(), start.utc(), endUtc);
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
        outcome.put("conflictsTruncated", conflictsTruncated);
        return new ExecutionOutcome(outcome, undoData(
                "activity", created.getId(), fingerprint(activityState(created)), true));
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
            case "note" -> noteService.deleteIf(
                    entityId,
                    current -> expected.equals(fingerprint(noteState(current))));
            case "tag" -> throw new ConflictException("Assistant tag undo is unavailable");
            default -> throw new ConflictException("Assistant tool undo metadata is invalid");
        }
    }

    /**
     * Locks the authorization rows this decision rests on, then the session and tool-call rows.
     *
     * <p>The authority is read once, from rows locked before any other row this transaction locks:
     * the user roots of the actor and of any principal {@code FOR SHARE} ascending by user id, the
     * active workspace root {@code FOR SHARE}, their memberships {@code FOR UPDATE} ascending by user
     * id, then the actor's custom role and its permission rows {@code FOR UPDATE} by role id. A
     * principal carries no requirement, so its role is never locked. Those rows stay locked until
     * commit, so a revocation that arrives mid-decision waits for it, and this service's own
     * permission assertions read the snapshot in memory and add no lock edge after a tenant record.
     *
     * <p>The snapshot checks the workspace's lifecycle, not the organization's. An organization that
     * enters teardown after the unlocked entry gate is refused by that gate's {@code isMember} join
     * and by the domain services' own permission checks, not here; no write path may treat this
     * snapshot alone as proof that the organization is active.
     *
     * <p>A principal is authorized like any locked member, so one whose account deletion is reserved
     * is refused with {@code User N is not a member of this workspace} even while its membership row
     * is still active: a record is not handed to an account that is being erased.
     *
     * @param principalUserIds the principals the write will name, each locked with no requirement
     *     of its own
     */
    private AuthorizedToolCall lockAuthorizedToolCall(
            int workspaceId,
            int userId,
            int sessionId,
            int toolCallId,
            List<Integer> principalUserIds) {
        Map<Integer, Set<Permission>> required = new LinkedHashMap<>();
        required.put(userId, Set.of(Permission.AI_USE));
        for (Integer principalUserId : principalUserIds) {
            required.putIfAbsent(principalUserId, Set.of());
        }
        LockedPermissionSnapshot authority =
                workspaceService.lockAndRequirePermissionsSnapshot(workspaceId, required);
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
        return new AuthorizedToolCall(toolCall, authority);
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

    /**
     * Resolves, before any lock, which principal rows the approval will have to lock.
     *
     * <p>A declared tool resolves its principals through {@link AiAssistantWriteTool#principals},
     * once; the framework locks exactly those rows and hands the same objects to the write. A tool
     * still on the legacy ledger resolves its owner here as before.
     *
     * <p>It is deliberately not an authorization step and takes no permission read of its own. One
     * here would run unlocked selects that the MyBatis first-level cache then replays for every
     * later permission question in this transaction, including the domain service's own
     * {@code @RequirePermission} check, handing each of them a pre-lock answer. Authority comes
     * from {@link #lockAuthorizedToolCall} instead.
     *
     * <p>A caller without {@code AI_USE} therefore reaches this step before the locked 403. An
     * unknown or foreign tool call answers 404, a stored proposal that no longer parses answers its
     * parse error, and an {@code assign_owner} proposal whose owner no longer resolves answers 404
     * {@code Owner is unavailable or ambiguous}. Each concerns only the caller's own proposal.
     */
    private PreliminaryPrincipals preliminaryPrincipals(
            Actor actor, int sessionId, int toolCallId) {
        AiChatSession session = chatMapper.getAccessibleSessionById(
                actor.workspaceId(), actor.userId(), sessionId);
        AiChatToolCall toolCall = chatMapper.getToolCallBySession(
                actor.workspaceId(), sessionId, toolCallId);
        if (session == null || toolCall == null
                || !Objects.equals(toolCall.getRequestedByUserId(), actor.userId())) {
            throw inaccessible();
        }
        if (!PROPOSED.equals(toolCall.getStatus())) {
            return PreliminaryPrincipals.NONE;
        }
        StoredWrite write = readStored(toolCall);
        Optional<AiAssistantWriteTool> declared = writeToolRegistry.find(write.toolName());
        if (declared.isPresent()) {
            return new PreliminaryPrincipals(
                    null, declared.get().principals(write.typedRequest()));
        }
        if (!"assign_owner".equals(write.toolName())) {
            return PreliminaryPrincipals.NONE;
        }
        return new PreliminaryPrincipals(
                resolveOwnerAssignment(request(write, AssignOwner.class).owner()), List.of());
    }

    /** Refuses an immediate-tier declared tool that names a principal no approval resolved. */
    private void requireNoPrincipals(StoredWrite write) {
        Optional<AiAssistantWriteTool> declared = writeToolRegistry.find(write.toolName());
        if (declared.isPresent() && !declared.get().principals(write.typedRequest()).isEmpty()) {
            throw new IllegalStateException("An immediate assistant tool cannot name a principal");
        }
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
        Optional<AiAssistantWriteTool> declared = writeToolRegistry.find(write.toolName());
        if (declared.isPresent()) {
            return lockDeclaredTarget(declared.get(), write);
        }
        return new PreparedMutation(null, null, lockTargetForUpdate(write));
    }

    /**
     * Takes a declared tool's aggregate locks in the framework's fixed order.
     *
     * <p>The target value is resolved first, by non-locking reads, because a stage change locks the
     * rows of the stage it resolves. Then the task board root when the tool declares it — the board
     * is always taken before any person, as the task-board lock order requires — and then the target
     * row. A tool never takes a lock of its own.
     */
    private PreparedMutation lockDeclaredTarget(AiAssistantWriteTool tool, StoredWrite write) {
        Lock lock = tool.lock(write.targetKind());
        Resolution resolution = tool.resolve(
                new Target(write.targetKind(), write.targetId()), write.typedRequest());
        if (lock.taskBoard()) {
            taskService.lockBoardForCreation();
        }
        return switch (lock.target()) {
            case PERSON_SHARE -> {
                if (!"person".equals(write.targetKind())) {
                    throw new BadRequestException("Unsupported assistant record kind");
                }
                yield new PreparedMutation(
                        null,
                        resolution,
                        updatedAt(personService.lockProcessablePersonForShare(write.targetId())));
            }
            case RECORD_UPDATE -> new PreparedMutation(null, resolution, lockTargetForUpdate(write));
            case DEAL_STAGE_CHANGE -> {
                if (resolution == null) {
                    throw new IllegalStateException("Assistant deal stage was not resolved");
                }
                DealService.LockedStageChange stageChange =
                        dealService.lockStageChangeRowsForUpdate(write.targetId(), resolution.id());
                yield new PreparedMutation(
                        stageChange,
                        resolution,
                        stageChange == null ? null : stageChange.targetUpdatedAt());
            }
        };
    }

    private String lockTargetForUpdate(StoredWrite write) {
        return switch (write.targetKind()) {
            case "person" -> updatedAt(personService.lockProcessablePersonForUpdate(
                    write.targetId()));
            case "company" -> updatedAt(companyService.lockOwnedCompanyForUpdate(
                    write.targetId()));
            case "deal" -> updatedAt(dealService.lockDealForUpdate(write.targetId()));
            default -> throw new BadRequestException("Unsupported assistant record kind");
        };
    }

    private static String updatedAt(Person person) {
        return person == null ? null : person.getUpdatedAt();
    }

    private static String updatedAt(Company company) {
        return company == null ? null : company.getUpdatedAt();
    }

    private static String updatedAt(Deal deal) {
        return deal == null ? null : deal.getUpdatedAt();
    }

    /**
     * Refuses one approval whose record was written after the proposal it is applying.
     *
     * <p>Everything else approval revalidates is about the member: their membership, their
     * permissions, the workspace's restrictions. This is about the record. A colleague who changed
     * the same field between the proposal being shown and the member pressing apply would otherwise
     * be silently overwritten by values the member read before that edit existed, so the write is
     * refused and the card re-reads the record and states what it now says. The comparison is the
     * one the card itself made, so a proposal shown as applicable is not refused on a rule the
     * member never saw.
     */
    private static void requireTargetUnchangedSinceProposal(
            AiChatToolCall toolCall, PreparedMutation mutation) {
        if (AiAssistantProposalFreshness.changedSince(
                mutation.targetUpdatedAt(), toolCall.getCreatedAt())) {
            throw new ConflictException("Assistant proposal target changed");
        }
    }

    /**
     * Asserts the tool's permissions against the authority locked before the session row.
     *
     * <p>It performs no database access, so there is no statement for the MyBatis first-level cache
     * to answer with a pre-lock result and no lock edge behind a record. The call after the record
     * lock reads the same immutable snapshot and cannot fail once the first has passed; it is a
     * structural check, not the protection. The protection is that the snapshot's rows stay locked
     * until commit.
     */
    private void requirePermissions(
            LockedPermissionSnapshot authority, int userId, StoredWrite write) {
        authority.revalidate();
        Set<Permission> effective = authority.effectiveFor(userId);
        for (Permission permission : EnumSet.copyOf(permissions(write))) {
            if (!effective.contains(permission)) {
                throw new ForbiddenException(
                        "Requires the " + permission + " permission in this workspace");
            }
        }
    }

    private Set<Permission> permissions(StoredWrite write) {
        Optional<AiAssistantWriteTool> declared = writeToolRegistry.find(write.toolName());
        if (declared.isPresent()) {
            return declared.get().requiredPermissions(write.targetKind());
        }
        return switch (write.toolName()) {
            case "create_activity" -> Set.of(Permission.ACTIVITY_CREATE, Permission.ACTIVITY_DELETE);
            case "create_note" -> Set.of(Permission.NOTE_CREATE, Permission.NOTE_DELETE);
            case "add_tag", "assign_owner" -> Set.of(updatePermission(write.targetKind()));
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
            AiAssistantWriteToolRequest typedRequest = readRequest(toolName, request);
            return new StoredWrite(
                    toolName, tier, targetKind, targetId,
                    expectedRestrictionEpoch, request, typedRequest);
        } catch (JacksonException | IllegalArgumentException exception) {
            throw new IllegalStateException("Assistant tool proposal could not be read", exception);
        }
    }

    private AiAssistantWriteToolRequest readRequest(String name, JsonNode args) {
        try {
            Optional<AiAssistantWriteTool> declared = writeToolRegistry.find(name);
            AiAssistantWriteToolRequest request = declared.isPresent()
                    ? objectMapper.treeToValue(args, declared.get().requestType())
                    : switch (name) {
                        case "create_activity" -> objectMapper.treeToValue(
                                args, CreateActivity.class);
                        case "create_note" -> objectMapper.treeToValue(args, CreateNote.class);
                        case "add_tag" -> objectMapper.treeToValue(args, AddTag.class);
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
        if (outcome.verification() != null) {
            envelope.put("verification", outcome.verification());
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

    private WriteExecution execution(AiChatToolCall toolCall, boolean replayed) {
        AiAssistantToolCallDto dto = dto(toolCall);
        return new WriteExecution(dto, modelResult(
                dto.id(), toolCall.getToolName(), dto.tier(), dto.status(), dto.result()), replayed);
    }

    private AiAssistantToolResult modelResult(
            int toolCallId, String tool, String tier, String status, JsonNode outcome) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("toolCallId", toolCallId);
        result.put("tool", tool);
        result.put("tier", tier);
        result.put("status", status);
        result.put("outcome", modelOutcome(tool, outcome));
        return new AiAssistantToolResult(result, List.of());
    }

    private Map<String, Object> modelOutcome(String tool, JsonNode outcome) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (outcome == null || !outcome.isObject()) {
            return result;
        }
        Optional<AiAssistantWriteTool> declared = writeToolRegistry.find(tool);
        if (declared.isPresent()) {
            return declared.get().modelOutcome(outcome);
        }
        switch (tool) {
            case "create_activity" -> {
                copyText(outcome, result, "recordType");
                copyText(outcome, result, "type");
                copyText(outcome, result, "subject");
                copyText(outcome, result, "start");
                copyText(outcome, result, "timezone");
                result.put("conflictCount", outcome.path("conflicts").size());
                result.put("conflictsTruncated", outcome.path("conflictsTruncated").asBoolean());
            }
            case "create_note" -> {
                copyText(outcome, result, "recordType");
                copyText(outcome, result, "title");
                copyText(outcome, result, "visibility");
            }
            case "add_tag" -> {
                copyText(outcome, result, "recordType");
                copyText(outcome, result, "tag");
                result.put("changed", outcome.path("changed").asBoolean());
            }
            default -> {
                copyText(outcome, result, "recordType");
                copyText(outcome, result, "owner");
            }
        }
        return result;
    }

    private static void copyText(JsonNode source, Map<String, Object> target, String field) {
        AiAssistantWriteTool.copyText(source, target, field);
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

    private AiAssistantToolProposalDto proposalDto(
            AiChatToolCall toolCall, StoredWrite write) {
        ObjectNode arguments = objectMapper.createObjectNode();
        Optional<AiAssistantWriteTool> declared = writeToolRegistry.find(write.toolName());
        if (declared.isPresent()) {
            Resolution resolution = declared.get().resolve(
                    new Target(write.targetKind(), write.targetId()), write.typedRequest());
            if (resolution == null) {
                throw inaccessible();
            }
            arguments.put(resolution.field(), resolution.label());
        } else if ("assign_owner".equals(write.toolName())) {
            arguments.put(
                    "owner",
                    resolveOwnerAssignment(request(write, AssignOwner.class).owner()).label());
        } else {
            throw inaccessible();
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
        return AiAssistantWriteTool.fingerprint(objectMapper, state);
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

    private Set<String> acceptedKinds(String toolName) {
        Optional<AiAssistantWriteTool> declared = writeToolRegistry.find(toolName);
        if (declared.isPresent()) {
            return declared.get().acceptedTargetKinds();
        }
        return switch (toolName) {
            case "create_activity", "create_note" -> Set.of("person", "deal");
            case "add_tag", "assign_owner" -> Set.of("person", "company", "deal");
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

    /** Auto-tier execution result for the next model step and API clients. */
    public record WriteExecution(
            AiAssistantToolCallDto toolCall,
            AiAssistantToolResult toolResult,
            boolean replayed) {
    }

    private record Actor(int workspaceId, int userId) {
    }

    private record AuthorizedToolCall(
            AiChatToolCall toolCall,
            LockedPermissionSnapshot authority) {
    }

    private record StoredWrite(
            String toolName,
            ToolTier tier,
            String targetKind,
            int targetId,
            long restrictionEpoch,
            JsonNode request,
            AiAssistantWriteToolRequest typedRequest) {
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
            Resolution resolution,
            String targetUpdatedAt) {
    }

    private record ExecutionOutcome(
            Map<String, Object> publicData,
            Map<String, Object> undo,
            Map<String, Object> verification) {
        private ExecutionOutcome(Map<String, Object> publicData, Map<String, Object> undo) {
            this(publicData, undo, null);
        }
    }

    /**
     * The principals one approval resolved before any lock.
     *
     * @param owner a legacy-ledger owner assignment, or {@code null}
     * @param principals a declared tool's principals, locked and handed to its write unchanged
     */
    private record PreliminaryPrincipals(
            OwnerAssignment owner,
            List<PrincipalRequest> principals) {
        private static final PreliminaryPrincipals NONE = new PreliminaryPrincipals(null, List.of());

        private List<Integer> userIds() {
            List<Integer> userIds = new ArrayList<>();
            if (owner != null && owner.userId() != null) {
                userIds.add(owner.userId());
            }
            principals.stream().map(PrincipalRequest::userId).forEach(userIds::add);
            return List.copyOf(userIds);
        }
    }
}
