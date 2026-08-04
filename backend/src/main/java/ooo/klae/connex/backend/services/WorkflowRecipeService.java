package ooo.klae.connex.backend.services;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import ooo.klae.connex.backend.beans.WorkflowRecipeOrigin;
import ooo.klae.connex.backend.dto.RuleAction;
import ooo.klae.connex.backend.dto.RuleTrigger;
import ooo.klae.connex.backend.dto.SegmentCondition;
import ooo.klae.connex.backend.dto.SegmentDefinition;
import ooo.klae.connex.backend.dto.WorkflowCanvas;
import ooo.klae.connex.backend.dto.WorkflowCreateRequest;
import ooo.klae.connex.backend.dto.WorkflowDefinition;
import ooo.klae.connex.backend.dto.WorkflowDto;
import ooo.klae.connex.backend.dto.WorkflowEdge;
import ooo.klae.connex.backend.dto.WorkflowNode;
import ooo.klae.connex.backend.dto.WorkflowPublishRequest;
import ooo.klae.connex.backend.dto.WorkflowRecipeDto;
import ooo.klae.connex.backend.dto.WorkflowRecipeInstallDto;
import ooo.klae.connex.backend.dto.WorkflowRecipeInstallRequest;
import ooo.klae.connex.backend.dto.WorkflowRecipePreviewDto;
import ooo.klae.connex.backend.dto.WorkflowRecipePreviewRequest;
import ooo.klae.connex.backend.dto.WorkflowValidationDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.WorkflowOperationsMapper;
import ooo.klae.connex.backend.services.WorkflowDraftCanonicalizer.CanonicalDraft;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;

/** Immutable curated recipe catalog, side-effect-free preview, and disabled installation. */
@Service
@RequiredArgsConstructor
public class WorkflowRecipeService {

    private static final int RECIPE_VERSION = 1;
    private static final List<String> RECIPE_ORDER = List.of(
        "person-job-change-follow-up",
        "deal-won-handoff",
        "cooling-company-review");

    private final WorkflowService workflowService;
    private final WorkflowOperationsMapper operationsMapper;
    private final WorkflowDraftCanonicalizer canonicalizer;
    private final WorkflowDefinitionValidator definitionValidator;
    private final WorkflowActionRetryPolicy retryPolicy;
    private final WorkflowSimulationService simulationService;
    private final WorkspaceService workspaceService;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    @RequirePermission(Permission.RULE_MANAGE)
    public List<WorkflowRecipeDto> list() {
        return RECIPE_ORDER.stream().map(this::metadata).toList();
    }

    @Transactional(readOnly = true)
    @RequirePermission(Permission.RULE_MANAGE)
    public WorkflowRecipeDto get(String recipeKey) {
        requireRecipe(recipeKey);
        return metadata(recipeKey);
    }

    @Transactional(readOnly = true)
    @RequirePermission(Permission.RULE_MANAGE)
    public WorkflowRecipePreviewDto preview(
            String recipeKey,
            WorkflowRecipePreviewRequest request) {
        requireRecipe(recipeKey);
        if (request == null) {
            throw new BadRequestException("Workflow recipe preview request is required");
        }
        RecipeMaterial material = materialize(
            recipeKey,
            request.name(),
            request.description(),
            request.parameters());
        return preview(material, request.exampleRecordId());
    }

