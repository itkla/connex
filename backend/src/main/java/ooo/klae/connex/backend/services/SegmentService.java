package ooo.klae.connex.backend.services;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.PersonEdge;
import ooo.klae.connex.backend.dto.DealRiskDto;
import ooo.klae.connex.backend.dto.RecordLabelDto;
import ooo.klae.connex.backend.dto.RelationshipTemperatureDto;
import ooo.klae.connex.backend.dto.SegmentCatalogDto;
import ooo.klae.connex.backend.dto.SegmentCondition;
import ooo.klae.connex.backend.dto.SegmentDefinition;
import ooo.klae.connex.backend.dto.SegmentFieldsDto;
import ooo.klae.connex.backend.dto.TagDto;
import ooo.klae.connex.backend.dto.WorkflowDiagnosticCode;
import ooo.klae.connex.backend.dto.WorkflowDiagnosticDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.WorkflowDefinitionValidationException;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.SegmentMapper;
import ooo.klae.connex.backend.mappers.TagMapper;
import ooo.klae.connex.backend.util.LikePattern;

/**
 * Evaluates a {@link SegmentDefinition} to the ids of matching records, scoped to the active
 * workspace and the current user. A definition combines conditions and nested groups with
 * {@code match} ({@code "all"} = intersection, {@code "any"} = union); each condition is a
 * graph-aware predicate or a field comparison, optionally negated (complemented within the
 * workspace's records of that type). Supported record types: {@code company}, {@code person},
 * {@code deal}.
 *
 * <p>Field conditions are validated against a kind-typed catalog (string / number / id / enum / tag /
 * date) before any SQL runs, and dispatched to the per-record-type {@code *IdsMatching} statements;
 * negatives are expressed as a positive operator plus {@code negate}. Predicates declare their
 * applicable record types in {@link SegmentCatalog} and dispatch per type: the graph/temperature
 * signals ({@code warm_intro_available}, {@code open_deal}, {@code cooling}) are company-only, the
 * existence signals ({@code has_open_task}, {@code recent_meeting}, …) apply to person/deal, the
 * warmth signals ({@code warmth_hot}…{@code going_cold}) apply to company/person via
 * {@link ScoringService}, and the deal-risk signals ({@code at_risk}, {@code risk_stalled}, …) apply
 * to deals via {@link DealRiskService}. Each computed signal is evaluated once per evaluation and
 * reused across sibling conditions. This shared model is the rule engine's {@code WHEN}.
 */
@Service
@RequiredArgsConstructor
public class SegmentService {

    private final WorkspaceService workspaceService;
    private final AuthService authService;
    private final ScoringService scoringService;
    private final DealRiskService dealRiskService;
    private final SegmentMapper segmentMapper;
    private final PersonEdgeReadService edgeReader;
    private final PersonMapper personMapper;
    private final TagMapper tagMapper;
    private final SegmentCatalog catalog;

    private static final int STRONG_EDGE = 2;

    /**
     * Returns the ids of records matching the definition, scoped to the active session's workspace
     * and user.
     */
    public List<Integer> evaluate(String recordType, SegmentDefinition definition) {
        return evaluate(workspaceService.getCurrentWorkspaceId(), authService.getCurrentUser().getId(), recordType, definition);
    }

    /**
     * Session-free evaluation for off-request callers (e.g. the rule engine): the workspace and the
     * user are supplied explicitly rather than resolved from the security/tenant context.
     */
    public List<Integer> evaluate(int workspaceId, int userId, String recordType, SegmentDefinition definition) {
        return evaluate(workspaceId, userId, recordType, definition, false);
    }

