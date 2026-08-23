package ooo.klae.connex.backend.ai.assistant;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.assistant.AiAssistantToolResult.Identifier;
import ooo.klae.connex.backend.ai.masking.MaskingContext;
import ooo.klae.connex.backend.ai.masking.MaskingEngine;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.SavedView;
import ooo.klae.connex.backend.dto.DealRiskDto;
import ooo.klae.connex.backend.dto.DealRiskFactor;
import ooo.klae.connex.backend.dto.RelationshipTemperatureDto;
import ooo.klae.connex.backend.dto.SegmentCondition;
import ooo.klae.connex.backend.dto.SegmentDefinition;
import ooo.klae.connex.backend.mappers.ActivityMapper;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.SegmentMapper;
import ooo.klae.connex.backend.services.DealRiskService;
import ooo.klae.connex.backend.services.OrganizationWorkspaceScopeControlAccess;
import ooo.klae.connex.backend.services.SavedViewService;
import ooo.klae.connex.backend.services.ScoringService;
import ooo.klae.connex.backend.services.SegmentService;
import ooo.klae.connex.backend.services.WorkspaceService;
import tools.jackson.databind.ObjectMapper;

/**
 * The bounded bulk read contracts Ask Connex uses instead of fanning out per record.
 *
 * <p>A cohort is resolved once through the existing authorized smart-segment evaluator, capped, and
 * then read with both the per-record and the total limit expressed inside the mapper query, so no
 * path materializes a workspace's activity history and truncates it afterwards. Every result states
 * the exact scope it interpreted, the true match counts behind the bounded rows it returned, the
 * caps it applied, and the categories it excluded.
 *
 * <p>A bounded result discloses truncation and stops there. It deliberately offers no continuation
 * handle: the per-record cap re-partitions the rows on every read, so a second page under a moved
 * period boundary is not the remainder of the first and any "rows left" figure computed from the
 * total match count would name rows no follow-up could reach. Stating the bound exactly is honest;
 * offering a resumption the contract cannot honour is not.
 */
@Service
@RequiredArgsConstructor
public class AiAssistantScopeReadService {
    private static final int MAX_FIELD_CHARS = 512;
    private static final int MAX_RESULT_TEXT_CHARS = 12_000;
    private static final DateTimeFormatter MYSQL_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ActivityMapper activityMapper;
    private final SegmentService segmentService;
    private final SegmentMapper segmentMapper;
    private final SavedViewService savedViewService;
    private final ScoringService scoringService;
    private final DealRiskService dealRiskService;
    private final PersonMapper personMapper;
    private final CompanyMapper companyMapper;
    private final DealMapper dealMapper;
    private final WorkspaceService workspaceService;
    private final OrganizationWorkspaceScopeControlAccess workspaceScopeControlAccess;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /** One resolved cohort and the honesty counters its resolution produced. */
    public record Cohort(String kind, List<Integer> ids, int matchedCount, boolean truncated) {
        public Cohort {
            ids = List.copyOf(ids);
        }
    }