    @Transactional
    @RequirePermission(Permission.RULE_MANAGE)
    public WorkflowRecipeInstallDto install(
            String recipeKey,
            WorkflowRecipeInstallRequest request) {
        requireRecipe(recipeKey);
        if (request == null) {
            throw new BadRequestException("Workflow recipe install request is required");
        }
        RecipeMaterial material = materialize(
            recipeKey,
            request.name(),
            request.description(),
            request.parameters());
        WorkflowRecipePreviewDto preview = preview(material, null);
        if (!preview.unresolvedParameters().isEmpty()
                || !preview.validation().canPublish()) {
            throw new ConflictException("Workflow recipe configuration is incomplete");
        }
        if (!preview.previewHash().equalsIgnoreCase(request.previewHash())) {
            throw new ConflictException("Workflow recipe preview changed; preview it again");
        }
        WorkflowCreateRequest create = new WorkflowCreateRequest();
        create.setName(material.name());
        create.setDescription(material.description());
        create.setRecordType(material.recordType());
        create.setExecutionMode("user");
        create.setDefinition(objectMapper.valueToTree(material.definition()));
        create.setCanvas(objectMapper.valueToTree(material.canvas()));
        WorkflowDto created = workflowService.createForRecipe(
            create, material.actorUserId());
        WorkflowPublishRequest publish = new WorkflowPublishRequest();
        publish.setExpectedRevision(0);
        WorkflowDto installed = workflowService.publish(created.id(), publish);
        WorkflowRecipeOrigin origin = new WorkflowRecipeOrigin();
        origin.setWorkspaceId(workspaceService.getCurrentWorkspaceId());
        origin.setWorkflowId(installed.id());
        origin.setRecipeKey(recipeKey);
        origin.setRecipeVersion(RECIPE_VERSION);
        origin.setTemplateHash(HexFormat.of().parseHex(preview.previewHash()));
        origin.setInstalledById(workspaceService.getCurrentUserId());
        operationsMapper.insertRecipeOrigin(origin);
        return new WorkflowRecipeInstallDto(
            recipeKey,
            RECIPE_VERSION,
            preview.previewHash(),
            installed);
    }

    private WorkflowRecipePreviewDto preview(
            RecipeMaterial material,
            Integer exampleRecordId) {
        CanonicalDraft canonical = canonicalizer.canonicalizeDraft(
            material.name(),
            material.description(),
            material.recordType(),
            "user",
            material.definition(),
            material.canvas());
        Set<Permission> required = definitionValidator.validateForMutation(
            material.recordType(), "user", material.definition());
        List<String> requiredPermissions = required.stream()
            .map(Enum::name)
            .sorted()
            .toList();
        Set<Permission> actorPermissions = material.actorUserId() < 1
            ? Set.of()
            : workspaceService.permissionsFor(
                workspaceService.getCurrentWorkspaceId(), material.actorUserId());
        List<String> missingPermissions = required.stream()
            .filter(permission -> !actorPermissions.contains(permission))
            .map(Enum::name)
            .sorted()
            .toList();
        boolean actorAvailable = material.actorUserId() > 0
            && workspaceService.getRole(
                workspaceService.getCurrentWorkspaceId(), material.actorUserId()) != null;
        List<String> missing = new ArrayList<>(material.unresolvedParameters());
        if (!actorAvailable && !missing.contains("actorUserId")) {
            missing.add("actorUserId");
        }
        boolean canPublish = missing.isEmpty() && missingPermissions.isEmpty();
        WorkflowValidationDto validation = new WorkflowValidationDto(
            0,
            true,
            canPublish,
            false,
            requiredPermissions,
            missingPermissions,
            List.of());
        List<WorkflowRecipePreviewDto.PlannedAction> actions = material.definition().nodes().stream()
            .filter(WorkflowNode.Action.class::isInstance)
            .map(WorkflowNode.Action.class::cast)
            .map(node -> new WorkflowRecipePreviewDto.PlannedAction(
                node.id(),
                node.config().getType(),
                retryPolicy.safety(node.config()).value()))
            .toList();
        var exampleResult = exampleRecordId == null
            ? null
            : simulationService.simulateDraft(
                canonical,
                material.definition(),
                material.actorUserId(),
                workspaceService.getCurrentUserId(),
                exampleRecordId);
        return new WorkflowRecipePreviewDto(
            metadata(material.recipeKey()),
            previewHash(material, canonical),
            material.definition(),
            material.canvas(),
            List.copyOf(missing),
            validation,
            actions,
            exampleResult,
            false);
    }