    /** Returns at most {@code limit} deterministic matches for a bounded automation enrollment. */
    public List<Integer> evaluate(
            int workspaceId,
            int userId,
            String recordType,
            SegmentDefinition definition,
            int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("Segment evaluation limit must be positive");
        }
        return evaluate(workspaceId, userId, recordType, definition, false).stream()
            .limit(limit)
            .toList();
    }

    /**
     * Session-free campaign evaluation that includes processing-restricted people in the candidate
     * set so the caller can classify them explicitly. Normal smart-segment and rule evaluation
     * continues to exclude suspended people through {@link #evaluate(int, int, String, SegmentDefinition)}.
     */
    public List<Integer> evaluateIncludingRestrictedPeople(
            int workspaceId, int userId, String recordType, SegmentDefinition definition) {
        return evaluate(workspaceId, userId, recordType, definition, true);
    }

    private List<Integer> evaluate(
            int workspaceId, int userId, String recordType, SegmentDefinition definition,
            boolean includeRestrictedPeople) {
        String type = requireSupported(recordType);
        if (definition == null) {
            return List.of();
        }
        int total = countConditions(definition, 1);
        if (total > catalog.maxConditions()) {
            throw new BadRequestException("A rule may reference at most " + catalog.maxConditions() + " conditions");
        }
        EvalContext ctx = new EvalContext(workspaceId, userId, type, includeRestrictedPeople, null);
        Set<Integer> result = evaluateGroup(definition, ctx, 1);
        return new ArrayList<>(result);
    }

    /**
     * Whether a single record matches the definition, evaluated for that record alone. Field and
     * graph conditions run as usual and are tested for membership, while the temperature- and
     * risk-based signals score only this record (via {@link ScoringService}/{@link DealRiskService}
     * scoped overloads) rather than the whole workspace. Semantically equal to
     * {@code evaluate(...).contains(entityId)} but bounded to the one record — the rule engine uses it
     * for its after-commit per-entity check so a computed {@code WHEN} does not score the workspace on
     * every event.
     */
    public boolean matchesEntity(
            int workspaceId, int userId, String recordType, SegmentDefinition definition, int entityId) {
        String type = requireSupported(recordType);
        if (definition == null) {
            return false;
        }
        countConditions(definition, 1);
        if (!segmentMapper.entityIdInWorkspace(workspaceId, type, entityId)) {
            return false;
        }
        EvalContext ctx = new EvalContext(workspaceId, userId, type, false, Set.of(entityId));
        return matchesGroup(definition, ctx, entityId, 1);
    }

    private boolean matchesGroup(SegmentDefinition group, EvalContext ctx, int entityId, int depth) {
        if (depth > catalog.maxDepth()) {
            throw new BadRequestException("Conditions are nested too deeply (max " + catalog.maxDepth() + " levels)");
        }
        String match = normalize(group.getMatch());
        boolean any = "any".equals(match);
        if (!any && !"all".equals(match)) {
            throw new BadRequestException("Invalid match (expected 'all' or 'any'): " + group.getMatch());
        }
        List<SegmentCondition> conditions = group.getConditions() == null ? List.of() : group.getConditions();
        List<SegmentDefinition> groups = group.getGroups() == null ? List.of() : group.getGroups();
        if (conditions.isEmpty() && groups.isEmpty()) {
            throw new BadRequestException("A condition group requires at least one condition or nested group");
        }
        boolean matched = !any;
        for (SegmentCondition condition : conditions) {
            boolean hit = conditionMatchesEntity(condition, ctx, entityId);
            matched = any ? matched || hit : matched && hit;
        }
        for (SegmentDefinition nested : groups) {
            boolean hit = matchesGroup(nested, ctx, entityId, depth + 1);
            matched = any ? matched || hit : matched && hit;
        }
        return group.isNegate() != matched;
    }

    private boolean conditionMatchesEntity(SegmentCondition condition, EvalContext ctx, int entityId) {
        boolean matched = evaluateCondition(condition, ctx).contains(entityId);
        return condition.isNegate() != matched;
    }

    private int countConditions(SegmentDefinition group, int depth) {
        if (depth > catalog.maxDepth()) {
            throw new BadRequestException("Conditions are nested too deeply (max " + catalog.maxDepth() + " levels)");
        }
        int count = group.getConditions() == null ? 0 : group.getConditions().size();
        if (group.getGroups() != null) {
            for (SegmentDefinition nested : group.getGroups()) {
                count += countConditions(nested, depth + 1);
            }
        }
        return count;
    }

    /**
     * The field value-options that power the builder: distinct industry values (company only) and
     * the workspace's tags (shared across record types).
     */
    public SegmentFieldsDto fields(String recordType) {
        String type = requireSupported(recordType);
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        List<TagDto> tags = tagMapper.getAllTags(workspaceId).stream().map(TagDto::from).toList();
        List<String> industries = "company".equals(type) ? segmentMapper.distinctIndustries(workspaceId) : List.of();
        return new SegmentFieldsDto(industries, tags);
    }

    /**
     * The static, workspace-independent shape of the builder for a record type: its fields (kind,
     * value source, legal operators), the predicates that apply to it, the enum option sets, and the
     * definition shape limits. Carries no workspace data, so it is safe to cache; the client renders
     * the builder generically from it and pairs it with the workspace value options from
     * {@link #fields(String)}.
     */
    public SegmentCatalogDto catalog(String recordType) {
        String type = requireSupported(recordType);
        List<SegmentCatalogDto.CatalogField> fields = new ArrayList<>();
        Map<String, List<String>> enums = new LinkedHashMap<>();
        for (SegmentCatalog.FieldSpec spec : catalog.fields(type)) {
            fields.add(new SegmentCatalogDto.CatalogField(
                spec.field(),
                spec.kind().name().toLowerCase(),
                spec.valueSource().name().toLowerCase(),
                catalog.operatorsFor(spec.kind())));
            if (spec.kind() == SegmentCatalog.Kind.ENUM) {
                enums.put(spec.field(), catalog.enumOptions(spec.field()));
            }
        }
        List<SegmentCatalogDto.CatalogPredicate> predicates = new ArrayList<>();
        for (SegmentCatalog.PredicateSpec spec : catalog.predicates()) {
            if (spec.recordTypes().contains(type)) {
                predicates.add(new SegmentCatalogDto.CatalogPredicate(
                    spec.key(),
                    List.copyOf(spec.recordTypes()),
                    spec.acceptsDays(),
                    spec.acceptsDays() ? spec.defaultDays() : null,
                    spec.acceptsDays() ? spec.minDays() : null,
                    spec.acceptsDays() ? spec.maxDays() : null));
            }
        }
        SegmentCatalogDto.CatalogLimits limits = new SegmentCatalogDto.CatalogLimits(
            catalog.maxConditions(), catalog.maxGroupConditions(), catalog.maxGroups(), catalog.maxDepth());
        return new SegmentCatalogDto(type, fields, predicates, enums, limits);
    }

    /** id + display label for a bounded set of records of {@code recordType}, for a preview sample. */
    public List<RecordLabelDto> labels(String recordType, List<Integer> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        return switch (requireSupported(recordType)) {
            case "person" -> segmentMapper.personLabels(workspaceId, ids);
            case "deal" -> segmentMapper.dealLabels(workspaceId, ids);
            default -> segmentMapper.companyLabels(workspaceId, ids);
        };
    }

    /**
     * Validates a definition's shape and legality — record type, depth, total count, each group's
     * match and non-emptiness, and every condition's field/operator/value against the catalog —
     * without executing any SQL. Used to reject a semantically invalid WHEN at rule-authoring time so
     * it fails loudly there instead of silently never matching at evaluation.
     */
    public void validate(String recordType, SegmentDefinition definition) {
        String type = requireSupported(recordType);
        if (definition == null) {
            return;
        }
        int total = countValidationConditions(definition, 1, "");
        if (total > catalog.maxConditions()) {
            throw invalid(
                WorkflowDiagnosticCode.CONDITION_LIMIT_EXCEEDED,
                "A rule may reference at most " + catalog.maxConditions() + " conditions",
                null,
                Map.of("maximum", Integer.toString(catalog.maxConditions())));
        }
        validateGroup(definition, type, "");
    }

    private int countValidationConditions(
            SegmentDefinition group, int depth, String path) {
        if (depth > catalog.maxDepth()) {
            throw invalid(
                WorkflowDiagnosticCode.CONDITION_DEPTH_EXCEEDED,
                "Conditions are nested too deeply (max " + catalog.maxDepth() + " levels)",
                path.isBlank() ? "groups" : path,
                Map.of("maximum", Integer.toString(catalog.maxDepth())));
        }
        int count = group.getConditions() == null ? 0 : group.getConditions().size();
        List<SegmentDefinition> groups = group.getGroups() == null
            ? List.of() : group.getGroups();
        for (int index = 0; index < groups.size(); index++) {
            String nestedPath = indexed(path, "groups", index);
            count += countValidationConditions(groups.get(index), depth + 1, nestedPath);
        }
        return count;
    }

    private void validateGroup(
            SegmentDefinition group, String recordType, String path) {
        String match = normalize(group.getMatch());
        if (!"any".equals(match) && !"all".equals(match)) {
            throw invalid(
                WorkflowDiagnosticCode.CONDITION_MATCH_INVALID,
                "Invalid match (expected 'all' or 'any'): " + group.getMatch(),
                property(path, "match"), Map.of());
        }
        List<SegmentCondition> conditions = group.getConditions() == null ? List.of() : group.getConditions();
        List<SegmentDefinition> groups = group.getGroups() == null ? List.of() : group.getGroups();
        if (conditions.isEmpty() && groups.isEmpty()) {
            throw invalid(
                WorkflowDiagnosticCode.CONDITION_GROUP_EMPTY,
                "A condition group requires at least one condition or nested group",
                path.isBlank() ? null : path, Map.of());
        }
        for (int index = 0; index < conditions.size(); index++) {
            validateCondition(
                conditions.get(index), recordType, indexed(path, "conditions", index));
        }
        for (int index = 0; index < groups.size(); index++) {
            validateGroup(groups.get(index), recordType, indexed(path, "groups", index));
        }
    }

    private void validateCondition(
            SegmentCondition condition, String recordType, String path) {
        if (condition == null) {
            throw invalid(
                WorkflowDiagnosticCode.CONFIG_FIELD_INVALID,
                "Condition configuration is invalid", path, Map.of());
        }
        String type = normalize(condition.getType());
        if ("predicate".equals(type)) {
            String key = normalize(condition.getKey());
            if (key == null || !catalog.isPredicate(key)) {
                throw invalid(
                    WorkflowDiagnosticCode.CONDITION_PREDICATE_UNKNOWN,
                    "Unknown predicate: " + condition.getKey(),
                    property(path, "key"), Map.of());
            }
            if (!catalog.predicateAppliesTo(key, recordType)) {
                throw invalid(
                    WorkflowDiagnosticCode.CONDITION_PREDICATE_RECORD_TYPE_UNSUPPORTED,
                    "Predicate '" + condition.getKey() + "' is not available for "
                        + recordType + " records",
                    property(path, "key"), Map.of("recordType", recordType));
            }
            return;
        }
        if ("field".equals(type)) {
            String field = normalize(condition.getField());
            String op = normalize(condition.getOp());
            if (field == null) {
                throw invalid(
                    WorkflowDiagnosticCode.CONDITION_FIELD_REQUIRED,
                    "Field condition requires 'field'",
                    property(path, "field"), Map.of());
            }
            if (op == null) {
                throw invalid(
                    WorkflowDiagnosticCode.CONDITION_OPERATOR_REQUIRED,
                    "Field condition requires 'op'",
                    property(path, "op"), Map.of());
            }
            SegmentCatalog.Kind kind = catalog.fieldKind(recordType, field);
            if (kind == null) {
                throw invalid(
                    WorkflowDiagnosticCode.CONDITION_FIELD_UNKNOWN,
                    "Unknown field for " + recordType + ": " + field,
                    property(path, "field"), Map.of("recordType", recordType));
            }
            if (!catalog.operatorsFor(kind).contains(op)) {
                throw invalid(
                    WorkflowDiagnosticCode.CONDITION_OPERATOR_UNSUPPORTED,
                    "Unsupported operator for '" + field + "': " + condition.getOp(),
                    property(path, "op"), Map.of());
            }
            try {
                bindValue(kind, op, condition, new HashMap<>());
            } catch (BadRequestException exception) {
                String valueField = "in".equals(op) ? "values"
                    : "within_days".equals(op) ? "days" : "value";
                WorkflowDiagnosticCode code = missingConditionValue(condition, valueField)
                    ? WorkflowDiagnosticCode.CONDITION_VALUE_REQUIRED
                    : WorkflowDiagnosticCode.CONDITION_VALUE_INVALID;
                throw invalid(
                    code,
                    exception.getMessage(),
                    property(path, valueField), Map.of());
            }
            return;
        }
        throw invalid(
            WorkflowDiagnosticCode.CONDITION_TYPE_INVALID,
            "Unknown condition type (expected 'predicate' or 'field'): " + condition.getType(),
            property(path, "type"), Map.of());
    }

    private Set<Integer> evaluateGroup(SegmentDefinition group, EvalContext ctx, int depth) {
        Set<Integer> matched = combineMembers(group, ctx, depth);
        return group.isNegate() ? complement(matched, ctx) : matched;
    }

    private Set<Integer> combineMembers(SegmentDefinition group, EvalContext ctx, int depth) {
        if (depth > catalog.maxDepth()) {
            throw new BadRequestException("Conditions are nested too deeply (max " + catalog.maxDepth() + " levels)");
        }
        String match = normalize(group.getMatch());
        boolean any = "any".equals(match);
        if (!any && !"all".equals(match)) {
            throw new BadRequestException("Invalid match (expected 'all' or 'any'): " + group.getMatch());
        }
        List<SegmentCondition> conditions = group.getConditions() == null ? List.of() : group.getConditions();
        List<SegmentDefinition> groups = group.getGroups() == null ? List.of() : group.getGroups();
        if (conditions.isEmpty() && groups.isEmpty()) {
            throw new BadRequestException("A condition group requires at least one condition or nested group");
        }

        Set<Integer> result = null;
        Set<String> seen = new HashSet<>();
        for (SegmentCondition condition : conditions) {
            if (!seen.add(signature(condition))) {
                continue;
            }
            Set<Integer> matched = evaluateCondition(condition, ctx);
            if (condition.isNegate()) {
                matched = complement(matched, ctx);
            }
            result = combine(result, matched, any);
            if (result.isEmpty() && !any) {
                return result;
            }
        }
        for (SegmentDefinition nested : groups) {
            result = combine(result, evaluateGroup(nested, ctx, depth + 1), any);
            if (result.isEmpty() && !any) {
                return result;
            }
        }
        return result == null ? new HashSet<>() : result;
    }

    private static Set<Integer> combine(Set<Integer> result, Set<Integer> matched, boolean any) {
        if (result == null) {
            return new HashSet<>(matched);
        }
        if (any) {
            result.addAll(matched);
        } else {
            result.retainAll(matched);
        }
        return result;
    }

    private Set<Integer> evaluateCondition(SegmentCondition condition, EvalContext ctx) {
        String type = normalize(condition.getType());
        if ("predicate".equals(type)) {
            return evaluatePredicate(condition, ctx);
        }
        if ("field".equals(type)) {
            return evaluateField(condition, ctx);
        }
        throw new BadRequestException("Unknown condition type (expected 'predicate' or 'field'): " + condition.getType());
    }

    private Set<Integer> evaluatePredicate(SegmentCondition condition, EvalContext ctx) {
        String key = normalize(condition.getKey());
        if (key == null || !catalog.isPredicate(key)) {
            throw new BadRequestException("Unknown predicate: " + condition.getKey());
        }
        if (!catalog.predicateAppliesTo(key, ctx.recordType())) {
            throw new BadRequestException(
                "Predicate '" + condition.getKey() + "' is not available for " + ctx.recordType() + " records");
        }
        int workspaceId = ctx.workspaceId();
        return switch (key) {
            case "open_deal" -> new HashSet<>(segmentMapper.companyIdsWithOpenDeal(workspaceId));
            case "no_activity" -> new HashSet<>(segmentMapper.companyIdsNoActivitySince(
                workspaceId, catalog.clampDays(condition.getDays())));
            case "cooling" -> coolingCompanyIds(ctx);
            case "warm_intro_available" -> warmIntroCompanyIds(workspaceId, ctx.userId());
            case "has_open_task", "overdue_task", "recent_meeting", "has_note", "has_attachment" ->
                existencePredicate(key, condition, ctx);
            case "warmth_hot" -> warmthBand(ctx, "hot");
            case "warmth_warm" -> warmthBand(ctx, "warm");
            case "warmth_cool" -> warmthBand(ctx, "cool");
            case "warmth_cold" -> warmthBand(ctx, "cold");
            case "warmth_rising" -> warmthTrend(ctx, "rising");
            case "going_cold" -> goingCold(ctx, catalog.clampDays(condition.getDays()));
            case "at_risk" -> dealRiskLevel(ctx, Set.of("high", "medium"));
            case "risk_high" -> dealRiskLevel(ctx, Set.of("high"));
            case "risk_close_overdue" -> dealRiskFactor(ctx, "close_overdue");
            case "risk_closing_soon" -> dealRiskFactor(ctx, "closing_soon_quiet");
            case "risk_stalled" -> dealRiskFactor(ctx, "stalled");
            case "risk_stakeholder_cold" -> dealRiskFactor(ctx, "stakeholder_cold");
            case "risk_no_stakeholders" -> dealRiskFactor(ctx, "no_stakeholders");
            default -> throw new BadRequestException("Unknown predicate: " + condition.getKey());
        };
    }

    private Set<Integer> dealRiskLevel(EvalContext ctx, Set<String> levels) {
        Set<Integer> universe = ctx.universe(segmentMapper);
        Set<Integer> ids = new HashSet<>();
        for (DealRiskDto risk : ctx.dealRisks(dealRiskService)) {
            if (levels.contains(risk.getLevel()) && universe.contains(risk.getDealId())) {
                ids.add(risk.getDealId());
            }
        }
        return ids;
    }

    private Set<Integer> dealRiskFactor(EvalContext ctx, String code) {
        Set<Integer> universe = ctx.universe(segmentMapper);
        Set<Integer> ids = new HashSet<>();
        for (DealRiskDto risk : ctx.dealRisks(dealRiskService)) {
            if (risk.getFactors() != null
                    && risk.getFactors().stream().anyMatch(factor -> code.equals(factor.getCode()))
                    && universe.contains(risk.getDealId())) {
                ids.add(risk.getDealId());
            }
        }
        return ids;
    }

    private Set<Integer> warmthBand(EvalContext ctx, String band) {
        Set<Integer> universe = ctx.universe(segmentMapper);
        Set<Integer> ids = new HashSet<>();
        for (RelationshipTemperatureDto temperature : ctx.temperatures(scoringService)) {
            if (band.equals(temperature.getBand()) && universe.contains(temperature.getId())) {
                ids.add(temperature.getId());
            }
        }
        return ids;
    }

    private Set<Integer> warmthTrend(EvalContext ctx, String trend) {
        Set<Integer> universe = ctx.universe(segmentMapper);
        Set<Integer> ids = new HashSet<>();
        for (RelationshipTemperatureDto temperature : ctx.temperatures(scoringService)) {
            if (trend.equals(temperature.getTrend()) && universe.contains(temperature.getId())) {
                ids.add(temperature.getId());
            }
        }
        return ids;
    }

    private Set<Integer> goingCold(EvalContext ctx, int days) {
        Set<Integer> universe = ctx.universe(segmentMapper);
        Set<Integer> ids = new HashSet<>();
        for (RelationshipTemperatureDto temperature : ctx.temperatures(scoringService)) {
            Integer daysUntilCold = temperature.getDaysUntilCold();
            if (daysUntilCold != null && daysUntilCold <= days && universe.contains(temperature.getId())) {
                ids.add(temperature.getId());
            }
        }
        return ids;
    }

    private Set<Integer> existencePredicate(String key, SegmentCondition condition, EvalContext ctx) {
        Map<String, Object> params = new HashMap<>();
        params.put("workspaceId", ctx.workspaceId());
        params.put("predicate", key);
        params.put("days", catalog.clampDays(condition.getDays()));
        params.put("includeRestrictedPeople", ctx.includeRestrictedPeople());
        return new HashSet<>(switch (ctx.recordType()) {
            case "person" -> segmentMapper.personExistence(params);
            case "deal" -> segmentMapper.dealExistence(params);
            case "company" -> segmentMapper.companyExistence(params);
            default -> List.<Integer>of();
        });
    }

    private Set<Integer> evaluateField(SegmentCondition condition, EvalContext ctx) {
        String field = normalize(condition.getField());
        String op = normalize(condition.getOp());
        if (field == null || op == null) {
            throw new BadRequestException("Field condition requires 'field' and 'op'");
        }
        SegmentCatalog.Kind kind = fieldKind(ctx.recordType(), field);
        if (!catalog.operatorsFor(kind).contains(op)) {
            throw new BadRequestException("Unsupported operator for '" + field + "': " + condition.getOp());
        }
        Map<String, Object> params = new HashMap<>();
        params.put("workspaceId", ctx.workspaceId());
        params.put("field", field);
        params.put("op", op);
        params.put("includeRestrictedPeople", ctx.includeRestrictedPeople());
        bindValue(kind, op, condition, params);
        return new HashSet<>(runFieldQuery(ctx.recordType(), params));
    }

    private List<Integer> runFieldQuery(String recordType, Map<String, Object> params) {
        return switch (recordType) {
            case "company" -> segmentMapper.companyIdsMatching(params);
            case "person" -> Boolean.TRUE.equals(params.get("includeRestrictedPeople"))
                    ? segmentMapper.personIdsMatchingIncludingRestricted(params)
                    : segmentMapper.personIdsMatching(params);
            case "deal" -> segmentMapper.dealIdsMatching(params);
            default -> throw new BadRequestException("Fields are not available for record type: " + recordType);
        };
    }

    private void bindValue(SegmentCatalog.Kind kind, String op, SegmentCondition condition, Map<String, Object> params) {
        switch (kind) {
            case STRING -> {
                switch (op) {
                    case "equals" -> params.put("value", requireValue(condition));
                    case "contains" -> params.put("pattern", LikePattern.containing(requireValue(condition)));
                    case "starts_with" -> params.put("pattern", LikePattern.starting(requireValue(condition)));
                    default -> { }
                }
            }
            case NUMBER -> params.put("number", requireNumber(condition));
            case ID -> {
                if ("in".equals(op)) {
                    params.put("ids", requireIds(condition));
                } else {
                    params.put("id", requireId(condition));
                }
            }
            case ENUM -> params.put("value", requireStatus(condition));
            case TAG -> params.put("id", requireId(condition));
            case DATE -> {
                switch (op) {
                    case "before", "after" -> params.put("value", requireDate(condition));
                    case "within_days" -> {
                        LocalDate today = LocalDate.now();
                        params.put("dateFrom", today.toString());
                        params.put("dateTo", today.plusDays(catalog.clampDays(condition.getDays())).toString());
                    }
                    default -> { }
                }
            }
        }
    }

    private Set<Integer> complement(Set<Integer> matched, EvalContext ctx) {
        Set<Integer> complement = new HashSet<>(ctx.universe(segmentMapper));
        complement.removeAll(matched);
        return complement;
    }

    private SegmentCatalog.Kind fieldKind(String recordType, String field) {
        SegmentCatalog.Kind kind = catalog.fieldKind(recordType, field);
        if (kind == null) {
            throw new BadRequestException("Unknown field for " + recordType + ": " + field);
        }
        return kind;
    }

    private Set<Integer> coolingCompanyIds(EvalContext ctx) {
        Set<Integer> universe = ctx.universe(segmentMapper);
        Set<Integer> ids = new HashSet<>();
        for (RelationshipTemperatureDto temperature : ctx.temperatures(scoringService)) {
            if (universe.contains(temperature.getId())
                    && ("cool".equals(temperature.getBand())
                        || "cold".equals(temperature.getBand())
                        || "cooling".equals(temperature.getTrend()))) {
                ids.add(temperature.getId());
            }
        }
        return ids;
    }

    private Set<Integer> warmIntroCompanyIds(int workspaceId, int userId) {
        Set<Integer> engaged = new HashSet<>(personMapper.getEngagedPersonIds(workspaceId));
        if (engaged.isEmpty()) {
            return new HashSet<>();
        }
        Set<Integer> warmlyConnected = new HashSet<>();
        for (PersonEdge edge : edgeReader.getAllEdges(workspaceId)) {
            if (edge.getStrength() < STRONG_EDGE || edge.getSourcePersonId() == edge.getTargetPersonId()) {
                continue;
            }
            if (engaged.contains(edge.getSourcePersonId())) {
                warmlyConnected.add(edge.getTargetPersonId());
            }
            if (engaged.contains(edge.getTargetPersonId())) {
                warmlyConnected.add(edge.getSourcePersonId());
            }
        }
        if (warmlyConnected.isEmpty()) {
            return new HashSet<>();
        }
        return new HashSet<>(segmentMapper.companyIdsForPersonsWithoutUserActivity(
            workspaceId, userId, new ArrayList<>(warmlyConnected)));
    }

    private String requireSupported(String recordType) {
        String type = normalize(recordType);
        if (type == null || !catalog.supportsRecordType(type)) {
            throw new BadRequestException("Smart segments are not available for record type: " + recordType);
        }
        return type;
    }

    private static String requireValue(SegmentCondition condition) {
        String value = condition.getValue();
        if (value == null || value.isBlank()) {
            throw new BadRequestException("Field condition requires a value");
        }
        return value.trim();
    }

    private static double requireNumber(SegmentCondition condition) {
        try {
            return Double.parseDouble(requireValue(condition));
        } catch (NumberFormatException e) {
            throw new BadRequestException("Field condition requires a numeric value");
        }
    }

    private static int requireId(SegmentCondition condition) {
        try {
            return Integer.parseInt(requireValue(condition));
        } catch (NumberFormatException e) {
            throw new BadRequestException("Field condition requires a numeric id");
        }
    }

    private static List<Integer> requireIds(SegmentCondition condition) {
        List<String> values = condition.getValues();
        if (values == null || values.isEmpty()) {
            throw new BadRequestException("An 'in' condition requires at least one value");
        }
        List<Integer> ids = new ArrayList<>();
        for (String value : values) {
            try {
                ids.add(Integer.parseInt(value.trim()));
            } catch (NumberFormatException e) {
                throw new BadRequestException("An 'in' condition requires numeric ids");
            }
        }
        return ids;
    }

    private String requireStatus(SegmentCondition condition) {
        String value = normalize(requireValue(condition));
        if (!catalog.statusValues().contains(value)) {
            throw new BadRequestException("Status must be one of open, won, lost");
        }
        return value;
    }

    private static String requireDate(SegmentCondition condition) {
        String value = requireValue(condition);
        try {
            return LocalDate.parse(value).toString();
        } catch (RuntimeException e) {
            throw new BadRequestException("Date condition requires an ISO date (YYYY-MM-DD)");
        }
    }

    private static String signature(SegmentCondition c) {
        return normalize(c.getType()) + "|" + normalize(c.getKey()) + "|" + c.getDays() + "|"
            + normalize(c.getField()) + "|" + normalize(c.getOp()) + "|" + c.getValue() + "|"
            + c.getValues() + "|" + c.isNegate();
    }

    private static String normalize(String value) {
        return value == null ? null : value.trim().toLowerCase();
    }

    private static boolean missingConditionValue(
            SegmentCondition condition, String field) {
        return switch (field) {
            case "values" -> condition.getValues() == null || condition.getValues().isEmpty();
            case "days" -> condition.getDays() == null;
            default -> condition.getValue() == null || condition.getValue().isBlank();
        };
    }

    private static String indexed(String path, String property, int index) {
        return property(path, property) + "[" + index + "]";
    }

    private static String property(String path, String property) {
        return path == null || path.isBlank() ? property : path + "." + property;
    }

    private static WorkflowDefinitionValidationException invalid(
            WorkflowDiagnosticCode code,
            String message,
            String fieldPath,
            Map<String, String> params) {
        return new WorkflowDefinitionValidationException(
            message,
            new WorkflowDiagnosticDto(code, null, null, fieldPath, params));
    }

    private static final class EvalContext {
        private final int workspaceId;
        private final int userId;
        private final String recordType;
        private final boolean includeRestrictedPeople;
        private final Set<Integer> candidateIds;
        private Set<Integer> universe;
        private List<RelationshipTemperatureDto> temperatures;
        private List<DealRiskDto> dealRisks;

        private EvalContext(int workspaceId, int userId, String recordType, boolean includeRestrictedPeople,
                Set<Integer> candidateIds) {
            this.workspaceId = workspaceId;
            this.userId = userId;
            this.recordType = recordType;
            this.includeRestrictedPeople = includeRestrictedPeople;
            this.candidateIds = candidateIds;
        }

        private int workspaceId() {
            return workspaceId;
        }

        private int userId() {
            return userId;
        }

        private String recordType() {
            return recordType;
        }

        private boolean includeRestrictedPeople() {
            return includeRestrictedPeople;
        }

        private Set<Integer> universe(SegmentMapper mapper) {
            if (universe == null) {
                universe = new HashSet<>(switch (recordType) {
                    case "company" -> mapper.companyIdsInWorkspace(workspaceId);
                    case "person" -> includeRestrictedPeople
                            ? mapper.personIdsInWorkspaceIncludingRestricted(workspaceId)
                            : mapper.personIdsInWorkspace(workspaceId);
                    case "deal" -> mapper.dealIdsInWorkspace(workspaceId);
                    default -> List.<Integer>of();
                });
            }
            return universe;
        }

        /**
         * The relationship temperatures for this record type, computed once per evaluation and reused
         * across every warmth predicate in the definition. Company and person are scored via
         * {@link ScoringService}; other record types have no temperature.
         */
        private List<RelationshipTemperatureDto> temperatures(ScoringService scoring) {
            if (temperatures == null) {
                temperatures = switch (recordType) {
                    case "company" -> candidateIds == null
                            ? scoring.scoreCompanies(workspaceId)
                            : scoring.scoreCompanies(workspaceId, candidateIds);
                    case "person" -> candidateIds == null
                            ? scoring.scoreContacts(workspaceId)
                            : scoring.scoreContacts(workspaceId, candidateIds);
                    default -> List.of();
                };
            }
            return temperatures;
        }

        /**
         * The risk assessments for the workspace's open deals, computed once per evaluation and reused
         * across every deal-risk predicate. Only the {@code deal} record type has risk assessments;
         * when scoped to a candidate set, only those deals are assessed.
         */
        private List<DealRiskDto> dealRisks(DealRiskService riskService) {
            if (dealRisks == null) {
                if (!"deal".equals(recordType)) {
                    dealRisks = List.of();
                } else {
                    dealRisks = candidateIds == null
                            ? riskService.assessWorkspace(workspaceId)
                            : riskService.assessDeals(workspaceId, new ArrayList<>(candidateIds));
                }
            }
            return dealRisks;
        }
    }
}
