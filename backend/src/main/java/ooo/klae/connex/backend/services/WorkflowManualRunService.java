package ooo.klae.connex.backend.services;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.SavedView;
import ooo.klae.connex.backend.beans.Workflow;
import ooo.klae.connex.backend.beans.WorkflowInvocation;
import ooo.klae.connex.backend.beans.WorkflowInvocationRecord;
import ooo.klae.connex.backend.beans.WorkflowVersion;
import ooo.klae.connex.backend.dto.MemberScope;
import ooo.klae.connex.backend.dto.SegmentDefinition;
import ooo.klae.connex.backend.dto.WorkflowDefinition;
import ooo.klae.connex.backend.dto.WorkflowDiagnosticDto;
import ooo.klae.connex.backend.dto.WorkflowInvocationResultDto;
import ooo.klae.connex.backend.dto.WorkflowManualConfirmRequest;
import ooo.klae.connex.backend.dto.WorkflowManualFilter;
import ooo.klae.connex.backend.dto.WorkflowManualPreparationDto;
import ooo.klae.connex.backend.dto.WorkflowManualPrepareRequest;
import ooo.klae.connex.backend.dto.WorkflowManualScope;
import ooo.klae.connex.backend.dto.WorkflowNode;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.WorkflowMapper;
import ooo.klae.connex.backend.mappers.WorkflowOperationsMapper;
import ooo.klae.connex.backend.mappers.WorkflowVersionMapper;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;

/** Freezes, confirms, and reports exact-scope canonical manual workflow invocations. */
@Service
@RequiredArgsConstructor
public class WorkflowManualRunService {

    private static final int MAX_RECORDS = 1000;
    private static final int MAX_SAMPLES = 25;
    private static final int MAX_LABEL_LENGTH = 128;
    private static final Set<String> TERMINAL_INVOCATION_STATUSES = Set.of(
        "succeeded", "failed", "partial", "cancelled", "expired");
    private static final Set<String> SOURCE_SURFACES = Set.of(
        "record", "record_list", "saved_view", "search", "command_palette");
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final WorkflowMapper workflowMapper;
    private final WorkflowVersionMapper workflowVersionMapper;
    private final WorkflowOperationsMapper operationsMapper;
    private final WorkflowDraftCanonicalizer canonicalizer;
    private final WorkflowDefinitionValidator definitionValidator;
    private final WorkflowActionRetryPolicy retryPolicy;
    private final WorkflowActionGuard actionGuard;
    private final WorkflowRecordGuard recordGuard;
    private final WorkflowManualRunConfirmationTransaction confirmationTransaction;
    private final WorkflowManualRunDispatchTransaction dispatchTransaction;
    private final WorkflowRunOperationService runOperationService;
    private final PersonService personService;
    private final CompanyService companyService;
    private final DealService dealService;
    private final SegmentService segmentService;
    private final SavedViewService savedViewService;
    private final MemberScopeResolver memberScopeResolver;
    private final WorkspaceService workspaceService;
    private final SystemActor systemActor;
    private final ObjectMapper objectMapper;