    private RecipeMaterial materialize(
            String recipeKey,
            String requestedName,
            String requestedDescription,
            Map<String, JsonNode> parameters) {
        Map<String, JsonNode> safeParameters = parameters == null ? Map.of() : parameters;
        List<String> unresolved = requiredParameters(recipeKey).stream()
            .filter(key -> parameterMissing(safeParameters.get(key)))
            .toList();
        int fallbackUserId = workspaceService.getCurrentUserId();
        int actorUserId = integer(safeParameters, "actorUserId", fallbackUserId, 1, Integer.MAX_VALUE);
        int targetUserId = integer(safeParameters, "targetUserId", fallbackUserId, 1, Integer.MAX_VALUE);
        int dueInDays = integer(safeParameters, "dueInDays", 7, 0, 365);
        int coolingDays = integer(safeParameters, "coolingDays", 30, 30, 365);
        String taskTitle = text(safeParameters, "taskTitle", defaultTaskTitle(recipeKey), 255);
        String activityNote = text(
            safeParameters, "activityNote", "Record the completed handoff.", 2000);
        String name = boundedText(
            requestedName, defaultName(recipeKey), 128, "Workflow recipe name");
        String description = boundedText(
            requestedDescription, defaultDescription(recipeKey), 512,
            "Workflow recipe description");
        WorkflowDefinition definition = switch (recipeKey) {
            case "person-job-change-follow-up" -> personJobChange(
                targetUserId, taskTitle, dueInDays);
            case "deal-won-handoff" -> dealWon(
                targetUserId, taskTitle, activityNote, dueInDays);
            case "cooling-company-review" -> coolingCompany(
                targetUserId, taskTitle, coolingDays, dueInDays);
            default -> throw recipeNotFound();
        };
        String recordType = switch (recipeKey) {
            case "person-job-change-follow-up" -> "person";
            case "deal-won-handoff" -> "deal";
            case "cooling-company-review" -> "company";
            default -> throw recipeNotFound();
        };
        return new RecipeMaterial(
            recipeKey,
            name,
            description,
            recordType,
            actorUserId,
            definition,
            canvas(definition),
            List.copyOf(unresolved),
            canonicalParameters(safeParameters));
    }

    private WorkflowRecipeDto metadata(String recipeKey) {
        return switch (recipeKey) {
            case "person-job-change-follow-up" -> recipe(
                recipeKey,
                "person.job_changed",
                List.of("person"),
                List.of("task"),
                List.of("TASK_CREATE"),
                List.of("create_task"));
            case "deal-won-handoff" -> recipe(
                recipeKey,
                "deal.won",
                List.of("deal"),
                List.of("task", "activity"),
                List.of("ACTIVITY_CREATE", "TASK_CREATE"),
                List.of("create_task", "log_activity"));
            case "cooling-company-review" -> recipe(
                recipeKey,
                "schedule.daily",
                List.of("company", "activity"),
                List.of("task"),
                List.of("TASK_CREATE"),
                List.of("create_task"));
            default -> throw recipeNotFound();
        };
    }

    private WorkflowRecipeDto recipe(
            String recipeKey,
            String sourceEvent,
            List<String> dataRead,
            List<String> dataWritten,
            List<String> requiredPermissions,
            List<String> sideEffects) {
        List<WorkflowRecipeDto.Action> actions = sideEffects.stream()
            .map(type -> new WorkflowRecipeDto.Action(type, retrySafety(type)))
            .toList();
        return new WorkflowRecipeDto(
            recipeKey,
            RECIPE_VERSION,
            1,
            "recipes.items." + recipeKey + ".title",
            "recipes.items." + recipeKey + ".description",
            sourceEvent,
            "user",
            dataRead,
            dataWritten,
            requiredParameters(recipeKey),
            requiredPermissions,
            List.of("sourceEvent", "recordType", "actionTypes", "retryBehavior"),
            requiredParameters(recipeKey),
            sideEffects,
            actions,
            "disable_stops_new_runs",
            "archive_retains_history");
    }

    private String retrySafety(String actionType) {
        RuleAction action = new RuleAction();
        action.setType(actionType);
        return retryPolicy.safety(action).value();
    }

    private static WorkflowDefinition personJobChange(
            int targetUserId,
            String title,
            int dueInDays) {
        RuleTrigger trigger = entityTrigger("person.job_changed");
        RuleAction task = task(targetUserId, title, dueInDays);
        return new WorkflowDefinition(
            1,
            "trigger",
            List.of(
                new WorkflowNode.Trigger("trigger", trigger),
                new WorkflowNode.Action("follow-up", task),
                new WorkflowNode.End("complete")),
            List.of(
                edge("trigger-next", "trigger", "follow-up", WorkflowEdge.Outcome.NEXT),
                edge("follow-up-next", "follow-up", "complete", WorkflowEdge.Outcome.NEXT)));
    }