    /**
     * Reads recent activity across the cohort the declared scope and requested narrowing describe.
     *
     * <p>{@code requestedKind} and {@code requestedBands} are model arguments and may only narrow
     * what the caller declared; {@code contextKind} is the server-derived kind of the record the
     * turn is anchored to and is consulted only where the declaration leaves the kind open.
     *
     * @param scope validated declared turn scope
     * @param requestedKind cohort record kind proposed as a model argument, or null
     * @param contextKind cohort record kind derived from the anchoring page record, or null
     * @param requestedBands warmth bands proposed as a model argument, or empty
     * @param requestedDays trailing window requested by the caller, or null to use the declared scope
     * @param limit maximum activity rows, clamped to the declared cap
     * @param perRecordLimit maximum rows per cohort record, clamped to the declared cap
     * @param resources per-turn handle registry the returned records are registered in
     * @return bounded activity rows plus the exact interpreted scope and coverage metadata
     */
    public AiAssistantToolResult scopeActivities(
            AiChatQueryScope scope,
            String requestedKind,
            String contextKind,
            List<String> requestedBands,
            Integer requestedDays,
            int limit,
            int perRecordLimit,
            AiChatResourceRegistry resources) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        AiChatCohortKind.Cohort facets = AiChatCohortKind.resolve(
                scope, requestedKind, contextKind, requestedBands);
        String kind = facets.kind();
        List<String> bands = facets.bands();
        int rows = clamp(limit, 1, AiChatScopeBounds.MAX_ACTIVITY_ROWS,
                AiChatScopeBounds.DEFAULT_ACTIVITY_ROWS);
        int perRecord = clamp(perRecordLimit, 1,
                AiChatScopeBounds.MAX_ACTIVITY_ROWS_PER_RECORD,
                AiChatScopeBounds.DEFAULT_ACTIVITY_ROWS_PER_RECORD);
        Period period = period(scope, requestedDays);
        Cohort cohort = cohort(kind, scope, bands);
        List<String> exclusions = new ArrayList<>();
        if (cohort.truncated()) {
            exclusions.add("bounded_results");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("scope", interpretedScopeData(kind, bands, period, scope));
        data.put("matchedRecords", cohort.matchedCount());
        data.put("readRecords", cohort.ids().size());
        data.put("recordsTruncated", cohort.truncated());
        data.put("caps", caps(
                "records", AiChatScopeBounds.MAX_COHORT_RECORDS,
                "activities", rows,
                "perRecord", perRecord));
        data.put("sort", "timestamp_desc");
        data.put("asOf", MYSQL_TIMESTAMP.format(
                LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)));
        if (cohort.ids().isEmpty()) {
            data.put("matchingActivities", 0);
            data.put("returnedActivities", 0);
            data.put("activitiesTruncated", false);
            data.put("records", List.of());
            data.put("activities", List.of());
            data.put("exclusions", List.copyOf(exclusions));
            return new AiAssistantToolResult(data, List.of());
        }
        List<Integer> organizationWorkspaceIds = workspaceScopeControlAccess
                .getForWorkspace(workspaceId).workspaceIds();
        long matching = activityMapper.countAiAssistantScopeActivities(
                workspaceId, organizationWorkspaceIds, kind, cohort.ids(),
                period.startUtc(), period.endUtc(), scope.activityTypes(), true);
        long unrestricted = activityMapper.countAiAssistantScopeActivities(
                workspaceId, organizationWorkspaceIds, kind, cohort.ids(),
                period.startUtc(), period.endUtc(), scope.activityTypes(), false);
        if (unrestricted > matching) {
            exclusions.add("restricted_records");
        }
        List<AiAssistantScopeActivity> activities = activityMapper.getAiAssistantScopeActivities(
                workspaceId, organizationWorkspaceIds, kind, cohort.ids(),
                period.startUtc(), period.endUtc(), scope.activityTypes(), true,
                perRecord, rows);
        Map<Integer, RecordLabel> labels = labels(workspaceId, kind, cohort.ids());
        List<Identifier> identifiers = new ArrayList<>();
        List<Map<String, Object>> records = new ArrayList<>();
        Set<Integer> present = new LinkedHashSet<>();
        activities.forEach(activity -> present.add(activity.scopeRecordId()));
        for (Integer recordId : cohort.ids()) {
            RecordLabel label = labels.get(recordId);
            if (label == null || !present.contains(recordId)) {
                continue;
            }
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("handle", resources.register(kind, recordId));
            record.put("kind", kind);
            record.put("name", label.name());
            records.add(record);
            identifiers.add(new Identifier(kind, label.name()));
        }
        TextBudget budget = new TextBudget(MAX_RESULT_TEXT_CHARS);
        List<Map<String, Object>> rowData = new ArrayList<>();
        for (AiAssistantScopeActivity activity : activities) {
            RecordLabel label = labels.get(activity.scopeRecordId());
            if (label == null) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("handle", resources.register(kind, activity.scopeRecordId()));
            putBounded(row, "type", activity.type(), budget, resources.maskingContext());
            putBounded(row, "subject", activity.subject(), budget, resources.maskingContext());
            putBounded(row, "notes", activity.notes(), budget, resources.maskingContext());
            putTemporal(row, "at", activity.timestamp(), budget, resources.maskingContext());
            rowData.add(row);
        }
        boolean activitiesTruncated = matching > rowData.size();
        if (activitiesTruncated) {
            exclusions.add("bounded_results");
        }
        data.put("matchingActivities", matching);
        data.put("returnedActivities", rowData.size());
        data.put("activitiesTruncated", activitiesTruncated);
        data.put("records", List.copyOf(records));
        data.put("activities", List.copyOf(rowData));
        data.put("exclusions", List.copyOf(new LinkedHashSet<>(exclusions)));
        identifiers.forEach(identifier -> identifier.seed(resources.maskingContext()));
        return new AiAssistantToolResult(data, identifiers);
    }

    /**
     * Returns the deterministic server-computed warmth of one already-authorized record.
     *
     * @param kind {@code person} or {@code company}
     * @param recordId authorized record identifier
     * @param handle per-turn handle the answer must cite
     * @return warmth score, band, trend, recency, and decay prediction
     */
    public AiAssistantToolResult relationshipMetrics(String kind, int recordId, String handle) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        List<RelationshipTemperatureDto> scores = "company".equals(kind)
                ? scoringService.scoreCompanies(workspaceId, Set.of(recordId))
                : scoringService.scoreContacts(workspaceId, Set.of(recordId));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("handle", handle);
        data.put("kind", kind);
        RelationshipTemperatureDto score = scores.stream()
                .filter(candidate -> candidate.getId() == recordId)
                .findFirst()
                .orElse(null);
        if (score == null) {
            data.put("available", false);
            return new AiAssistantToolResult(data, List.of());
        }
        data.put("available", true);
        data.put("score", score.getScore());
        data.put("band", score.getBand());
        data.put("trend", score.getTrend());
        if (score.getLastTouchAt() != null) {
            data.put("lastTouchAt", score.getLastTouchAt());
        }
        if (score.getDaysSinceTouch() != null) {
            data.put("daysSinceTouch", score.getDaysSinceTouch());
        }
        data.put("recentTouches", score.getTouchCount());
        if (score.getGoesColdAt() != null) {
            data.put("goesColdAt", score.getGoesColdAt());
        }
        if (score.getDaysUntilCold() != null) {
            data.put("daysUntilCold", score.getDaysUntilCold());
        }
        data.put("modelVersion", score.getModelVersion());
        data.put("asOf", score.getAsOf().toString());
        return new AiAssistantToolResult(data, List.of());
    }

    /**
     * Returns the bounded set of open deals the deterministic risk model flags for attention.
     *
     * @param scope validated declared turn scope
     * @param limit maximum deals, clamped to the declared cap
     * @param resources per-turn handle registry the returned deals are registered in
     * @return risk-ordered deals with their stable factor codes and the counters behind the bound
     */
    public AiAssistantToolResult dealAttention(
            AiChatQueryScope scope, int limit, AiChatResourceRegistry resources) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int bound = clamp(limit, 1, AiChatScopeBounds.MAX_ATTENTION_DEALS,
                AiChatScopeBounds.MAX_ATTENTION_DEALS);
        Cohort cohort = cohort("deal", attentionScope(scope), List.of());
        List<DealRiskDto> assessments = cohort.ids().isEmpty()
                ? List.of()
                : dealRiskService.assessDeals(workspaceId, cohort.ids());
        List<DealRiskDto> bounded = assessments.stream().limit(bound).toList();
        Map<Integer, Deal> deals = bounded.isEmpty()
                ? Map.of()
                : dealMapper.getByIds(
                                workspaceId, bounded.stream().map(DealRiskDto::getDealId).toList())
                        .stream()
                        .collect(java.util.stream.Collectors.toMap(Deal::getId, deal -> deal));
        List<Identifier> identifiers = new ArrayList<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (DealRiskDto assessment : bounded) {
            Deal deal = deals.get(assessment.getDealId());
            if (deal == null || deal.getName() == null) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("handle", resources.register("deal", deal.getId()));
            row.put("name", deal.getName());
            row.put("level", assessment.getLevel());
            row.put("score", assessment.getScore());
            if (assessment.getValue() != null) {
                row.put("value", assessment.getValue());
            }
            if (deal.getCurrency() != null) {
                row.put("currency", deal.getCurrency());
            }
            if (deal.getExpectedCloseDate() != null) {
                row.put("expectedCloseDate", deal.getExpectedCloseDate());
            }
            row.put("factors", assessment.getFactors().stream()
                    .map(DealRiskFactor::getCode)
                    .toList());
            rows.add(row);
            identifiers.add(new Identifier("deal", deal.getName()));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        Map<String, Object> attentionScope = new LinkedHashMap<>();
        attentionScope.put("records", "deal");
        attentionScope.put("status", "open");
        attentionScope.put("owner", scope.memberScope().mode().name().toLowerCase(Locale.ROOT));
        attentionScope.put("stages", !scope.stageIds().isEmpty());
        attentionScope.put("savedView", scope.savedViewId() != null);
        data.put("scope", attentionScope);
        data.put("matchedRecords", cohort.matchedCount());
        data.put("readRecords", cohort.ids().size());
        data.put("recordsTruncated", cohort.truncated());
        data.put("flaggedDeals", assessments.size());
        data.put("returnedDeals", rows.size());
        data.put("dealsTruncated", assessments.size() > rows.size() || cohort.truncated());
        Map<String, Object> attentionCaps = new LinkedHashMap<>();
        attentionCaps.put("records", AiChatScopeBounds.MAX_COHORT_RECORDS);
        attentionCaps.put("deals", bound);
        data.put("caps", attentionCaps);
        data.put("sort", "risk_score_desc");
        data.put("asOf", MYSQL_TIMESTAMP.format(
                LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)));
        data.put("deals", List.copyOf(rows));
        identifiers.forEach(identifier -> identifier.seed(resources.maskingContext()));
        return new AiAssistantToolResult(data, identifiers);
    }

    /**
     * Resolves the cohort a declared scope will read, before the turn runs.
     *
     * <p>Resolution goes through the same rule and the same refusals the executed retrieval applies,
     * so a preview can never state a breadth for a query the turn would then decline to run, and the
     * count a member confirms is a count of the set the turn reads.
     *
     * @param scope validated declared turn scope
     * @param contextKind record kind derived from the anchoring page record, or null
     * @param dealAttentionPlan whether the routed skill's plan is a pipeline attention review
     * @return the cohort the turn will read
     */
    public Cohort previewCohort(
            AiChatQueryScope scope, String contextKind, boolean dealAttentionPlan) {
        if (dealAttentionPlan) {
            return cohort("deal", attentionScope(scope), List.of());
        }
        AiChatCohortKind.Cohort facets = AiChatCohortKind.resolve(
                scope, null, contextKind, List.of());
        return cohort(facets.kind(), scope, facets.bands());
    }

    /**
     * Resolves the cohort a scope describes through the authorized smart-segment evaluator.
     *
     * @param kind cohort record kind
     * @param scope validated declared turn scope
     * @param bands warmth bands to apply, empty for none
     * @return capped cohort ids plus the true match count and whether the cap was reached
     */
    public Cohort cohort(String kind, AiChatQueryScope scope, List<String> bands) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        SegmentDefinition definition = cohortDefinition(kind, scope, bands);
        List<Integer> matched = definition == null
                ? universe(workspaceId, kind)
                : segmentService.evaluate(kind, definition);
        List<Integer> ordered = matched.stream()
                .sorted(java.util.Comparator.reverseOrder())
                .toList();
        boolean truncated = ordered.size() > AiChatScopeBounds.MAX_COHORT_RECORDS;
        return new Cohort(
                kind,
                ordered.stream().limit(AiChatScopeBounds.MAX_COHORT_RECORDS).toList(),
                ordered.size(),
                truncated);
    }

    /**
     * Builds the segment definition that expresses one declared scope, or null when the scope
     * places no constraint on which records the cohort may contain.
     */
    private SegmentDefinition cohortDefinition(
            String kind, AiChatQueryScope scope, List<String> bands) {
        List<SegmentCondition> conditions = new ArrayList<>();
        List<SegmentDefinition> groups = new ArrayList<>();
        switch (scope.memberScope().mode()) {
            case ME -> conditions.add(field(
                    "owner", "is", String.valueOf(scope.memberScope().userId())));
            case MEMBERS -> conditions.add(fieldValues(
                    "owner", "in",
                    scope.memberScope().memberIds().stream().map(String::valueOf).toList()));
            default -> { }
        }
        if (!bands.isEmpty() && !"deal".equals(kind)) {
            groups.add(anyOf(bands.stream()
                    .map(band -> predicate("warmth_" + band))
                    .toList()));
        }
        if ("deal".equals(kind)) {
            if (!scope.stageIds().isEmpty()) {
                conditions.add(fieldValues(
                        "stage", "in",
                        scope.stageIds().stream().map(String::valueOf).toList()));
            }
            if (!scope.dealStatuses().isEmpty()) {
                groups.add(anyOf(scope.dealStatuses().stream()
                        .map(status -> field("status", "is", status))
                        .toList()));
            }
        }
        SegmentDefinition savedView = savedViewDefinition(kind, scope.savedViewId());
        if (savedView != null) {
            groups.add(savedView);
        }
        if (conditions.isEmpty() && groups.isEmpty()) {
            return null;
        }
        SegmentDefinition definition = new SegmentDefinition();
        definition.setMatch("all");
        definition.setConditions(conditions);
        definition.setGroups(groups);
        return definition;
    }

    /**
     * Resolves the segment definition of the declared saved view.
     *
     * <p>Refusing rather than returning null is the whole point: a view that was accepted at request
     * time and then edited, retyped, or emptied before this read would otherwise collapse the cohort
     * to the workspace universe while the echoed scope still claimed the view had bounded it.
     */
    private SegmentDefinition savedViewDefinition(String kind, Integer savedViewId) {
        if (savedViewId == null) {
            return null;
        }
        SavedView view = savedViewService.getById(savedViewId);
        if (!kind.equals(view.getRecordType())) {
            throw AiAssistantLoopException.malformed(AiChatSavedViewScope.UNSUPPORTED);
        }
        return AiChatSavedViewScope.definition(objectMapper, view)
                .orElseThrow(() -> AiAssistantLoopException.malformed(
                        AiChatSavedViewScope.UNSUPPORTED));
    }

    private List<Integer> universe(int workspaceId, String kind) {
        return switch (kind) {
            case "person" -> segmentMapper.personIdsInWorkspace(workspaceId);
            case "deal" -> segmentMapper.dealIdsInWorkspace(workspaceId);
            default -> segmentMapper.companyIdsInWorkspace(workspaceId);
        };
    }

    /**
     * Narrows a declared scope to the open deals a pipeline attention review covers.
     *
     * <p>The review reads open deals only, so a scope that declares warmth — which the deterministic
     * risk model does not consume — or that excludes open deals entirely is refused rather than
     * quietly overridden into a query the requester never asked for.
     */
    private static AiChatQueryScope attentionScope(AiChatQueryScope scope) {
        if (!scope.warmthBands().isEmpty()) {
            throw AiAssistantLoopException.malformed(
                    AiChatCohortKind.WARMTH_UNSUPPORTED_FOR_DEALS);
        }
        if (!scope.dealStatuses().isEmpty() && !scope.dealStatuses().contains("open")) {
            throw AiAssistantLoopException.malformed(
                    AiChatCohortKind.DEAL_STATUS_UNSUPPORTED_FOR_ATTENTION);
        }
        if (List.of("open").equals(scope.dealStatuses())) {
            return scope;
        }
        return new AiChatQueryScope(
                scope.declared(), scope.periodStart(), scope.periodEnd(), scope.periodDays(),
                scope.memberScope(), scope.warmthBands(), scope.recordKinds(), scope.stageIds(),
                List.of("open"), scope.activityTypes(), scope.savedViewId());
    }

    private Map<Integer, RecordLabel> labels(int workspaceId, String kind, List<Integer> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Integer, RecordLabel> labels = new LinkedHashMap<>();
        switch (kind) {
            case "person" -> personMapper.getByIds(workspaceId, ids).stream()
                    .filter(AiAssistantScopeReadService::isProcessable)
                    .filter(person -> person.getName() != null && !person.getName().isBlank())
                    .forEach(person -> labels.put(
                            person.getId(), new RecordLabel(person.getName())));
            case "deal" -> dealMapper.getByIds(workspaceId, ids).stream()
                    .filter(deal -> deal.getName() != null && !deal.getName().isBlank())
                    .forEach(deal -> labels.put(deal.getId(), new RecordLabel(deal.getName())));
            default -> companyMapper.getByIds(workspaceId, ids).stream()
                    .filter(company -> company.getArchivedAt() == null)
                    .filter(company -> company.getName() != null && !company.getName().isBlank())
                    .forEach(company -> labels.put(
                            company.getId(), new RecordLabel(company.getName())));
        }
        return Map.copyOf(labels);
    }

    private static boolean isProcessable(Person person) {
        return person != null && person.getSuspendedAt() == null
                && person.getProvisionCeasedAt() == null && person.getArchivedAt() == null;
    }

    private Period period(AiChatQueryScope scope, Integer requestedDays) {
        LocalDate end = scope.periodEnd() == null
                ? LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)
                : scope.periodEnd();
        LocalDate start;
        if (scope.periodStart() != null) {
            start = scope.periodStart();
        } else {
            int days = clamp(requestedDays == null ? 0 : requestedDays,
                    1, AiChatScopeBounds.MAX_PERIOD_DAYS,
                    AiChatScopeBounds.DEFAULT_PERIOD_DAYS);
            start = end.minusDays(days - 1L);
        }
        if (scope.periodStart() != null && requestedDays != null) {
            LocalDate narrowed = end.minusDays(
                    clamp(requestedDays, 1, AiChatScopeBounds.MAX_PERIOD_DAYS,
                            AiChatScopeBounds.DEFAULT_PERIOD_DAYS) - 1L);
            if (narrowed.isAfter(start)) {
                start = narrowed;
            }
        }
        return new Period(
                start, end,
                LocalDateTime.of(start, LocalTime.MIN),
                LocalDateTime.of(end, LocalTime.MAX));
    }

    /**
     * States every facet the cohort query actually applied.
     *
     * <p>Stage and saved-view membership is stated as a flag rather than an identifier because
     * tenant-local ids never enter prompt data; the facets themselves cannot be absent here, because
     * a scope declaring one that the resolved cohort cannot honour is refused before this runs.
     */
    private static Map<String, Object> interpretedScopeData(
            String kind, List<String> bands, Period period, AiChatQueryScope scope) {
        Map<String, Object> interpreted = new LinkedHashMap<>();
        interpreted.put("records", kind);
        interpreted.put("warmth", bands);
        interpreted.put("periodStart", period.start().toString());
        interpreted.put("periodEnd", period.end().toString());
        interpreted.put("owner", scope.memberScope().mode().name().toLowerCase(Locale.ROOT));
        interpreted.put("types", scope.activityTypes());
        interpreted.put("statuses", scope.dealStatuses());
        interpreted.put("stages", !scope.stageIds().isEmpty());
        interpreted.put("savedView", scope.savedViewId() != null);
        return interpreted;
    }

    /**
     * Builds the declared caps in a fixed key order. Durable tool results are compared and stored
     * verbatim, so their key order must not depend on the per-JVM randomization {@code Map.of} uses.
     */
    private static Map<String, Object> caps(
            String firstKey, int first, String secondKey, int second,
            String thirdKey, int third) {
        Map<String, Object> caps = new LinkedHashMap<>();
        caps.put(firstKey, first);
        caps.put(secondKey, second);
        caps.put(thirdKey, third);
        return caps;
    }

    private static int clamp(int value, int minimum, int maximum, int fallback) {
        if (value < minimum) {
            return Math.min(fallback, maximum);
        }
        return Math.min(value, maximum);
    }

    private static void putBounded(
            Map<String, Object> data,
            String key,
            String value,
            TextBudget budget,
            MaskingContext maskingContext) {
        if (value == null || value.isBlank()) {
            return;
        }
        put(data, key, MaskingEngine.screenFreeTextBeforeTruncation(value, maskingContext), budget);
    }

    private static void putTemporal(
            Map<String, Object> data,
            String key,
            String value,
            TextBudget budget,
            MaskingContext maskingContext) {
        if (value == null || value.isBlank()) {
            return;
        }
        put(data, key, MaskingEngine.maskTemporal(value, maskingContext), budget);
    }

    private static void put(
            Map<String, Object> data, String key, String screened, TextBudget budget) {
        int retained = Math.min(screened.length(), Math.min(MAX_FIELD_CHARS, budget.remaining()));
        if (retained <= 0) {
            return;
        }
        data.put(key, screened.substring(0, retained));
        budget.consume(retained);
        if (retained < screened.length()) {
            data.put(key + "Truncated", true);
        }
    }

    private static SegmentCondition field(String name, String operator, String value) {
        SegmentCondition condition = new SegmentCondition();
        condition.setType("field");
        condition.setField(name);
        condition.setOp(operator);
        condition.setValue(value);
        return condition;
    }

    private static SegmentCondition fieldValues(
            String name, String operator, List<String> values) {
        SegmentCondition condition = new SegmentCondition();
        condition.setType("field");
        condition.setField(name);
        condition.setOp(operator);
        condition.setValues(values);
        return condition;
    }

    private static SegmentCondition predicate(String key) {
        SegmentCondition condition = new SegmentCondition();
        condition.setType("predicate");
        condition.setKey(key);
        return condition;
    }

    private static SegmentDefinition anyOf(List<SegmentCondition> conditions) {
        SegmentDefinition group = new SegmentDefinition();
        group.setMatch("any");
        group.setConditions(conditions);
        return group;
    }

    private record RecordLabel(String name) {
    }

    private record Period(
            LocalDate start, LocalDate end, LocalDateTime startUtc, LocalDateTime endUtc) {
    }

    private static final class TextBudget {
        private int remaining;

        private TextBudget(int remaining) {
            this.remaining = remaining;
        }

        private int remaining() {
            return remaining;
        }

        private void consume(int characters) {
            remaining -= characters;
        }
    }
}