    @Transactional
    @RequirePermission(Permission.RULE_MANAGE)
    public WorkflowManualPreparationDto prepare(
            int workflowId,
            WorkflowManualPrepareRequest request) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int requesterId = workspaceService.getCurrentUserId();
        String sourceSurface = requireSourceSurface(request.sourceSurface());
        Workflow workflow = requireWorkflow(workspaceId, workflowId);
        WorkflowVersion version = requireActiveVersion(workspaceId, workflow);
        ResolvedScope resolved = resolveScope(
            request.scope(), sourceSurface, requesterId, version.getRecordType());
        WorkflowDefinition definition = canonicalizer.parseDefinition(version.getDefinitionJson());
        definitionValidator.validate(version.getRecordType(), version.getExecutionMode(), definition);
        int actorUserId = actorUserId(version);
        List<WorkflowManualPreparationDto.Action> actions = actions(definition);
        List<String> blockers = operationalBlockers(workflow, version, actorUserId, resolved.ids());
        List<WorkflowInvocationRecord> records = new ArrayList<>();
        List<WorkflowManualPreparationDto.Sample> samples = new ArrayList<>();
        int missingReferences = 0;
        int configurationSkips = 0;
        int ordinal = 0;
        List<WorkflowNode.Action> actionNodes = actionNodes(definition);
        for (int recordId : resolved.ids()) {
            WorkflowInvocationRecord record = new WorkflowInvocationRecord();
            record.setWorkspaceId(workspaceId);
            record.setOrdinal(ordinal++);
            record.setRecordId(recordId);
            record.setExecutionStatus("pending");
            String skipCode = recordSkipCode(
                workspaceId, version, actorUserId, recordId, actionNodes);
            if (skipCode == null) {
                record.setPreviewStatus("ready");
                if (samples.size() < MAX_SAMPLES) {
                    samples.add(new WorkflowManualPreparationDto.Sample(
                        recordId, recordLabel(version.getRecordType(), recordId)));
                }
            } else {
                record.setPreviewStatus("skipped");
                record.setPreviewReasonCode(skipCode);
                record.setExecutionStatus("skipped");
                if ("record_not_found".equals(skipCode)) {
                    missingReferences++;
                } else {
                    configurationSkips++;
                }
            }
            records.add(record);
        }
        byte[] rawToken = new byte[32];
        SECURE_RANDOM.nextBytes(rawToken);
        String scopeToken = Base64.getUrlEncoder().withoutPadding().encodeToString(rawToken);
        String contractJson = scopeContract(
            request.scope(), resolved, sourceSurface, version.getRecordType());
        byte[] scopeHash = sha256(contractJson.getBytes(StandardCharsets.UTF_8));
        WorkflowInvocation invocation = invocation(
            workspaceId,
            workflow,
            version,
            requesterId,
            sourceSurface,
            resolved,
            scopeToken,
            scopeHash,
            contractJson,
            records);
        operationsMapper.insertInvocation(invocation);
        records.forEach(record -> record.setInvocationId(invocation.getId()));
        if (!records.isEmpty()) {
            operationsMapper.insertInvocationRecords(
                workspaceId, invocation.getId(), records);
        }
        WorkflowManualPreparationDto.ExpectedSkips expectedSkips =
            new WorkflowManualPreparationDto.ExpectedSkips(
                0, 0, missingReferences, 0, configurationSkips);
        return new WorkflowManualPreparationDto(
            invocation.getId(),
            workflow.getId(),
            workflow.getName(),
            version.getId(),
            version.getVersionNumber(),
            HexFormat.of().formatHex(version.getDefinitionHash()),
            version.getExecutionMode(),
            actorUserId,
            resolved.scopeKind(),
            resolved.resolvedKind(),
            sourceSurface,
            version.getRecordType(),
            scopeToken,
            HexFormat.of().formatHex(scopeHash),
            invocation.getExpiresAt(),
            records.size(),
            (int) records.stream().filter(record -> "ready".equals(record.getPreviewStatus())).count(),
            expectedSkips,
            List.copyOf(samples),
            actions,
            blockers.isEmpty() && records.stream().anyMatch(
                record -> "ready".equals(record.getPreviewStatus())),
            List.copyOf(blockers));
    }

    @RequirePermission(Permission.RULE_MANAGE)
    public WorkflowInvocationResultDto confirm(
            int workflowId,
            String idempotencyKey,
            WorkflowManualConfirmRequest request) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int requesterId = workspaceService.getCurrentUserId();
        byte[] tokenHash = sha256(requireToken(request.scopeToken()).getBytes(StandardCharsets.UTF_8));
        byte[] scopeHash = requireHash(request.scopeHash());
        byte[] confirmationKey = requireUuid(idempotencyKey);
        WorkflowInvocation invocation = confirmationTransaction.confirm(
            workspaceId,
            workflowId,
            requesterId,
            tokenHash,
            scopeHash,
            confirmationKey);
        if (TERMINAL_INVOCATION_STATUSES.contains(invocation.getStatus())) {
            return result(workspaceId, workflowId, invocation.getId());
        }
        List<WorkflowInvocationRecord> records = operationsMapper.getInvocationRecords(
            workspaceId, invocation.getId());
        for (WorkflowInvocationRecord record : records) {
            if ("ready".equals(record.getPreviewStatus())
                    && record.getWorkflowRunId() == null
                    && "pending".equals(record.getExecutionStatus())) {
                dispatchTransaction.dispatch(
                    workspaceId,
                    workflowId,
                    invocation.getWorkflowVersionId(),
                    invocation.getId(),
                    record.getRecordId());
            }
        }
        operationsMapper.markInvocationRunning(workspaceId, invocation.getId());
        return result(workspaceId, workflowId, invocation.getId());
    }

    @Transactional
    @RequirePermission(Permission.RULE_MANAGE)
    public WorkflowInvocationResultDto get(int workflowId, long invocationId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        return result(workspaceId, workflowId, invocationId);
    }

    @Transactional
    @RequirePermission(Permission.RULE_MANAGE)
    public WorkflowInvocationResultDto cancel(int workflowId, long invocationId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        WorkflowInvocationResultDto current = result(workspaceId, workflowId, invocationId);
        if (terminalInvocation(current.status())) {
            return current;
        }
        WorkflowInvocation invocation = requireInvocation(
            workspaceId, workflowId, invocationId);
        for (WorkflowInvocationRecord record : operationsMapper.getInvocationRecords(
                workspaceId, invocationId)) {
            if (record.getWorkflowRunId() != null
                    && Set.of("queued", "running", "waiting").contains(
                        record.getExecutionStatus())) {
                runOperationService.cancel(
                    workflowId, "canonical-" + record.getWorkflowRunId());
            }
        }
        operationsMapper.cancelPendingInvocationRecords(workspaceId, invocationId);
        operationsMapper.cancelInvocation(
            workspaceId, invocationId, LocalDateTime.now());
        return result(workspaceId, workflowId, invocationId);
    }

    private WorkflowInvocationResultDto result(
            int workspaceId,
            int workflowId,
            long invocationId) {
        WorkflowInvocation invocation = requireInvocation(
            workspaceId, workflowId, invocationId);
        List<WorkflowInvocationRecord> records = operationsMapper.getInvocationRecords(
            workspaceId, invocationId);
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (WorkflowInvocationRecord record : records) {
            counts.merge(record.getExecutionStatus(), 1, Integer::sum);
        }
        String status = invocationStatus(invocation, counts, records.size());
        LocalDateTime completedAt = null;
        if (terminalInvocation(status)) {
            if (invocation.getCompletedAt() == null) {
                operationsMapper.completeInvocationIfActive(
                    workspaceId, invocationId, status, LocalDateTime.now());
                invocation = requireInvocationForUpdate(
                    workspaceId, workflowId, invocationId);
                status = invocationStatus(invocation, counts, records.size());
            }
            completedAt = invocation.getCompletedAt();
        }
        List<WorkflowInvocationResultDto.RecordResult> recordDtos = records.stream()
            .map(record -> new WorkflowInvocationResultDto.RecordResult(
                record.getRecordId(),
                record.getExecutionStatus(),
                record.getPreviewReasonCode() == null
                    ? record.getExecutionFailureCategory() : record.getPreviewReasonCode(),
                record.getWorkflowRunId() == null
                    ? null : "canonical-" + record.getWorkflowRunId()))
            .toList();
        return new WorkflowInvocationResultDto(
            invocation.getId(),
            status,
            invocation.getExactCount(),
            count(counts, "queued"),
            count(counts, "running"),
            count(counts, "waiting"),
            count(counts, "succeeded"),
            count(counts, "failed"),
            count(counts, "intervention_required"),
            count(counts, "cancelled"),
            count(counts, "skipped"),
            invocation.getCreatedAt(),
            invocation.getConfirmedAt(),
            completedAt,
            recordDtos);
    }

    private ResolvedScope resolveScope(
            WorkflowManualScope scope,
            String sourceSurface,
            int requesterId,
            String recordType) {
        if (scope instanceof WorkflowManualScope.CommandPalette command) {
            if (!"command_palette".equals(sourceSurface)
                    || command.resolvedScope() instanceof WorkflowManualScope.CommandPalette) {
                throw new BadRequestException("Command palette requires one concrete nested scope");
            }
            ResolvedScope nested = resolveScope(
                command.resolvedScope(), "record_list", requesterId, recordType);
            return new ResolvedScope(
                "command_palette", nested.resolvedKind(), nested.ids(), nested.contract());
        }
        if ("command_palette".equals(sourceSurface)) {
            throw new BadRequestException("Command palette requests require a nested scope");
        }
        if (scope instanceof WorkflowManualScope.SingleRecord single) {
            return resolved("single_record", List.of(single.recordId()), scope);
        }
        if (scope instanceof WorkflowManualScope.PageSelection selection) {
            return resolved("page_selection", selection.recordIds(), scope);
        }
        if (scope instanceof WorkflowManualScope.ExplicitSelection selection) {
            return resolved("explicit_selection", selection.recordIds(), scope);
        }
        if (scope instanceof WorkflowManualScope.FilterMatch match) {
            return resolved(
                "filter_match",
                resolveFilterForRecordType(recordType, match.filter(), requesterId),
                scope);
        }
        if (scope instanceof WorkflowManualScope.SmartSegment segment) {
            return resolved(
                "smart_segment",
                segmentService.evaluate(recordType, segment.definition()),
                scope);
        }
        if (scope instanceof WorkflowManualScope.SavedView saved) {
            return resolveSavedView(saved, requesterId, recordType);
        }
        if (scope instanceof WorkflowManualScope.SearchSnapshot search) {
            String query = requireQuery(search.query());
            return resolved(
                "search_snapshot",
                resolveFilterForRecordType(recordType, new WorkflowManualFilter(
                    query, null, null, null, false, null, null, null,
                    null, null, null, null, null), requesterId),
                scope);
        }
        throw new BadRequestException("Unsupported manual workflow scope");
    }

    private ResolvedScope resolveSavedView(
            WorkflowManualScope.SavedView scope,
            int requesterId,
            String recordType) {
        SavedView view = savedViewService.getById(scope.savedViewId());
        if (!recordType.equals(view.getRecordType())) {
            throw new BadRequestException("Saved view record type does not match the workflow");
        }
        JsonNode config = view.getConfig();
        Set<Integer> matches = new HashSet<>();
        JsonNode segments = config == null ? null : config.get("segments");
        if (segments != null && !segments.isNull()) {
            try {
                SegmentDefinition definition = objectMapper.treeToValue(
                    segments, SegmentDefinition.class);
                matches.addAll(segmentService.evaluate(view.getRecordType(), definition));
            } catch (Exception exception) {
                throw new BadRequestException("Saved view scope is malformed");
            }
        }
        WorkflowManualFilter filter = filterFromSavedView(config);
        List<Integer> nativeMatches = resolveFilterForRecordType(
            view.getRecordType(), filter, requesterId);
        List<Integer> ids;
        if (matches.isEmpty()) {
            ids = nativeMatches;
        } else if (hasNativeFilter(filter)) {
            ids = nativeMatches.stream().filter(matches::contains).toList();
        } else {
            ids = matches.stream().sorted().toList();
        }
        return resolved("saved_view", ids, scope);
    }

    private List<Integer> resolveFilterForRecordType(
            String recordType,
            WorkflowManualFilter filter,
            int requesterId) {
        WorkflowManualFilter resolved = filter == null
            ? new WorkflowManualFilter(
                null, null, null, null, false, null, null, null,
                null, null, null, null, null)
            : filter;
        MemberScope memberScope = memberScopeResolver.resolve(
            resolved.memberScope(), resolved.memberIds(), requesterId);
        return switch (recordType) {
            case "person" -> personService.getMatchingPersonIds(
                blankToNull(resolved.query()),
                resolved.companies(),
                resolved.titles(),
                Boolean.TRUE.equals(resolved.noCompany()),
                memberScope,
                false);
            case "company" -> companyService.getMatchingCompanyIds(
                blankToNull(resolved.query()),
                resolved.industry(),
                false,
                null,
                memberScope,
                false);
            case "deal" -> dealService.getMatchingDealIds(
                blankToNull(resolved.query()),
                blankToNull(resolved.currency()),
                resolved.pipelineIds(),
                resolved.stageIds(),
                resolved.companyIds(),
                Boolean.TRUE.equals(resolved.noCompany()),
                resolved.statuses(),
                resolved.risks(),
                memberScope);
            default -> throw new BadRequestException("Unsupported workflow record type");
        };
    }

    private ResolvedScope resolved(
            String kind,
            List<Integer> rawIds,
            WorkflowManualScope contract) {
        if (rawIds == null) {
            throw new BadRequestException("Manual workflow scope requires record ids");
        }
        List<Integer> ids = rawIds.stream()
            .peek(id -> {
                if (id == null || id < 1) {
                    throw new BadRequestException("Manual workflow record ids must be positive");
                }
            })
            .distinct()
            .sorted()
            .toList();
        if (ids.size() > MAX_RECORDS) {
            throw new BadRequestException("Manual workflow scope exceeds 1000 records");
        }
        return new ResolvedScope(kind, kind, ids, contract);
    }

    private Workflow requireWorkflow(int workspaceId, int workflowId) {
        Workflow workflow = workflowMapper.getById(workspaceId, workflowId);
        if (workflow == null) {
            throw new ResourceNotFoundException("Workflow not found");
        }
        return workflow;
    }

    private WorkflowVersion requireActiveVersion(int workspaceId, Workflow workflow) {
        if (workflow.getActiveVersionId() == null) {
            throw new ConflictException("Workflow has no active version");
        }
        WorkflowVersion version = workflowVersionMapper.getById(
            workspaceId, workflow.getId(), workflow.getActiveVersionId());
        if (version == null) {
            throw new ConflictException("Workflow active version is unavailable");
        }
        return version;
    }

    private List<String> operationalBlockers(
            Workflow workflow,
            WorkflowVersion version,
            int actorUserId,
            List<Integer> recordIds) {
        List<String> blockers = new ArrayList<>();
        if (workflow.getArchivedAt() != null) {
            blockers.add("workflow_archived");
        }
        if (!workflow.isEnabled()) {
            blockers.add("workflow_disabled");
        }
        if (workflow.getIntakePausedAt() != null) {
            blockers.add("workflow_paused");
        }
        if (!"canonical".equals(workflow.getRuntimeOwner())) {
            blockers.add("workflow_not_canonical");
        }
        if (workspaceService.getRole(workflow.getWorkspaceId(), actorUserId) == null
                && !"system".equals(version.getExecutionMode())) {
            blockers.add("actor_unavailable");
        }
        if (recordIds.isEmpty()) {
            blockers.add("scope_empty");
        }
        return blockers;
    }

    private String recordSkipCode(
            int workspaceId,
            WorkflowVersion version,
            int actorUserId,
            int recordId,
            List<WorkflowNode.Action> actions) {
        try {
            recordGuard.requireAccessible(workspaceId, version.getRecordType(), recordId);
        } catch (WorkflowExecutionException exception) {
            return "record_not_found";
        }
        for (WorkflowNode.Action node : actions) {
            WorkflowDiagnosticDto blocker = actionGuard.blocker(
                workspaceId,
                actorUserId,
                version.getRecordType(),
                recordId,
                node.id(),
                node.config());
            if (blocker != null) {
                return switch (blocker.code()) {
                    case ACTION_PERMISSION_MISSING -> "action_permission_missing";
                    case ACTION_TARGET_MEMBER_UNAVAILABLE -> "actor_unavailable";
                    default -> "configuration_missing";
                };
            }
        }
        return null;
    }

    private List<WorkflowManualPreparationDto.Action> actions(WorkflowDefinition definition) {
        return actionNodes(definition).stream()
            .map(node -> new WorkflowManualPreparationDto.Action(
                node.id(),
                node.config().getType(),
                retryPolicy.safety(node.config()).value()))
            .toList();
    }

    private static List<WorkflowNode.Action> actionNodes(WorkflowDefinition definition) {
        return definition.nodes().stream()
            .filter(WorkflowNode.Action.class::isInstance)
            .map(WorkflowNode.Action.class::cast)
            .toList();
    }

    private int actorUserId(WorkflowVersion version) {
        Integer actor = "system".equals(version.getExecutionMode())
            ? systemActor.user().getId() : version.getRunAsUserId();
        if (actor == null || actor < 1) {
            throw new ConflictException("Workflow actor is unavailable");
        }
        return actor;
    }

    private WorkflowInvocation invocation(
            int workspaceId,
            Workflow workflow,
            WorkflowVersion version,
            int requesterId,
            String sourceSurface,
            ResolvedScope resolved,
            String scopeToken,
            byte[] scopeHash,
            String contractJson,
            List<WorkflowInvocationRecord> records) {
        WorkflowInvocation invocation = new WorkflowInvocation();
        invocation.setWorkspaceId(workspaceId);
        invocation.setWorkflowId(workflow.getId());
        invocation.setWorkflowVersionId(version.getId());
        invocation.setRequestedById(requesterId);
        invocation.setScopeKind(resolved.scopeKind());
        invocation.setResolvedScopeKind(resolved.resolvedKind());
        invocation.setSourceSurface(sourceSurface);
        invocation.setRecordType(version.getRecordType());
        invocation.setScopeTokenHash(sha256(scopeToken.getBytes(StandardCharsets.UTF_8)));
        invocation.setScopeHash(scopeHash);
        invocation.setScopeContractJson(contractJson);
        invocation.setExactCount(records.size());
        int ready = (int) records.stream()
            .filter(record -> "ready".equals(record.getPreviewStatus())).count();
        invocation.setReadyCount(ready);
        invocation.setSkippedCount(records.size() - ready);
        invocation.setStatus("prepared");
        invocation.setExpiresAt(LocalDateTime.now().plusMinutes(15));
        return invocation;
    }

    private String scopeContract(
            WorkflowManualScope original,
            ResolvedScope resolved,
            String sourceSurface,
            String recordType) {
        Map<String, Object> contract = new LinkedHashMap<>();
        contract.put("scopeKind", resolved.scopeKind());
        contract.put("resolvedScopeKind", resolved.resolvedKind());
        contract.put("sourceSurface", sourceSurface);
        contract.put("recordType", recordType);
        contract.put("recordIds", resolved.ids());
        contract.put("request", original);
        try {
            String json = objectMapper.writeValueAsString(contract);
            if (json.getBytes(StandardCharsets.UTF_8).length > 16_384) {
                throw new BadRequestException("Manual workflow scope is too large");
            }
            return json;
        } catch (BadRequestException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BadRequestException("Manual workflow scope is malformed");
        }
    }

    private String recordLabel(String recordType, int recordId) {
        String label = switch (recordType) {
            case "person" -> label(personService.getPersonById(recordId));
            case "company" -> label(companyService.getCompanyById(recordId));
            case "deal" -> label(dealService.getDealById(recordId));
            default -> Integer.toString(recordId);
        };
        String safe = label == null || label.isBlank() ? Integer.toString(recordId) : label.trim();
        return safe.length() <= MAX_LABEL_LENGTH ? safe : safe.substring(0, MAX_LABEL_LENGTH);
    }

    private static String label(Person person) {
        return person.getName();
    }

    private static String label(Company company) {
        return company.getName();
    }

    private static String label(Deal deal) {
        return deal.getName();
    }

    private WorkflowManualFilter filterFromSavedView(JsonNode config) {
        if (config == null || config.isNull()) {
            return new WorkflowManualFilter(
                null, null, null, null, false, null, null, null,
                null, null, null, null, null);
        }
        JsonNode filters = config.get("filters");
        return new WorkflowManualFilter(
            text(config.get("query")),
            textValues(filters, "company"),
            textValues(filters, "title"),
            textValues(filters, "industry"),
            booleanValue(filters, "noCompany"),
            firstValue(filters, "currency"),
            integerValues(filters, "pipelineId"),
            integerValues(filters, "stageId"),
            integerValues(filters, "companyId"),
            textValues(filters, "status"),
            textValues(filters, "risk"),
            firstValue(filters, "scope"),
            integerValues(filters, "memberId"));
    }

    private static boolean hasNativeFilter(WorkflowManualFilter filter) {
        return blankToNull(filter.query()) != null
            || nonempty(filter.companies())
            || nonempty(filter.titles())
            || nonempty(filter.industry())
            || Boolean.TRUE.equals(filter.noCompany())
            || blankToNull(filter.currency()) != null
            || nonempty(filter.pipelineIds())
            || nonempty(filter.stageIds())
            || nonempty(filter.companyIds())
            || nonempty(filter.statuses())
            || nonempty(filter.risks())
            || blankToNull(filter.memberScope()) != null;
    }

    private static <T> boolean nonempty(List<T> values) {
        return values != null && !values.isEmpty();
    }

    private static String text(JsonNode node) {
        return node == null || !node.isTextual() ? null : node.textValue();
    }

    private static List<String> textValues(JsonNode object, String key) {
        JsonNode values = object == null ? null : object.get(key);
        if (values == null || !values.isArray()) {
            return null;
        }
        List<String> result = new ArrayList<>();
        values.forEach(value -> {
            if (value.isTextual() && !value.textValue().isBlank()) {
                result.add(value.textValue());
            }
        });
        return result.isEmpty() ? null : List.copyOf(result);
    }

    private static List<Integer> integerValues(JsonNode object, String key) {
        List<String> values = textValues(object, key);
        if (values == null) {
            return null;
        }
        try {
            return values.stream().map(Integer::valueOf).toList();
        } catch (NumberFormatException exception) {
            throw new BadRequestException("Saved view identifier filter is malformed");
        }
    }

    private static String firstValue(JsonNode object, String key) {
        List<String> values = textValues(object, key);
        return values == null ? null : values.getFirst();
    }

    private static boolean booleanValue(JsonNode object, String key) {
        String value = firstValue(object, key);
        return "true".equals(value);
    }

    private static String requireSourceSurface(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
        if (!SOURCE_SURFACES.contains(normalized)) {
            throw new BadRequestException("Unsupported manual workflow source surface");
        }
        return normalized;
    }

    private static String requireQuery(String value) {
        String query = blankToNull(value);
        if (query == null || query.length() > 1024) {
            throw new BadRequestException("Search scope query is required and must be at most 1024 characters");
        }
        return query;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String requireToken(String value) {
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(value);
            if (decoded.length != 32) {
                throw new IllegalArgumentException("token");
            }
            return value;
        } catch (IllegalArgumentException exception) {
            throw new BadRequestException("Invalid manual workflow scope token");
        }
    }

    private static byte[] requireHash(String value) {
        try {
            byte[] decoded = HexFormat.of().parseHex(value);
            if (decoded.length != 32) {
                throw new IllegalArgumentException("hash");
            }
            return decoded;
        } catch (IllegalArgumentException exception) {
            throw new BadRequestException("Invalid manual workflow scope hash");
        }
    }

    private static byte[] requireUuid(String value) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException("Idempotency-Key is required");
        }
        try {
            UUID uuid = UUID.fromString(value);
            return ByteBuffer.allocate(16)
                .putLong(uuid.getMostSignificantBits())
                .putLong(uuid.getLeastSignificantBits())
                .array();
        } catch (IllegalArgumentException exception) {
            throw new BadRequestException("Idempotency-Key must be a UUID");
        }
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private WorkflowInvocation requireInvocation(
            int workspaceId,
            int workflowId,
            long invocationId) {
        WorkflowInvocation invocation = operationsMapper.getInvocation(
            workspaceId, workflowId, invocationId);
        if (invocation == null) {
            throw new ResourceNotFoundException("Workflow invocation not found");
        }
        return invocation;
    }

    private WorkflowInvocation requireInvocationForUpdate(
            int workspaceId,
            int workflowId,
            long invocationId) {
        WorkflowInvocation invocation = operationsMapper.getInvocationForUpdate(
            workspaceId, workflowId, invocationId);
        if (invocation == null) {
            throw new ResourceNotFoundException("Workflow invocation not found");
        }
        return invocation;
    }

    private static String invocationStatus(
            WorkflowInvocation invocation,
            Map<String, Integer> counts,
            int total) {
        if (TERMINAL_INVOCATION_STATUSES.contains(invocation.getStatus())) {
            return invocation.getStatus();
        }
        if ("prepared".equals(invocation.getStatus())) {
            return "prepared";
        }
        int nonterminal = count(counts, "pending") + count(counts, "queued")
            + count(counts, "running") + count(counts, "waiting");
        if (nonterminal > 0) {
            return "prepared".equals(invocation.getStatus())
                ? "prepared" : "running";
        }
        int successes = count(counts, "succeeded");
        int adverse = total - successes;
        if (successes == total && total > 0) {
            return "succeeded";
        }
        if (successes > 0 && adverse > 0) {
            return "partial";
        }
        return "failed";
    }

    private static boolean terminalInvocation(String status) {
        return TERMINAL_INVOCATION_STATUSES.contains(status);
    }

    private static int count(Map<String, Integer> counts, String key) {
        return counts.getOrDefault(key, 0);
    }

    private record ResolvedScope(
        String scopeKind,
        String resolvedKind,
        List<Integer> ids,
        WorkflowManualScope contract
    ) { }
}