    private static WorkflowDefinition dealWon(
            int targetUserId,
            String title,
            String activityNote,
            int dueInDays) {
        RuleAction activity = new RuleAction();
        activity.setType("log_activity");
        activity.setActivityType("note");
        activity.setBody(activityNote);
        return new WorkflowDefinition(
            1,
            "trigger",
            List.of(
                new WorkflowNode.Trigger("trigger", entityTrigger("deal.won")),
                new WorkflowNode.Action("handoff-task", task(targetUserId, title, dueInDays)),
                new WorkflowNode.Action("handoff-activity", activity),
                new WorkflowNode.End("complete")),
            List.of(
                edge("trigger-next", "trigger", "handoff-task", WorkflowEdge.Outcome.NEXT),
                edge("task-next", "handoff-task", "handoff-activity", WorkflowEdge.Outcome.NEXT),
                edge("activity-next", "handoff-activity", "complete", WorkflowEdge.Outcome.NEXT)));
    }

    private static WorkflowDefinition coolingCompany(
            int targetUserId,
            String title,
            int coolingDays,
            int dueInDays) {
        RuleTrigger trigger = new RuleTrigger();
        trigger.setType("schedule");
        trigger.setCadence("daily");
        SegmentCondition cooling = new SegmentCondition();
        cooling.setType("predicate");
        cooling.setKey("cooling");
        SegmentCondition noActivity = new SegmentCondition();
        noActivity.setType("predicate");
        noActivity.setKey("no_activity");
        noActivity.setDays(coolingDays);
        SegmentDefinition condition = new SegmentDefinition();
        condition.setMatch("all");
        condition.setConditions(List.of(cooling, noActivity));
        condition.setGroups(List.of());
        return new WorkflowDefinition(
            1,
            "trigger",
            List.of(
                new WorkflowNode.Trigger("trigger", trigger),
                new WorkflowNode.Condition("enrollment", condition),
                new WorkflowNode.Action("review-task", task(targetUserId, title, dueInDays)),
                new WorkflowNode.End("complete")),
            List.of(
                edge("trigger-next", "trigger", "enrollment", WorkflowEdge.Outcome.NEXT),
                edge("enrollment-yes", "enrollment", "review-task", WorkflowEdge.Outcome.YES),
                edge("enrollment-no", "enrollment", "complete", WorkflowEdge.Outcome.NO),
                edge("review-next", "review-task", "complete", WorkflowEdge.Outcome.NEXT)));
    }

    private static RuleTrigger entityTrigger(String event) {
        RuleTrigger trigger = new RuleTrigger();
        trigger.setType("entity_change");
        trigger.setEvents(List.of(event));
        return trigger;
    }

    private static RuleAction task(int targetUserId, String title, int dueInDays) {
        RuleAction action = new RuleAction();
        action.setType("create_task");
        action.setTargetUserId(targetUserId);
        action.setTitle(title);
        action.setDueInDays(dueInDays);
        return action;
    }

    private static WorkflowEdge edge(
            String id,
            String source,
            String target,
            WorkflowEdge.Outcome outcome) {
        return new WorkflowEdge(id, source, target, outcome);
    }

    private static WorkflowCanvas canvas(WorkflowDefinition definition) {
        Map<String, WorkflowCanvas.Position> positions = new LinkedHashMap<>();
        int index = 0;
        for (WorkflowNode node : definition.nodes()) {
            positions.put(
                node.id(),
                new WorkflowCanvas.Position(
                    BigDecimal.valueOf(index++ * 320L),
                    node instanceof WorkflowNode.End
                        ? BigDecimal.valueOf(160) : BigDecimal.ZERO));
        }
        return new WorkflowCanvas(
            Map.copyOf(positions),
            new WorkflowCanvas.Viewport(
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ONE));
    }

