package ooo.klae.connex.backend.services;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.PersonEdge;
import ooo.klae.connex.backend.dto.RecordLabelDto;
import ooo.klae.connex.backend.dto.RelationshipTemperatureDto;
import ooo.klae.connex.backend.dto.SegmentCondition;
import ooo.klae.connex.backend.dto.SegmentDefinition;
import ooo.klae.connex.backend.dto.SegmentFieldsDto;
import ooo.klae.connex.backend.dto.TagDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.mappers.PersonEdgeMapper;
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
 * negatives are expressed as a positive operator plus {@code negate}. Predicates
 * ({@code warm_intro_available}, {@code open_deal}, {@code cooling}, {@code no_activity}) remain
 * company-only, since they are graph- and temperature-derived; {@code cooling} reuses
 * {@link ScoringService}. This shared model is the rule engine's {@code WHEN}.
 */
@Service
@RequiredArgsConstructor
public class SegmentService {

    private final WorkspaceService workspaceService;
    private final AuthService authService;
    private final ScoringService scoringService;
    private final SegmentMapper segmentMapper;
    private final PersonEdgeMapper edgeMapper;
    private final PersonMapper personMapper;
    private final TagMapper tagMapper;

    private static final int DEFAULT_DAYS = 30;
    private static final int MAX_DAYS = 3650;
    private static final int STRONG_EDGE = 2;
    private static final int MAX_CONDITIONS = 32;
    private static final int MAX_DEPTH = 4;

    private static final Set<String> SUPPORTED_RECORD_TYPES = Set.of("company", "person", "deal");
    private static final Set<String> PREDICATE_KEYS =
        Set.of("warm_intro_available", "open_deal", "cooling", "no_activity");
    private static final Set<String> STATUS_VALUES = Set.of("open", "won", "lost");

    private enum Kind { STRING, NUMBER, ID, ENUM, TAG, DATE }

    private static final Map<String, Map<String, Kind>> FIELDS = Map.of(
        "company", Map.of(
            "industry", Kind.STRING,
            "name", Kind.STRING,
            "tag", Kind.TAG),
        "person", Map.of(
            "name", Kind.STRING,
            "title", Kind.STRING,
            "email", Kind.STRING,
            "company", Kind.ID,
            "tag", Kind.TAG),
        "deal", Map.of(
            "name", Kind.STRING,
            "value", Kind.NUMBER,
            "stage", Kind.ID,
            "owner", Kind.ID,
            "status", Kind.ENUM,
            "close_date", Kind.DATE,
            "tag", Kind.TAG));

