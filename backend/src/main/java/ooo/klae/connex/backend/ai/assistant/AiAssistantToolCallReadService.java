package ooo.klae.connex.backend.ai.assistant;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.assistant.AiAssistantToolCatalog.ToolTier;
import ooo.klae.connex.backend.beans.AiChatMessage;
import ooo.klae.connex.backend.beans.AiChatToolCall;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.AiAssistantToolCallReadDto;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.AiChatMapper;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.PipelineMapper;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.tenant.Permission;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Projects durable assistant write-tool state through the current viewer's live authority. */
@Service
@RequiredArgsConstructor
public class AiAssistantToolCallReadService {
    private static final Pattern TURN_STEP_KEY = Pattern.compile(
            "^turn-([1-9][0-9]*)-step-([1-9][0-9]*)$");

    private final AiAssistantToolCatalog toolCatalog;
    private final AiChatMapper chatMapper;
    private final WorkspaceService workspaceService;
    private final PersonMapper personMapper;
    private final CompanyMapper companyMapper;
    private final DealMapper dealMapper;
    private final PipelineMapper pipelineMapper;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /** Returns every safe write-tool card in one authorized session. */
    @Transactional(readOnly = true)
    public List<AiAssistantToolCallReadDto> list(int sessionId, boolean pendingOnly) {
        Viewer viewer = currentViewer();
        requireReadableSession(viewer, sessionId);
        List<StoredToolCall> stored = chatMapper.listToolCallsBySession(
                viewer.workspaceId(), sessionId, pendingOnly).stream()
                .map(this::readStored)
                .filter(Objects::nonNull)
                .filter(call -> !pendingOnly || call.tier() == ToolTier.CONFIRM)
                .toList();
        return project(viewer, sessionId, stored);
    }

    /** Returns one safe write-tool card in an authorized session. */
    @Transactional(readOnly = true)
    public AiAssistantToolCallReadDto get(int sessionId, int toolCallId) {
        Viewer viewer = currentViewer();
        requireReadableSession(viewer, sessionId);
        AiChatToolCall toolCall = chatMapper.getToolCallBySession(
                viewer.workspaceId(), sessionId, toolCallId);
        StoredToolCall stored = toolCall == null ? null : readStored(toolCall);
        if (stored == null) {
            throw inaccessible();
        }
        List<AiAssistantToolCallReadDto> projected = project(
                viewer, sessionId, List.of(stored));
        if (projected.isEmpty()) {
            throw inaccessible();
        }
        return projected.getFirst();
    }