    private String previewHash(RecipeMaterial material, CanonicalDraft canonical) {
        Map<String, Object> identity = new TreeMap<>();
        identity.put("recipeKey", material.recipeKey());
        identity.put("recipeVersion", RECIPE_VERSION);
        identity.put("name", material.name());
        identity.put("description", material.description());
        identity.put("recordType", material.recordType());
        identity.put("actorUserId", material.actorUserId());
        identity.put("parameters", material.canonicalParameters());
        identity.put("definitionHash", HexFormat.of().formatHex(canonical.definitionHash()));
        try {
            return HexFormat.of().formatHex(sha256(
                objectMapper.writeValueAsString(identity).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new BadRequestException("Workflow recipe configuration is malformed");
        }
    }

    private static Map<String, String> canonicalParameters(Map<String, JsonNode> parameters) {
        Map<String, String> values = new TreeMap<>();
        parameters.forEach((key, value) -> {
            if (key != null && key.length() <= 64 && value != null && value.isValueNode()) {
                values.put(key, value.toString());
            }
        });
        return Map.copyOf(values);
    }

    private static List<String> requiredParameters(String recipeKey) {
        return switch (recipeKey) {
            case "person-job-change-follow-up" -> List.of(
                "actorUserId", "targetUserId", "taskTitle", "dueInDays");
            case "deal-won-handoff" -> List.of(
                "actorUserId", "targetUserId", "taskTitle", "activityNote", "dueInDays");
            case "cooling-company-review" -> List.of(
                "actorUserId", "targetUserId", "taskTitle", "coolingDays", "dueInDays");
            default -> throw recipeNotFound();
        };
    }

    private static boolean parameterMissing(JsonNode value) {
        return value == null || value.isNull()
            || value.isTextual() && value.textValue().isBlank();
    }

    private static int integer(
            Map<String, JsonNode> parameters,
            String key,
            int fallback,
            int minimum,
            int maximum) {
        JsonNode value = parameters.get(key);
        if (parameterMissing(value)) {
            return fallback;
        }
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new BadRequestException("Workflow recipe parameter " + key + " must be an integer");
        }
        int result = value.intValue();
        if (result < minimum || result > maximum) {
            throw new BadRequestException("Workflow recipe parameter " + key + " is out of range");
        }
        return result;
    }

    private static String text(
            Map<String, JsonNode> parameters,
            String key,
            String fallback,
            int maximum) {
        JsonNode value = parameters.get(key);
        if (parameterMissing(value)) {
            return fallback;
        }
        if (!value.isTextual()) {
            throw new BadRequestException("Workflow recipe parameter " + key + " must be text");
        }
        return boundedText(value.textValue(), fallback, maximum,
            "Workflow recipe parameter " + key);
    }

    private static String boundedText(
            String value,
            String fallback,
            int maximum,
            String label) {
        String result = value == null || value.isBlank() ? fallback : value.trim();
        if (result.length() > maximum) {
            throw new BadRequestException(label + " is too long");
        }
        return result;
    }

    private static String defaultName(String recipeKey) {
        return switch (recipeKey) {
            case "person-job-change-follow-up" -> "Job change follow-up";
            case "deal-won-handoff" -> "Deal won handoff";
            case "cooling-company-review" -> "Cooling company review";
            default -> throw recipeNotFound();
        };
    }

    private static String defaultDescription(String recipeKey) {
        return switch (recipeKey) {
            case "person-job-change-follow-up" ->
                "Create an assigned follow-up task when a person's job changes.";
            case "deal-won-handoff" ->
                "Create a handoff task and activity when a deal is won.";
            case "cooling-company-review" ->
                "Create review tasks for cooling companies on a daily schedule.";
            default -> throw recipeNotFound();
        };
    }

    private static String defaultTaskTitle(String recipeKey) {
        return switch (recipeKey) {
            case "person-job-change-follow-up" -> "Follow up after job change";
            case "deal-won-handoff" -> "Complete deal handoff";
            case "cooling-company-review" -> "Review cooling relationship";
            default -> throw recipeNotFound();
        };
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void requireRecipe(String recipeKey) {
        if (!RECIPE_ORDER.contains(recipeKey)) {
            throw recipeNotFound();
        }
    }

    private static ResourceNotFoundException recipeNotFound() {
        return new ResourceNotFoundException("Workflow recipe not found");
    }

    private record RecipeMaterial(
        String recipeKey,
        String name,
        String description,
        String recordType,
        int actorUserId,
        WorkflowDefinition definition,
        WorkflowCanvas canvas,
        List<String> unresolvedParameters,
        Map<String, String> canonicalParameters
    ) { }
}