    private static final Map<Kind, Set<String>> OPS = Map.of(
        Kind.STRING, Set.of("equals", "contains", "starts_with", "is_set"),
        Kind.NUMBER, Set.of("equals", "gt", "gte", "lt", "lte"),
        Kind.ID, Set.of("is", "in"),
        Kind.ENUM, Set.of("is"),
        Kind.TAG, Set.of("has"),
        Kind.DATE, Set.of("before", "after", "within_days", "is_set"));

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
        if (total > MAX_CONDITIONS) {
            throw new BadRequestException("A rule may reference at most " + MAX_CONDITIONS + " conditions");
        }
        EvalContext ctx = new EvalContext(workspaceId, userId, type, includeRestrictedPeople);
        Set<Integer> result = evaluateGroup(definition, ctx, 1);
        return new ArrayList<>(result);
    }

    private int countConditions(SegmentDefinition group, int depth) {
        if (depth > MAX_DEPTH) {
            throw new BadRequestException("Conditions are nested too deeply (max " + MAX_DEPTH + " levels)");
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
        int total = countConditions(definition, 1);
        if (total > MAX_CONDITIONS) {
            throw new BadRequestException("A rule may reference at most " + MAX_CONDITIONS + " conditions");
        }
        validateGroup(definition, type);
    }

    private void validateGroup(SegmentDefinition group, String recordType) {
        String match = normalize(group.getMatch());
        if (!"any".equals(match) && !"all".equals(match)) {
            throw new BadRequestException("Invalid match (expected 'all' or 'any'): " + group.getMatch());
        }
        List<SegmentCondition> conditions = group.getConditions() == null ? List.of() : group.getConditions();
        List<SegmentDefinition> groups = group.getGroups() == null ? List.of() : group.getGroups();
        if (conditions.isEmpty() && groups.isEmpty()) {
            throw new BadRequestException("A condition group requires at least one condition or nested group");
        }
        for (SegmentCondition condition : conditions) {
            validateCondition(condition, recordType);
        }
        for (SegmentDefinition nested : groups) {
            validateGroup(nested, recordType);
        }
    }

    private void validateCondition(SegmentCondition condition, String recordType) {
        String type = normalize(condition.getType());
        if ("predicate".equals(type)) {
            if (!"company".equals(recordType)) {
                throw new BadRequestException("Predicates are only available for company records");
            }
            String key = normalize(condition.getKey());
            if (key == null || !PREDICATE_KEYS.contains(key)) {
                throw new BadRequestException("Unknown predicate: " + condition.getKey());
            }
            return;
        }
        if ("field".equals(type)) {
            String field = normalize(condition.getField());
            String op = normalize(condition.getOp());
            if (field == null || op == null) {
                throw new BadRequestException("Field condition requires 'field' and 'op'");
            }
            Kind kind = fieldKind(recordType, field);
            if (!OPS.get(kind).contains(op)) {
                throw new BadRequestException("Unsupported operator for '" + field + "': " + condition.getOp());
            }
            bindValue(kind, op, condition, new HashMap<>());
            return;
        }
        throw new BadRequestException("Unknown condition type (expected 'predicate' or 'field'): " + condition.getType());
    }

    private Set<Integer> evaluateGroup(SegmentDefinition group, EvalContext ctx, int depth) {
        Set<Integer> matched = combineMembers(group, ctx, depth);
        return group.isNegate() ? complement(matched, ctx) : matched;
    }

    private Set<Integer> combineMembers(SegmentDefinition group, EvalContext ctx, int depth) {
        if (depth > MAX_DEPTH) {
            throw new BadRequestException("Conditions are nested too deeply (max " + MAX_DEPTH + " levels)");
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
            if (!"company".equals(ctx.recordType())) {
                throw new BadRequestException("Predicates are only available for company records");
            }
            return evaluatePredicate(condition, ctx.workspaceId(), ctx.userId());
        }
        if ("field".equals(type)) {
            return evaluateField(condition, ctx);
        }
        throw new BadRequestException("Unknown condition type (expected 'predicate' or 'field'): " + condition.getType());
    }

    private Set<Integer> evaluatePredicate(SegmentCondition condition, int workspaceId, int userId) {
        String key = normalize(condition.getKey());
        if (key == null || !PREDICATE_KEYS.contains(key)) {
            throw new BadRequestException("Unknown predicate: " + condition.getKey());
        }
        return switch (key) {
            case "open_deal" -> new HashSet<>(segmentMapper.companyIdsWithOpenDeal(workspaceId));
            case "no_activity" -> new HashSet<>(segmentMapper.companyIdsNoActivitySince(
                workspaceId, resolveDays(condition.getDays())));
            case "cooling" -> coolingCompanyIds(workspaceId);
            case "warm_intro_available" -> warmIntroCompanyIds(workspaceId, userId);
            default -> throw new BadRequestException("Unknown predicate: " + condition.getKey());
        };
    }

    private Set<Integer> evaluateField(SegmentCondition condition, EvalContext ctx) {
        String field = normalize(condition.getField());
        String op = normalize(condition.getOp());
        if (field == null || op == null) {
            throw new BadRequestException("Field condition requires 'field' and 'op'");
        }
        Kind kind = fieldKind(ctx.recordType(), field);
        if (!OPS.get(kind).contains(op)) {
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

    private void bindValue(Kind kind, String op, SegmentCondition condition, Map<String, Object> params) {
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
                        params.put("dateTo", today.plusDays(resolveDays(condition.getDays())).toString());
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

    private static Kind fieldKind(String recordType, String field) {
        Map<String, Kind> catalog = FIELDS.get(recordType);
        Kind kind = catalog == null ? null : catalog.get(field);
        if (kind == null) {
            throw new BadRequestException("Unknown field for " + recordType + ": " + field);
        }
        return kind;
    }

    private Set<Integer> coolingCompanyIds(int workspaceId) {
        Set<Integer> owned = new HashSet<>(segmentMapper.companyIdsInWorkspace(workspaceId));
        Set<Integer> ids = new HashSet<>();
        for (RelationshipTemperatureDto temperature : scoringService.scoreCompanies(workspaceId)) {
            if (owned.contains(temperature.getId())
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
        for (PersonEdge edge : edgeMapper.getAllEdges(workspaceId)) {
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

    private static String requireSupported(String recordType) {
        String type = normalize(recordType);
        if (type == null || !SUPPORTED_RECORD_TYPES.contains(type)) {
            throw new BadRequestException("Smart segments are not available for record type: " + recordType);
        }
        return type;
    }

    private static int resolveDays(Integer days) {
        if (days == null) {
            return DEFAULT_DAYS;
        }
        return Math.min(Math.max(days, 1), MAX_DAYS);
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

    private static String requireStatus(SegmentCondition condition) {
        String value = normalize(requireValue(condition));
        if (!STATUS_VALUES.contains(value)) {
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

    private static final class EvalContext {
        private final int workspaceId;
        private final int userId;
        private final String recordType;
        private final boolean includeRestrictedPeople;
        private Set<Integer> universe;

        private EvalContext(int workspaceId, int userId, String recordType, boolean includeRestrictedPeople) {
            this.workspaceId = workspaceId;
            this.userId = userId;
            this.recordType = recordType;
            this.includeRestrictedPeople = includeRestrictedPeople;
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
    }
}