    private List<AiAssistantToolCallReadDto> project(
            Viewer viewer, int sessionId, List<StoredToolCall> stored) {
        Set<Permission> viewerPermissions = workspaceService.permissionsFor(
                viewer.workspaceId(), viewer.userId());
        Map<RecordKey, VisibleTarget> visibleTargets = visibleTargets(
                viewer.workspaceId(), stored);
        List<User> assignableOwners = stored.stream().anyMatch(call ->
                "assign_owner".equals(call.toolCall().getToolName())
                        && Objects.equals(
                                call.toolCall().getRequestedByUserId(), viewer.userId()))
                ? workspaceService.getMembers(viewer.workspaceId())
                : List.of();
        List<Stage> stages = stored.stream().anyMatch(call ->
                "change_deal_stage".equals(call.toolCall().getToolName())
                        && Objects.equals(
                                call.toolCall().getRequestedByUserId(), viewer.userId()))
                ? pipelineMapper.getAllStages(viewer.workspaceId())
                : List.of();
        Map<Integer, Integer> assistantMessages = assistantMessages(
                viewer.workspaceId(), sessionId);
        List<AiAssistantToolCallReadDto> projected = new ArrayList<>();
        for (StoredToolCall call : stored) {
            String status = publicStatus(call.toolCall());
            if (status == null) {
                continue;
            }
            UndoProjection undo = undoProjection(
                    call, status, viewer.userId(), viewerPermissions);
            if (undo.undone()) {
                status = "undone";
            }
            RecordKey targetKey = new RecordKey(call.targetKind(), call.targetId());
            VisibleTarget visibleTarget = visibleTargets.get(targetKey);
            AiAssistantToolCallReadDto.Target target = visibleTarget == null
                    ? new AiAssistantToolCallReadDto.Target(call.targetKind(), null, null)
                    : new AiAssistantToolCallReadDto.Target(
                            call.targetKind(), call.targetId(), visibleTarget.label());
            Integer messageId = assistantMessages.get(call.turnId());
            projected.add(new AiAssistantToolCallReadDto(
                    call.toolCall().getId(),
                    call.toolCall().getToolName(),
                    call.tier().name().toLowerCase(),
                    status,
                    target,
                    requestSummary(
                            call, viewer, visibleTarget, assignableOwners, stages),
                    outcomeSummary(call.toolCall(), status),
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
                    toolCall, tier, targetKind, targetId, turnId, requestValue);
        } catch (JacksonException | IllegalArgumentException exception) {
            return null;
        }
    }

    private Map<RecordKey, VisibleTarget> visibleTargets(
            int workspaceId, List<StoredToolCall> stored) {
        Set<RecordKey> requested = new LinkedHashSet<>();
        stored.stream()
                .map(call -> new RecordKey(call.targetKind(), call.targetId()))
                .forEach(requested::add);
        Map<RecordKey, VisibleTarget> visible = new LinkedHashMap<>();
        List<Integer> personIds = ids(requested, "person");
        List<Integer> companyIds = ids(requested, "company");
        List<Integer> dealIds = ids(requested, "deal");
        if (!personIds.isEmpty()) {
            for (Person person : personMapper.getByIds(workspaceId, personIds)) {
                if (isProcessable(person)) {
                    putVisible(visible, "person", person.getId(), person.getName(), null);
                }
            }
        }
        if (!companyIds.isEmpty()) {
            for (Company company : companyMapper.getByIds(workspaceId, companyIds)) {
                putVisible(visible, "company", company.getId(), company.getName(), null);
            }
        }
        if (!dealIds.isEmpty()) {
            for (Deal deal : dealMapper.getByIds(workspaceId, dealIds)) {
                putVisible(
                        visible, "deal", deal.getId(), deal.getName(), deal.getPipelineId());
            }
        }
        return Map.copyOf(visible);
    }

    private Map<Integer, Integer> assistantMessages(int workspaceId, int sessionId) {
        Map<Integer, Integer> messages = new LinkedHashMap<>();
        for (AiChatMessage message : chatMapper.listAssistantMessagesBySession(
                workspaceId, sessionId)) {
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
            Set<Permission> viewerPermissions) {
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
            boolean available = expiresAt != null
                    && Objects.equals(call.toolCall().getRequestedByUserId(), viewerUserId)
                    && hasUndoPermissions(call.toolCall().getToolName(), viewerPermissions)
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

    private static boolean hasUndoPermissions(
            String toolName, Set<Permission> viewerPermissions) {
        return switch (toolName) {
            case "create_activity" -> viewerPermissions.containsAll(
                    Set.of(Permission.ACTIVITY_CREATE, Permission.ACTIVITY_DELETE));
            case "create_task" -> viewerPermissions.containsAll(
                    Set.of(Permission.TASK_CREATE, Permission.TASK_DELETE));
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
            Viewer viewer,
            VisibleTarget visibleTarget,
            List<User> assignableOwners,
            List<Stage> stages) {
        String toolName = call.toolCall().getToolName();
        if (visibleTarget != null
                && Objects.equals(call.toolCall().getRequestedByUserId(), viewer.userId())) {
            String resolved = switch (toolName) {
                case "assign_owner" -> ownerSummary(call.requestValue(), assignableOwners);
                case "change_deal_stage" -> stageSummary(
                        call.requestValue(), visibleTarget.pipelineId(), stages);
                default -> null;
            };
            if (resolved != null) {
                return resolved;
            }
        }
        return switch (toolName) {
            case "create_activity" -> "Create an activity";
            case "create_task" -> "Create a task";
            case "create_note" -> "Create a note";
            case "add_tag" -> "Add an existing tag";
            case "change_deal_stage" -> "Change the deal stage";
            case "assign_owner" -> "Assign an owner";
            default -> "Run a write tool";
        };
    }

    private static String ownerSummary(String requestedOwner, List<User> assignableOwners) {
        String normalizedOwner = requestedOwner.trim();
        if ("unassigned".equalsIgnoreCase(normalizedOwner)) {
            return "Remove the current owner";
        }
        List<User> matches = assignableOwners.stream()
                .filter(user -> user.getDisplayName() != null
                        && user.getDisplayName().equalsIgnoreCase(normalizedOwner)
                        || user.getUsername() != null
                        && user.getUsername().equalsIgnoreCase(normalizedOwner))
                .toList();
        if (matches.size() != 1) {
            return null;
        }
        User match = matches.getFirst();
        String label = match.getDisplayName() == null || match.getDisplayName().isBlank()
                ? match.getUsername()
                : match.getDisplayName();
        return label == null || label.isBlank() ? null : "Assign owner: " + label;
    }

    private static String stageSummary(
            String requestedStage, Integer pipelineId, List<Stage> stages) {
        if (pipelineId == null) {
            return null;
        }
        List<Stage> matches = stages.stream()
                .filter(stage -> stage.getPipeline() != null
                        && stage.getPipeline().getId() == pipelineId)
                .filter(stage -> stage.getName() != null
                        && stage.getName().equalsIgnoreCase(requestedStage.trim()))
                .toList();
        return matches.size() == 1
                ? "Change deal stage to: " + matches.getFirst().getName()
                : null;
    }

    private String outcomeSummary(AiChatToolCall toolCall, String status) {
        return switch (status) {
            case "proposed" -> null;
            case "rejected" -> "Request rejected";
            case "failed" -> "Request failed";
            case "undone" -> "Created record removed";
            case "executed" -> switch (toolCall.getToolName()) {
                case "create_activity" -> "Activity created";
                case "create_task" -> "Task created";
                case "create_note" -> "Note created";
                case "add_tag" -> addTagOutcomeSummary(toolCall.getResultJson());
                case "change_deal_stage" -> "Deal stage changed";
                case "assign_owner" -> "Owner assigned";
                default -> "Request completed";
            };
            default -> null;
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

    private static boolean acceptsTarget(String toolName, String kind) {
        return switch (toolName) {
            case "create_activity", "create_task", "create_note" ->
                    "person".equals(kind) || "deal".equals(kind);
            case "add_tag", "assign_owner" ->
                    "person".equals(kind) || "company".equals(kind) || "deal".equals(kind);
            case "change_deal_stage" -> "deal".equals(kind);
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
        if (stepNumber > AiChatAgentLoopService.HARD_MAX_STEPS) {
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
            Map<RecordKey, VisibleTarget> visible,
            String kind,
            int id,
            String label,
            Integer pipelineId) {
        if (label != null && !label.isBlank()) {
            visible.put(new RecordKey(kind, id), new VisibleTarget(label, pipelineId));
        }
    }

    private Viewer currentViewer() {
        return new Viewer(
                workspaceService.getCurrentWorkspaceId(), workspaceService.getCurrentUserId());
    }

    private void requireReadableSession(Viewer viewer, int sessionId) {
        workspaceService.requirePermission(
                viewer.workspaceId(), viewer.userId(), Permission.AI_USE);
        if (chatMapper.getAccessibleSessionById(
                viewer.workspaceId(), viewer.userId(), sessionId) == null) {
            throw inaccessible();
        }
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
            String requestValue) {
    }

    private record RecordKey(String kind, int id) {
    }

    private record VisibleTarget(String label, Integer pipelineId) {
    }

    private record UndoProjection(boolean available, String expiresAt, boolean undone) {
        private static final UndoProjection NONE = new UndoProjection(false, null, false);
    }
}
