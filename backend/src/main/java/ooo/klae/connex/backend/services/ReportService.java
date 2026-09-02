package ooo.klae.connex.backend.services;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.DayOfWeek;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.time.zone.ZoneOffsetTransition;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.AiFeature;
import ooo.klae.connex.backend.ai.AiGenerationService;
import ooo.klae.connex.backend.ai.AiGenerationTaskResult;
import ooo.klae.connex.backend.ai.AiRestrictionEpoch;
import ooo.klae.connex.backend.ai.report.AiReportNarrativeService;
import ooo.klae.connex.backend.beans.ReportGoal;
import ooo.klae.connex.backend.beans.ReportDefinition;
import ooo.klae.connex.backend.beans.ReportSchedule;
import ooo.klae.connex.backend.beans.ReportSnapshot;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.AiGenerationStatusDto;
import ooo.klae.connex.backend.dto.IntroSuggestionDto;
import ooo.klae.connex.backend.dto.ReportAggregateQuery;
import ooo.klae.connex.backend.dto.ReportAggregateRow;
import ooo.klae.connex.backend.dto.ReportAppendixRowDto;
import ooo.klae.connex.backend.dto.ReportCitationDto;
import ooo.klae.connex.backend.dto.ReportConfig;
import ooo.klae.connex.backend.dto.ReportDataPointDto;
import ooo.klae.connex.backend.dto.DealRiskDto;
import ooo.klae.connex.backend.dto.ReportDefinitionDto;
import ooo.klae.connex.backend.dto.ReportDefinitionRequest;
import ooo.klae.connex.backend.dto.ReportDocumentDto;
import ooo.klae.connex.backend.dto.ReportFilters;
import ooo.klae.connex.backend.dto.ReportForecastAggregateRow;
import ooo.klae.connex.backend.dto.ReportGenerateRequest;
import ooo.klae.connex.backend.dto.ReportKpiDto;
import ooo.klae.connex.backend.dto.ReportLayoutItem;
import ooo.klae.connex.backend.dto.ReportNarrativeDto;
import ooo.klae.connex.backend.dto.ReportOffsetSegment;
import ooo.klae.connex.backend.dto.ReportRange;
import ooo.klae.connex.backend.dto.ReportSnapshotDto;
import ooo.klae.connex.backend.dto.ReportSnapshotSummaryDto;
import ooo.klae.connex.backend.dto.ReportTemplateDto;
import ooo.klae.connex.backend.dto.ReportWidgetConfig;
import ooo.klae.connex.backend.dto.ReportWidgetDataDto;
import ooo.klae.connex.backend.dto.RelationshipTemperatureDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.exceptions.TooManyRequestsException;
import ooo.klae.connex.backend.mappers.GoalMapper;
import ooo.klae.connex.backend.mappers.ReportMapper;
import ooo.klae.connex.backend.mappers.ScheduleMapper;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;
import ooo.klae.connex.backend.warmth.RelationshipWarmthModel;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Workspace-scoped report definition, deterministic generation, snapshot, and export service.
 */
@Service
@RequiredArgsConstructor
public class ReportService {
    private static final RelationshipWarmthModel WARMTH_MODEL = RelationshipWarmthModel.current();

    private static final int MAX_CONFIG_BYTES = 131_072;
    private static final int MAX_SNAPSHOT_BYTES = 4_194_304;
    private static final int MAX_SNAPSHOTS_PER_REPORT = 100;
    private static final int MAX_SCHEDULED_SNAPSHOTS_PER_SCHEDULE = 26;
    private static final int SCHEDULED_EVICTION_BATCH_SIZE = 16;
    private static final int MAX_SCHEDULED_EVICTION_BATCHES = 256;
    private static final int MAX_SNAPSHOT_LIST_SIZE =
            MAX_SNAPSHOTS_PER_REPORT + MAX_SCHEDULED_SNAPSHOTS_PER_SCHEDULE;
    private static final int MAX_REPORTS_PER_WORKSPACE = 100;
    private static final int MAX_SNAPSHOTS_PER_WORKSPACE = 1_000;
    private static final long MAX_SNAPSHOT_BYTES_PER_WORKSPACE = 268_435_456;
    private static final String SNAPSHOT_ORIGIN_MANUAL = "manual";
    private static final String SNAPSHOT_ORIGIN_SCHEDULED = "scheduled";
    private static final String NARRATIVE_NOT_CACHED = "not_cached";
    private static final String NARRATIVE_NOT_CONFIGURED = "not_configured";
    private static final String NARRATIVE_RATE_LIMITED = "rate_limited";
    private static final Set<String> NARRATIVE_FAILURE_REASONS = Set.of(
            "provider_error", "invalid_grounding", "rate_limited");
    private static final int RISK_ID_BATCH_SIZE = 1_000;
    private static final int FORECAST_HORIZON_MONTHS = 3;
    private static final int FORECAST_HISTORY_PRIOR_DEALS = 10;
    private static final long MAX_RANGE_DAYS = 1_826;
    private static final BigDecimal FORECAST_NEUTRAL_WIN_RATE = new BigDecimal("0.5");
    private static final Set<String> CADENCES = Set.of("weekly", "monthly", "quarterly", "custom");
    private static final Set<String> BUCKETS = Set.of("day", "week", "month");
    private static final Set<String> CHART_TYPES = Set.of("bar", "line-area", "donut", "funnel", "table", "kpi");
    private static final Set<String> DATA_SOURCES = Set.of(
            "deals", "people", "companies", "activities", "tasks", "relationships", "documents",
            "leads");
    /**
     * Lead-lifecycle measures (#559 increment 6). Volume, qualification, and conversion read the
     * append-only transition history rather than the contact's current stage, which cannot say a
     * contact was ever qualified — only where it ended up.
     */
    private static final Set<String> LEAD_MEASURES = Set.of(
            "lead_count", "qualified_count", "converted_count", "disqualified_count",
            "qualification_rate", "conversion_rate", "time_to_convert_days",
            "first_response_hours", "first_response_breach_rate");
    private static final Set<String> LEAD_GROUPS = Set.of("none", "date", "owner", "lead_source");
    private static final Set<String> DEAL_MEASURES = Set.of(
            "count", "new_pipeline_value", "won_revenue", "win_rate", "avg_cycle_days",
            "open_pipeline_value", "open_deal_count", "at_risk_revenue",
            "single_threaded_deal_count", "single_threaded_deal_value",
            "forecast_best", "forecast_weighted", "forecast_worst", "attainment",
            "effective_discount_percent", "open_discount_percent");
    private static final Set<String> DISCOUNT_MEASURES = Set.of(
            "effective_discount_percent", "open_discount_percent");
    private static final Set<String> DOCUMENT_MEASURES = Set.of(
            "quote_count", "quote_issue_rate", "document_to_win_rate",
            "approval_decision_count", "approval_cycle_days");
    private static final Set<String> DOCUMENT_APPROVAL_MEASURES = Set.of(
            "approval_decision_count", "approval_cycle_days");
    private static final Set<String> DOCUMENT_OUTCOME_MEASURES = Set.of("document_to_win_rate");
    private static final Set<String> NON_ADDITIVE_MEASURES = Set.of(
            "win_rate", "avg_cycle_days", "quote_issue_rate", "document_to_win_rate",
            "approval_cycle_days",
            "qualification_rate", "conversion_rate", "time_to_convert_days",
            "first_response_hours", "first_response_breach_rate");
    private static final Set<String> UNDEFINED_WHEN_EMPTY_MEASURES = Set.of(
            "quote_issue_rate", "document_to_win_rate", "approval_cycle_days",
            "effective_discount_percent", "open_discount_percent",
            "qualification_rate", "conversion_rate", "time_to_convert_days",
            "first_response_hours", "first_response_breach_rate");
    private static final Set<String> FORECAST_MEASURES = Set.of(
            "forecast_best", "forecast_weighted", "forecast_worst");
    private static final Set<String> COMPANY_MEASURES = Set.of(
            "count", "coverage_gap_count", "coverage_gap_open_pipeline_value",
            "warm_intro_opportunity_value", "warm_intro_reachable_account_count");
    private static final Set<String> WARM_INTRO_MEASURES = Set.of(
            "warm_intro_opportunity_value", "warm_intro_reachable_account_count");
    private static final Set<String> RELATIONSHIP_MEASURES = Set.of(
            "count", "company_count", "reverse_intro_weighted_opportunities");
    private static final Set<String> EMPLOYMENT_MEASURES = Set.of(
            "employment_departure_count", "employment_arrival_count");
    private static final Set<String> REVERSE_INTRO_MEASURES = Set.of(
            "reverse_intro_weighted_opportunities");
    private static final Set<String> COUNT_MEASURES = Set.of("count");
    private static final Set<String> SUPPORTED_MEASURES = supportedMeasureCatalog();
    private static final Set<String> DEAL_GROUPS = Set.of(
            "none", "date", "pipeline", "stage", "owner", "status", "company", "deal", "risk");
    private static final Set<String> ACTIVITY_GROUPS = Set.of("none", "date", "activity_type", "owner");
    private static final Set<String> TASK_GROUPS = Set.of("none", "date", "status", "owner");
    private static final Set<String> PEOPLE_GROUPS = Set.of("none", "date", "company", "person");
    private static final Set<String> EMPLOYMENT_GROUPS = Set.of("none", "date", "company", "person");
    private static final Set<String> COMPANY_GROUPS = Set.of("none", "industry", "company", "connector");
    private static final Set<String> RELATIONSHIP_GROUPS = Set.of("none", "warmth_band", "trend", "pair");
    private static final Set<String> DOCUMENT_GROUPS = Set.of("none", "date", "owner", "company");
    private static final Set<String> DISCOUNT_GROUPS = Set.of(
            "none", "date", "pipeline", "stage", "owner", "company");
    private static final Set<String> DEAL_STATUSES = Set.of("open", "won", "lost");
    private static final Set<String> TASK_STATUSES = Set.of("todo", "in_progress", "done");
    private static final Set<String> WARMTH_BANDS = Set.of("hot", "warm", "cool", "cold");
    private static final Set<String> TEMPLATE_KEYS = Set.of(
            "sales-performance", "pipeline-health", "relationship-coverage", "relationship-health",
            "forecasting", "quota-attainment", "activity-team", "network-warm-intros", "employment-moves",
            "commercial-documents", "lead-lifecycle");

    private final ReportMapper reportMapper;
    private final ScheduleMapper scheduleMapper;
    private final GoalMapper goalMapper;
    private final WorkspaceService workspaceService;
    private final AuthService authService;
    private final ScoringService scoringService;
    private final DealRiskService dealRiskService;
    private final ReportNetworkService reportNetworkService;
    private final AiReportNarrativeService aiReportNarrativeService;
    private final AiGenerationService aiGenerationService;
    private final AiRestrictionEpoch aiRestrictionEpoch;
    private final ReportPermissionPolicy reportPermissionPolicy;
    private final AuditService auditService;
    private final DeletionPolicy deletionPolicy;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    /**
     * Controls how a generated document resolves its AI narrative. {@code NONE} skips it (exports),
     * {@code CACHED} returns a cached narrative without ever calling the provider, and {@code FULL}
     * performs blocking generation for internal snapshot and delivery callers. Interactive HTTP
     * generation uses {@link #generateInteractive(int, ReportGenerateRequest, NarrativeMode)}.
     */
    public enum NarrativeMode {
        NONE,
        CACHED,
        FULL
    }

    /** Returns all report definitions in the active workspace. */
    @RequirePermission(Permission.REPORT_READ)
    public List<ReportDefinitionDto> list() {
        return reportMapper.getDefinitions(workspaceService.getCurrentWorkspaceId()).stream()
                .map(this::toDefinitionDto)
                .toList();
    }

    /** Returns one report definition in the active workspace. */
    @RequirePermission(Permission.REPORT_READ)
    public ReportDefinitionDto get(int id) {
        return toDefinitionDto(requireDefinition(id));
    }

    /** Returns the ten built-in report starting points. */
    @RequirePermission(Permission.REPORT_READ)
    public List<ReportTemplateDto> templates() {
        return List.of(
                template("sales-performance", "Sales Performance", "Revenue, win rate, and new pipeline momentum.",
                        "monthly", List.of(
                                widget("revenue", "Won revenue", "deals", "won_revenue", "date", "line-area"),
                                widget("win-rate", "Win rate", "deals", "win_rate", "none", "kpi"),
                                widget("new-pipeline", "New pipeline", "deals", "new_pipeline_value", "pipeline", "bar"))),
                template("quota-attainment", "Quota Attainment",
                        "Owner and workspace revenue targets compared with won revenue.",
                        "monthly", "month", List.of(
                                widget("attainment-by-owner", "Attainment by owner", "deals",
                                        "attainment", "owner", "bar"),
                                widget("overall-attainment", "Overall attainment", "deals",
                                        "attainment", "none", "kpi"),
                                widget("won-by-owner", "Won revenue by owner", "deals",
                                        "won_revenue", "owner", "bar"))),
                template("pipeline-health", "Pipeline Health", "Open pipeline, stage coverage, and relationship risk.",
                        "weekly", List.of(
                                widget("pipeline-value", "Open pipeline", "deals", "open_pipeline_value", "pipeline", "bar"),
                                widget("stage-count", "Deals by stage", "deals", "open_deal_count", "stage", "funnel"),
                                widget("risk-revenue", "At-risk revenue", "deals", "at_risk_revenue", "risk", "table"))),
                template("forecasting", "Forecasting",
                        "Next-three-month best, likely, and commit forecasts weighted by historical stage win rates.",
                        "quarterly", "month", List.of(
                                widget("weighted-by-month", "Likely forecast by month", "deals",
                                        "forecast_weighted", "date", "line-area"),
                                widget("best-summary", "Best-case forecast", "deals",
                                        "forecast_best", "none", "kpi"),
                                widget("weighted-summary", "Likely forecast", "deals",
                                        "forecast_weighted", "none", "kpi"),
                                widget("worst-summary", "Commit forecast", "deals",
                                        "forecast_worst", "none", "kpi"),
                                widget("pipeline-by-stage", "Forward pipeline by stage", "deals",
                                        "forecast_best", "stage", "bar"))),
                template("relationship-coverage", "Relationship Coverage", "Warmth distribution and account coverage.",
                        "monthly", List.of(
                                widget("warmth", "Relationship warmth", "relationships", "count", "warmth_band", "donut"),
                                widget("companies", "New companies", "companies", "count", "industry", "bar"),
                                widget("people", "New contacts", "people", "count", "company", "table"))),
                template("relationship-health", "Relationship Health",
                        "Cooling accounts, coverage gaps, and single-threaded deal risk.",
                        "monthly", List.of(
                                widget("warmth", "Relationship warmth", "relationships", "count", "warmth_band", "donut"),
                                widget("cooling-accounts", "Company relationship trends", "relationships",
                                        "company_count", "trend", "bar"),
                                widget("coverage-gap-count", "Coverage gaps", "companies",
                                        "coverage_gap_count", "none", "kpi"),
                                widget("coverage-gap-pipeline", "Coverage gaps by open pipeline", "companies",
                                        "coverage_gap_open_pipeline_value", "company", "table"),
                                widget("single-thread-count", "Single-threaded deals", "deals",
                                        "single_threaded_deal_count", "none", "kpi"),
                                widget("single-thread-value", "Single-threaded deal value", "deals",
                                        "single_threaded_deal_value", "deal", "table"))),
                template("network-warm-intros", "Network & Warm Introductions",
                        "Reachable pipeline, unactivated paths, connector coverage, and reverse-intro potential.",
                        "monthly", List.of(
                                widget("reachable-pipeline", "Reachable pipeline value", "companies",
                                        "warm_intro_opportunity_value", "none", "kpi"),
                                widget("closeable-gaps", "Network-closeable coverage gaps", "companies",
                                        "warm_intro_reachable_account_count", "none", "kpi"),
                                widget("top-paths", "Top unactivated warm-intro paths", "companies",
                                        "warm_intro_opportunity_value", "company", "table"),
                                widget("connector-coverage", "Connector coverage", "companies",
                                        "warm_intro_reachable_account_count", "connector", "bar"),
                                widget("reverse-intro-value", "Reverse-intro opportunity value", "relationships",
                                        "reverse_intro_weighted_opportunities", "none", "kpi"),
                                widget("reverse-intro-matches", "Top reverse-intro matches", "relationships",
                                        "reverse_intro_weighted_opportunities", "pair", "table"))),
                template("employment-moves", "Employment Moves",
                        "Contact departures and arrivals that signal relationship risk and new-account opportunities.",
                        "monthly", List.of(
                                widget("departure-total", "Employment departures", "people",
                                        "employment_departure_count", "none", "kpi"),
                                widget("arrival-total", "Employment arrivals", "people",
                                        "employment_arrival_count", "none", "kpi"),
                                widget("departure-trend", "Employment departures", "people",
                                        "employment_departure_count", "date", "line-area"),
                                widget("arrival-trend", "Employment arrivals", "people",
                                        "employment_arrival_count", "date", "line-area"),
                                widget("departure-companies", "Employment departures", "people",
                                        "employment_departure_count", "company", "table"),
                                widget("arrival-companies", "Employment arrivals", "people",
                                        "employment_arrival_count", "company", "table"),
                                widget("departure-contacts", "Employment departures", "people",
                                        "employment_departure_count", "person", "table"),
                                widget("arrival-contacts", "Employment arrivals", "people",
                                        "employment_arrival_count", "person", "table"))),
                template("commercial-documents", "Quotes & Approvals",
                        "Quote volume, approval turnaround, and the discount actually conceded.",
                        "monthly", "month", List.of(
                                widget("quote-volume", "Quote volume", "documents",
                                        "quote_count", "date", "line-area"),
                                widget("issue-rate", "Quote issue rate", "documents",
                                        "quote_issue_rate", "none", "kpi"),
                                widget("doc-to-win", "Document-to-win rate", "documents",
                                        "document_to_win_rate", "none", "kpi"),
                                widget("approval-cycle", "Approval cycle days", "documents",
                                        "approval_cycle_days", "none", "kpi"),
                                widget("approval-volume", "Approval decisions by owner", "documents",
                                        "approval_decision_count", "owner", "bar"),
                                widget("won-discount", "Effective discount (won)", "deals",
                                        "effective_discount_percent", "none", "bar"),
                                widget("open-discount", "Effective discount (open)", "deals",
                                        "open_discount_percent", "pipeline", "table"))),
                template("lead-lifecycle", "Lead Lifecycle",
                        "Where leads come from, how many qualify, how fast they are answered, "
                            + "and what converts.",
                        "monthly", "month", List.of(
                                widget("lead-volume", "Leads entered", "leads",
                                        "lead_count", "date", "line-area"),
                                widget("lead-by-source", "Leads by source", "leads",
                                        "lead_count", "lead_source", "donut"),
                                widget("qualification-rate", "Qualification rate", "leads",
                                        "qualification_rate", "none", "kpi"),
                                widget("conversion-rate", "Conversion rate", "leads",
                                        "conversion_rate", "none", "kpi"),
                                widget("time-to-convert", "Time to convert", "leads",
                                        "time_to_convert_days", "none", "kpi"),
                                widget("first-response", "Time to first response", "leads",
                                        "first_response_hours", "none", "kpi"),
                                widget("breach-rate", "First-response breach rate", "leads",
                                        "first_response_breach_rate", "date", "bar"),
                                widget("qualified-by-owner", "Qualified by owner", "leads",
                                        "qualified_count", "owner", "table"))),
                template("activity-team", "Activity & Team", "Team activity and task execution.",
                        "weekly", List.of(
                                widget("activity", "Activity volume", "activities", "count", "date", "line-area"),
                                widget("team", "Touches by owner", "activities", "count", "owner", "bar"),
                                widget("tasks", "Task outcomes", "tasks", "count", "status", "donut"))));
    }

    /** Creates a workspace-shared report definition. */
    @Transactional
    @RequirePermission(Permission.REPORT_CREATE)
    public ReportDefinitionDto create(ReportDefinitionRequest request) {
        ValidatedDefinition validated = validate(request);
        ReportDefinition definition = new ReportDefinition();
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        reportMapper.lockDefinitions(workspaceId);
        if (reportMapper.countDefinitions(workspaceId) >= MAX_REPORTS_PER_WORKSPACE) {
            throw new BadRequestException("A workspace cannot have more than 100 reports");
        }
        definition.setWorkspaceId(workspaceId);
        definition.setCreatedBy(authService.getCurrentUser().getId());
        apply(definition, request, validated);
        reportMapper.insertDefinition(definition);
        auditService.record("report.create", "report", definition.getId(), definition.getName(),
                "Created report " + definition.getName(), null);
        return toDefinitionDto(requireDefinition(definition.getId()));
    }

    /** Replaces a workspace-shared report definition. */
    @Transactional
    @RequirePermission(Permission.REPORT_UPDATE)
    public ReportDefinitionDto update(int id, ReportDefinitionRequest request) {
        ReportDefinition definition = requireDefinition(id);
        ValidatedDefinition validated = validate(request);
        apply(definition, request, validated);
        if (reportMapper.updateDefinition(definition) == 0) {
            throw new ResourceNotFoundException("Report not found with id: " + id);
        }
        auditService.record("report.update", "report", id, definition.getName(),
                "Updated report " + definition.getName(), null);
        return toDefinitionDto(requireDefinition(id));
    }

    /** Deletes a report definition and its snapshots. */
    @Transactional
    @RequirePermission(Permission.REPORT_DELETE)
    public void delete(int id) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        reportMapper.lockDefinitions(workspaceId);
        ReportDefinition definition = reportMapper.getDefinition(workspaceId, id);
        if (definition == null) {
            throw new ResourceNotFoundException("Report not found with id: " + id);
        }
        deletionPolicy.requireDeletable(definition.getCreatedBy());
        int currentUserId = workspaceService.getCurrentUserId();
        int destroyedSnapshotCount = reportMapper.countSnapshots(workspaceId, id);
        if (reportMapper.countSnapshotsNotGeneratedBy(workspaceId, id, currentUserId) > 0
                || reportMapper.countScheduledSnapshots(workspaceId, id) > 0) {
            workspaceService.requireRole(WorkspaceService.Role.ADMIN);
        }
        if (reportMapper.deleteDefinition(workspaceId, id) == 0) {
            throw new ResourceNotFoundException("Report not found with id: " + id);
        }
        auditService.record("report.delete", "report", id, definition.getName(),
                "Deleted report " + definition.getName(),
                Map.of("destroyedSnapshotCount", destroyedSnapshotCount));
    }

    /**
     * Generates deterministic figures and resolves the grounded narrative per the requested mode.
     * {@link NarrativeMode#CACHED} never invokes the AI provider, so the figures return immediately
     * and a cache miss yields an explicit {@code not_cached} narrative.
     */
    @RequirePermission(Permission.REPORT_READ)
    public ReportDocumentDto generate(int id, ReportGenerateRequest request, NarrativeMode mode) {
        return generateInternal(id, request, mode);
    }

    /**
     * Resolves one saved widget as a deterministic scalar without generating a narrative. The
     * caller must hold every permission required by the full saved report definition.
     */
    @RequirePermission(Permission.REPORT_READ)
    public ReportKpiDto widgetKpi(int reportId, String widgetId, ReportGenerateRequest request) {
        ReportDefinition definition = requireDefinition(reportId);
        for (Permission permission : reportPermissionPolicy.requiredFor(definition)) {
            workspaceService.requirePermission(permission);
        }
        ReportConfig config = parseConfig(definition.getConfigJson());
        validateConfig(definition.getCadence(), definition.getTemplateKey(), config);
        PeriodWindow period = resolvePeriod(definition.getCadence(), config.range(), request, config.bucket());
        validateAttainmentPeriod(config, period);
        WidgetSelection selected = selectWidget(config, widgetId);
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        GenerationInputs inputs = generationInputs(
                workspaceId, config, List.of(selected.widget()), period);
        WidgetResult result = generateWidget(
                workspaceId,
                selected.widget(),
                config.filters(),
                config.bucket(),
                period,
                selected.index(),
                inputs);
        ReportWidgetDataDto widget = result.widget();
        return new ReportKpiDto(
                definition.getId(),
                definition.getName(),
                widget.widgetId(),
                widget.title(),
                widget.chartType(),
                widget.dataSource(),
                widget.measure(),
                widget.groupBy(),
                widget.unit(),
                widget.total(),
                widget.priorTotal(),
                widget.changePercent(),
                period.start(),
                period.end(),
                period.priorStart(),
                period.priorEnd(),
                Instant.now(clock).toString(),
                result.unavailabilityReason() == null,
                result.unavailabilityReason());
    }

    /**
     * Generates interactive figures once and starts a shared asynchronous narrative on a cache miss.
     * The returned handle resolves to a document built from these exact frozen figures.
     */
    @RequirePermission(Permission.REPORT_READ)
    public ReportDocumentDto generateInteractive(
            int id,
            ReportGenerateRequest request,
            NarrativeMode mode) {
        if (mode != NarrativeMode.FULL) {
            return generateInternal(id, request, mode);
        }
        PreparedReport prepared = prepareReport(id, request);
        ReportNarrativeDto cached = aiReportNarrativeService.cachedNarrative(
                id,
                prepared.definitionName(),
                prepared.period().start(),
                prepared.period().end(),
                prepared.figures().appendix());
        ReportDocumentDto initial = document(prepared, cached);
        if (!NARRATIVE_NOT_CACHED.equals(cached.reason())) {
            return initial;
        }

        Set<Permission> requiredPermissions = new HashSet<>(reportPermissionPolicy.requiredFor(initial));
        requiredPermissions.add(Permission.AI_USE);
        ReportDocumentDto unavailable = document(
                prepared,
                ReportNarrativeDto.unavailable(NARRATIVE_NOT_CONFIGURED));
        AiGenerationStatusDto generation;
        try {
            generation = aiGenerationService.startAtRestrictionEpoch(
                    AiFeature.REPORT_NARRATIVE,
                    new ReportGenerationIdentity(
                            id,
                            prepared.definition(),
                            prepared.period().start(),
                            prepared.period().end(),
                            prepared.figures(),
                            prepared.restrictionEpoch()),
                    requiredPermissions,
                    unavailable,
                    () -> generateNarrative(prepared),
                    prepared.restrictionEpoch());
        } catch (TooManyRequestsException exception) {
            return document(prepared, ReportNarrativeDto.unavailable(NARRATIVE_RATE_LIMITED));
        }
        return new ReportDocumentDto(
                initial.definition(),
                initial.periodStart(),
                initial.periodEnd(),
                initial.priorPeriodStart(),
                initial.priorPeriodEnd(),
                initial.narrative(),
                initial.widgets(),
                initial.appendix(),
                initial.citations(),
                initial.generatedAt(),
                generation);
    }

    /** Freezes a generated report document as an immutable snapshot. */
    @RequirePermission(Permission.REPORT_CREATE)
    public ReportSnapshotDto createSnapshot(int id, ReportGenerateRequest request) {
        workspaceService.requirePermission(Permission.REPORT_READ);
        ReportDocumentDto document = generateInternal(id, request, NarrativeMode.FULL);
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        String computedResult = serialize(document, MAX_SNAPSHOT_BYTES, "Report snapshot is too large");
        ReportSnapshot persisted = transactionTemplate.execute(status -> persistManualSnapshot(
                workspaceId, id, document, computedResult, authService.getCurrentUser().getId()));
        if (persisted == null) {
            throw new ResourceNotFoundException("Report snapshot could not be created");
        }
        return toSnapshotDto(persisted);
    }

    /**
     * Freezes one claimed scheduled delivery under the run-as identity. Authorization derives from
     * {@link ScheduleService#deliveryAccess(ReportSchedule)}, which re-derives the run-as user's
     * permissions immediately before the occurrence is claimed. This boundary deliberately does
     * not require {@link Permission#REPORT_CREATE}: delivery access validates
     * {@link Permission#REPORT_READ}, plus {@link Permission#GOAL_READ} for attainment, through
     * {@link ReportPermissionPolicy}, and adding snapshot-create authorization after the committed
     * claim would burn a valid read-only run-as occurrence without delivering it. The method is
     * package-private so the same-package scheduler can call it while it remains outside the
     * public-mutator architecture rule. The workspace-scoped schedule row lock enforces tenant
     * equality for the migration's deliberately single-column schedule foreign key; a composite
     * foreign key including the non-null workspace id cannot use {@code ON DELETE SET NULL}.
     */
    ReportSnapshotDto createDeliverySnapshot(int reportId, int scheduleId) {
        workspaceService.requirePermission(Permission.REPORT_READ);
        ReportDocumentDto document = generateInternal(reportId, null, NarrativeMode.FULL);
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        String computedResult = serialize(document, MAX_SNAPSHOT_BYTES, "Report snapshot is too large");
        ReportSnapshot persisted = transactionTemplate.execute(status -> persistDeliverySnapshot(
                workspaceId, reportId, scheduleId, document, computedResult,
                authService.getCurrentUser().getId()));
        if (persisted == null) {
            throw new ResourceNotFoundException("Report snapshot could not be created");
        }
        return toSnapshotDto(persisted);
    }

    private ReportSnapshot persistManualSnapshot(
            int workspaceId,
            int reportId,
            ReportDocumentDto document,
            String computedResult,
            int generatedBy) {
        reportMapper.lockDefinitions(workspaceId);
        if (reportMapper.getDefinition(workspaceId, reportId) == null) {
            throw new ResourceNotFoundException("Report not found with id: " + reportId);
        }
        if (reportMapper.countManualSnapshots(workspaceId, reportId) >= MAX_SNAPSHOTS_PER_REPORT) {
            throw new BadRequestException("A report cannot have more than 100 snapshots");
        }
        requireWorkspaceSnapshotCapacity(workspaceId, computedResult);
        return insertSnapshot(
                workspaceId, reportId, document, computedResult, generatedBy,
                SNAPSHOT_ORIGIN_MANUAL, null);
    }

    private ReportSnapshot persistDeliverySnapshot(
            int workspaceId,
            int reportId,
            int scheduleId,
            ReportDocumentDto document,
            String computedResult,
            int generatedBy) {
        reportMapper.lockDefinitions(workspaceId);
        if (reportMapper.getDefinition(workspaceId, reportId) == null) {
            throw new ResourceNotFoundException("Report not found with id: " + reportId);
        }
        ReportSchedule schedule = scheduleMapper.lockById(workspaceId, scheduleId);
        if (schedule == null || !schedule.isEnabled()
                || schedule.getReportDefinitionId() != reportId
                || schedule.getRunAsUserId() != generatedBy) {
            throw new ResourceNotFoundException("Report delivery schedule is unavailable");
        }
        reportMapper.deleteScheduledSnapshotsBeyondRetention(
                workspaceId, scheduleId, reportId, MAX_SCHEDULED_SNAPSHOTS_PER_SCHEDULE - 1);
        requireDeliverySnapshotCapacity(workspaceId, computedResult);
        return insertSnapshot(
                workspaceId, reportId, document, computedResult, generatedBy,
                SNAPSHOT_ORIGIN_SCHEDULED, scheduleId);
    }

    private ReportSnapshot insertSnapshot(
            int workspaceId,
            int reportId,
            ReportDocumentDto document,
            String computedResult,
            int generatedBy,
            String origin,
            Integer reportScheduleId) {
        ReportSnapshot snapshot = new ReportSnapshot();
        snapshot.setWorkspaceId(workspaceId);
        snapshot.setReportDefinitionId(reportId);
        snapshot.setPeriodStart(document.periodStart());
        snapshot.setPeriodEnd(document.periodEnd());
        snapshot.setComputedResult(computedResult);
        snapshot.setOrigin(origin);
        snapshot.setReportScheduleId(reportScheduleId);
        snapshot.setGeneratedBy(generatedBy);
        reportMapper.insertSnapshot(snapshot);
        auditService.record("report.snapshot.create", "report", reportId, document.definition().name(),
                "Created report snapshot", null);
        ReportSnapshot persisted = reportMapper.getSnapshot(workspaceId, reportId, snapshot.getId());
        if (persisted == null) {
            throw new ResourceNotFoundException("Report snapshot not found with id: " + snapshot.getId());
        }
        return persisted;
    }

    private boolean hasWorkspaceSnapshotCapacity(int workspaceId, String computedResult) {
        return reportMapper.countWorkspaceSnapshots(workspaceId) < MAX_SNAPSHOTS_PER_WORKSPACE
                && reportMapper.workspaceSnapshotBytes(workspaceId)
                        + computedResult.getBytes(StandardCharsets.UTF_8).length
                                <= MAX_SNAPSHOT_BYTES_PER_WORKSPACE;
    }

    private void requireWorkspaceSnapshotCapacity(int workspaceId, String computedResult) {
        if (!hasWorkspaceSnapshotCapacity(workspaceId, computedResult)) {
            throw new BadRequestException("The workspace report snapshot quota has been reached");
        }
    }

    /**
     * Preserves scheduled-delivery continuity by evicting the oldest scheduled-origin snapshots
     * workspace-wide when the workspace quota is exhausted. Eviction runs in small batches and
     * stops as soon as the snapshot fits, so a workspace that is marginally over its quota loses
     * only the few oldest scheduled rows rather than its whole evidence tail.
     *
     * <p>This deliberately trades the oldest scheduled evidence for delivery continuity: without
     * it, a workspace reaching its quota would fail every future scheduled delivery permanently
     * and silently, because the occurrence is already claimed by the time this runs. Manual
     * snapshots are never evicted, and a workspace that still cannot fit the snapshot fails closed
     * so the scheduler audits the failure and sends nothing.
     */
    private void requireDeliverySnapshotCapacity(int workspaceId, String computedResult) {
        for (int batch = 0; batch < MAX_SCHEDULED_EVICTION_BATCHES; batch++) {
            if (hasWorkspaceSnapshotCapacity(workspaceId, computedResult)) {
                return;
            }
            if (reportMapper.deleteOldestScheduledSnapshots(
                    workspaceId, SCHEDULED_EVICTION_BATCH_SIZE) == 0) {
                break;
            }
        }
        requireWorkspaceSnapshotCapacity(workspaceId, computedResult);
    }

    /** Lists frozen snapshots for a report definition. */
    @RequirePermission(Permission.REPORT_READ)
    public List<ReportSnapshotSummaryDto> listSnapshots(int id) {
        requireDefinition(id);
        return reportMapper.getSnapshots(workspaceService.getCurrentWorkspaceId(), id, MAX_SNAPSHOT_LIST_SIZE);
    }

    /** Returns one frozen report snapshot. */
    @RequirePermission(Permission.REPORT_READ)
    public ReportSnapshotDto getSnapshot(int reportId, int snapshotId) {
        requireDefinition(reportId);
        ReportSnapshot snapshot = reportMapper.getSnapshot(
                workspaceService.getCurrentWorkspaceId(), reportId, snapshotId);
        if (snapshot == null) {
            throw new ResourceNotFoundException("Report snapshot not found with id: " + snapshotId);
        }
        ReportSnapshotDto dto = toSnapshotDto(snapshot);
        requireGoalReadForAttainment(dto.computedResult());
        return dto;
    }

    /** Deletes one frozen report snapshot. */
    @Transactional
    @RequirePermission(Permission.REPORT_DELETE)
    public void deleteSnapshot(int reportId, int snapshotId) {
        requireDefinition(reportId);
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        ReportSnapshot snapshot = reportMapper.getSnapshot(workspaceId, reportId, snapshotId);
        if (snapshot == null) {
            throw new ResourceNotFoundException("Report snapshot not found with id: " + snapshotId);
        }
        deletionPolicy.requireDeletable(snapshot.getGeneratedBy());
        if (reportMapper.deleteSnapshot(workspaceId, reportId, snapshotId) == 0) {
            throw new ResourceNotFoundException("Report snapshot not found with id: " + snapshotId);
        }
        auditService.record("report.snapshot.delete", "report", reportId, null,
                "Deleted report snapshot " + snapshotId, null);
    }

    /** Exports a live report appendix as RFC-4180 CSV. */
    @RequirePermission(Permission.REPORT_READ)
    public String exportCsv(int id, ReportGenerateRequest request) {
        return appendixCsv(generateInternal(id, request, NarrativeMode.NONE));
    }

    /** Exports a frozen report appendix as RFC-4180 CSV. */
    @RequirePermission(Permission.REPORT_READ)
    public String exportSnapshotCsv(int reportId, int snapshotId) {
        return appendixCsv(getSnapshot(reportId, snapshotId).computedResult());
    }

    private ReportDocumentDto generateInternal(int id, ReportGenerateRequest request, NarrativeMode mode) {
        PreparedReport prepared = prepareReport(id, request);
        ReportNarrativeDto narrative = switch (mode) {
            case NONE -> ReportNarrativeDto.unavailable("not_requested");
            case CACHED -> aiReportNarrativeService.cachedNarrative(
                    id,
                    prepared.definitionName(),
                    prepared.period().start(),
                    prepared.period().end(),
                    prepared.figures().appendix());
            case FULL -> aiReportNarrativeService.generate(
                    id,
                    prepared.definitionName(),
                    prepared.period().start(),
                    prepared.period().end(),
                    prepared.figures().appendix(),
                    prepared.restrictionEpoch());
        };
        return document(prepared, narrative);
    }

    private PreparedReport prepareReport(int id, ReportGenerateRequest request) {
        ReportDefinition definition = requireDefinition(id);
        ReportConfig config = parseConfig(definition.getConfigJson());
        validateConfig(definition.getCadence(), definition.getTemplateKey(), config);
        PeriodWindow period = resolvePeriod(definition.getCadence(), config.range(), request, config.bucket());
        validateAttainmentPeriod(config, period);
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        long restrictionEpoch = aiRestrictionEpoch.current(workspaceId);
        GeneratedFigures figures = generateFigures(workspaceId, config, period);
        return new PreparedReport(
                toDefinitionDto(definition),
                definition.getName(),
                period,
                figures,
                restrictionEpoch,
                Instant.now(clock).toString());
    }

    private AiGenerationTaskResult<ReportDocumentDto> generateNarrative(PreparedReport prepared) {
        ReportNarrativeDto narrative = aiReportNarrativeService.generate(
                prepared.definition().id(),
                prepared.definitionName(),
                prepared.period().start(),
                prepared.period().end(),
                prepared.figures().appendix(),
                prepared.restrictionEpoch());
        if (narrative.available()) {
            return AiGenerationTaskResult.resolved(document(prepared, narrative));
        }
        if (NARRATIVE_FAILURE_REASONS.contains(narrative.reason())) {
            return AiGenerationTaskResult.failed(narrative.reason());
        }
        return AiGenerationTaskResult.unavailable(document(prepared, narrative));
    }

    private ReportDocumentDto document(PreparedReport prepared, ReportNarrativeDto narrative) {
        List<ReportCitationDto> citations = citations(narrative, prepared.figures().appendix());
        return new ReportDocumentDto(
                prepared.definition(),
                prepared.period().start(),
                prepared.period().end(),
                prepared.period().priorStart(),
                prepared.period().priorEnd(),
                narrative,
                prepared.figures().widgets(),
                prepared.figures().appendix(),
                citations,
                prepared.generatedAt());
    }

    /** Generates every widget's figures and appendix rows for the resolved reporting period. */
    private GeneratedFigures generateFigures(int workspaceId, ReportConfig config, PeriodWindow period) {
        List<ReportWidgetDataDto> widgets = new ArrayList<>();
        List<ReportAppendixRowDto> appendix = new ArrayList<>();
        GenerationInputs inputs = generationInputs(workspaceId, config, config.widgets(), period);
        int widgetIndex = 0;
        for (ReportWidgetConfig widget : config.widgets()) {
            WidgetResult result = generateWidget(
                    workspaceId, widget, config.filters(), config.bucket(), period, widgetIndex++, inputs);
            widgets.add(result.widget());
            appendix.addAll(result.appendix());
        }
        return new GeneratedFigures(List.copyOf(widgets), List.copyOf(appendix));
    }

    private WidgetResult generateWidget(int workspaceId, ReportWidgetConfig widget, ReportFilters filters,
            String bucket, PeriodWindow period, int widgetIndex, GenerationInputs inputs) {
        String effectiveBucket = FORECAST_MEASURES.contains(widget.measure()) ? "month" : bucket;
        String aggregationKey = widget.dataSource() + '\u0000' + widget.measure() + '\u0000'
                + normalizeGroup(widget.groupBy()) + '\u0000' + effectiveBucket;
        List<ReportAggregateRow> current = inputs.currentRows().get(aggregationKey);
        List<ReportAggregateRow> prior = inputs.priorRows().get(aggregationKey);
        if (current == null || prior == null) {
            RowPair generated = aggregateWidget(workspaceId, widget, filters, effectiveBucket, period, inputs);
            current = generated.current();
            prior = generated.prior();
            inputs.currentRows().put(aggregationKey, current);
            inputs.priorRows().put(aggregationKey, prior);
        }
        return widgetResult(
                widget, widgetIndex, current, prior, effectiveBucket, period,
                priorComparable(widget, filters),
                networkAuthoritativeTotalRows(widget, inputs));
    }

    private static WidgetSelection selectWidget(ReportConfig config, String widgetId) {
        for (int index = 0; index < config.widgets().size(); index++) {
            ReportWidgetConfig widget = config.widgets().get(index);
            if (widget.id().equals(widgetId)) {
                return new WidgetSelection(widget, index);
            }
        }
        throw new ResourceNotFoundException("Report widget not found with id: " + widgetId);
    }

    /**
     * Authoritative total rows for a grouped relationship-network widget: the ungrouped ("none")
     * aggregation over the same snapshot, so a display cap (top-N paths, connectors, or reverse-intro
     * pairs) never truncates the KPI scalar or a scheduled-delivery headline. Distinct-count measures
     * (reachable accounts) also require this because summing per-connector rows double-counts a company
     * reachable through several connectors. Returns {@code null} for non-network or already-ungrouped
     * widgets, which keep deriving their total from their own rows.
     */
    private List<ReportAggregateRow> networkAuthoritativeTotalRows(
            ReportWidgetConfig widget, GenerationInputs inputs) {
        if ("none".equals(normalizeGroup(widget.groupBy()))) {
            return null;
        }
        ReportWidgetConfig ungrouped = new ReportWidgetConfig(
                widget.id(), widget.title(), widget.dataSource(), widget.measure(), "none", widget.chartType());
        if (REVERSE_INTRO_MEASURES.contains(widget.measure())) {
            return ReportNetworkService.aggregateReverseIntro(ungrouped, inputs.reverseIntroSuggestions());
        }
        if (WARM_INTRO_MEASURES.contains(widget.measure())) {
            return ReportNetworkService.aggregateWarmIntro(ungrouped, inputs.warmIntroOpportunities());
        }
        return null;
    }

    private RowPair aggregateWidget(
            int workspaceId,
            ReportWidgetConfig widget,
            ReportFilters filters,
            String bucket,
            PeriodWindow period,
            GenerationInputs inputs) {
        if (REVERSE_INTRO_MEASURES.contains(widget.measure())) {
            return new RowPair(
                    ReportNetworkService.aggregateReverseIntro(widget, inputs.reverseIntroSuggestions()),
                    List.of());
        } else if ("relationships".equals(widget.dataSource())) {
            boolean companies = "company_count".equals(widget.measure());
            return new RowPair(
                    relationshipRows(widget, filters,
                            companies ? inputs.currentCompanyRelationships() : inputs.currentRelationships()),
                    relationshipRows(widget, filters,
                            companies ? inputs.priorCompanyRelationships() : inputs.priorRelationships()));
        } else if ("attainment".equals(widget.measure())) {
            return aggregateAttainment(workspaceId, widget, filters, period, inputs);
        } else if ("at_risk_revenue".equals(widget.measure())) {
            return new RowPair(
                    riskRows(workspaceId, widget, filters, period.start(), period.end(), period.zone(), inputs),
                    List.of());
        } else if (FORECAST_MEASURES.contains(widget.measure())) {
            return new RowPair(aggregateForecast(
                    workspaceId, widget, filters, period.zone(), inputs), List.of());
        } else if (Set.of("coverage_gap_count", "coverage_gap_open_pipeline_value")
                .contains(widget.measure())) {
            List<ReportAggregateRow> current = reportMapper.aggregateCoverageGaps(query(
                    workspaceId, widget, filters, bucket, period.currentStartUtc(),
                    period.currentEndUtc(), period.zone()),
                    period.currentEndUtc().minusNanos(1_000_000),
                    WARMTH_MODEL.sqlParameters());
            List<ReportAggregateRow> prior = priorComparable(widget, filters)
                    ? reportMapper.aggregateCoverageGaps(query(
                            workspaceId, widget, filters, bucket, period.priorStartUtc(),
                            period.priorEndUtc(), period.zone()),
                            period.priorEndUtc().minusNanos(1_000_000),
                            WARMTH_MODEL.sqlParameters())
                    : List.of();
            return new RowPair(
                    countTotalRow(widget, current),
                    countTotalRow(widget, prior));
        } else if (Set.of("single_threaded_deal_count", "single_threaded_deal_value")
                .contains(widget.measure())) {
            List<ReportAggregateRow> current = reportMapper.aggregateSingleThreadedDeals(query(
                    workspaceId, widget, filters, bucket, period.currentStartUtc(),
                    period.currentEndUtc(), period.zone()));
            return new RowPair(countTotalRow(widget, current), List.of());
        } else if (WARM_INTRO_MEASURES.contains(widget.measure())) {
            return new RowPair(
                    ReportNetworkService.aggregateWarmIntro(widget, inputs.warmIntroOpportunities()),
                    List.of());
        }
        List<ReportAggregateRow> current = aggregate(widget.dataSource(), query(
                        workspaceId,
                        widget,
                        filters,
                        bucket,
                        period.currentStartUtc(),
                        period.currentEndUtc(),
                        period.zone()));
        List<ReportAggregateRow> prior = aggregate(widget.dataSource(), query(
                        workspaceId,
                        widget,
                        filters,
                        bucket,
                        period.priorStartUtc(),
                        period.priorEndUtc(),
                        period.zone()));
        return new RowPair(
                hydrateOwnerLabels(current, widget, inputs.ownerLabels()),
                hydrateOwnerLabels(prior, widget, inputs.ownerLabels()));
    }

    /**
     * Builds one widget's points, appendix rows, and scalars.
     *
     * <p>Current-period and prior-period emptiness are tracked separately. A rate, average, or
     * discount has no value at all without a cohort, so an absent prior row stays undefined instead
     * of collapsing to a measured zero — otherwise a period that simply had no quotes, decisions, or
     * discounted deals would be published as a real figure and turn every current value into a
     * fabricated change against it.
     */
    private WidgetResult widgetResult(
            ReportWidgetConfig widget,
            int widgetIndex,
            List<ReportAggregateRow> current,
            List<ReportAggregateRow> prior,
            String bucket,
            PeriodWindow period,
            boolean priorComparable,
            List<ReportAggregateRow> authoritativeTotalRows) {
        Map<String, ReportAggregateRow> priorByKey = new HashMap<>();
        for (ReportAggregateRow row : prior) {
            priorByKey.put(comparisonKey(row, widget, bucket, period.priorStart()), row);
        }
        boolean undefinedWhenEmpty = undefinedWhenEmpty(widget);
        List<ReportDataPointDto> points = new ArrayList<>();
        List<ReportAppendixRowDto> appendix = new ArrayList<>();
        Set<String> units = new LinkedHashSet<>();
        Set<String> unitKeys = new LinkedHashSet<>();
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal priorTotal = BigDecimal.ZERO;
        boolean priorTotalDefined = priorComparable && !(undefinedWhenEmpty && prior.isEmpty());
        int pointIndex = 0;
        for (ReportAggregateRow row : current) {
            ReportAggregateRow priorRow = priorByKey.remove(
                    comparisonKey(row, widget, bucket, period.start()));
            BigDecimal priorValue = priorPointValue(priorRow, priorComparable, undefinedWhenEmpty);
            BigDecimal value = safe(row.value());
            String sourceId = "metric." + widgetIndex + "." + pointIndex++;
            String unit = normalizedUnit(row.unit());
            points.add(new ReportDataPointDto(row.groupKey(), row.groupLabel(), value, priorValue, sourceId));
            appendix.add(new ReportAppendixRowDto(sourceId, widget.id(),
                    widget.measure() + " · " + row.groupLabel(), value, priorValue, unit));
            units.add(unit);
            unitKeys.add(unitKey(row.unit()));
            total = total.add(value);
            if (priorValue == null) {
                priorTotalDefined = false;
            } else {
                priorTotal = priorTotal.add(priorValue);
            }
        }
        for (ReportAggregateRow row : priorByKey.values()) {
            ReportAggregateRow alignedRow = alignPriorDateRow(
                    row, widget, bucket, period.priorStart(), period.start());
            BigDecimal priorValue = safe(row.value());
            String sourceId = "metric." + widgetIndex + "." + pointIndex++;
            String unit = normalizedUnit(row.unit());
            points.add(new ReportDataPointDto(
                    alignedRow.groupKey(), alignedRow.groupLabel(), BigDecimal.ZERO, priorValue, sourceId));
            appendix.add(new ReportAppendixRowDto(sourceId, widget.id(),
                    widget.measure() + " · " + alignedRow.groupLabel(), BigDecimal.ZERO, priorValue, unit));
            units.add(unit);
            unitKeys.add(unitKey(row.unit()));
            priorTotal = priorTotal.add(priorValue);
        }
        if ("date".equals(normalizeGroup(widget.groupBy()))) {
            points.sort(Comparator
                    .comparing((ReportDataPointDto point) -> parseBucketDate(point.key(), bucket))
                    .thenComparing(ReportDataPointDto::key));
        }
        String unit = units.size() == 1 && unitKeys.size() == 1 ? units.iterator().next() : "mixed";
        boolean additive = DISCOUNT_MEASURES.contains(widget.measure())
                ? points.size() <= 1
                : !NON_ADDITIVE_MEASURES.contains(widget.measure())
                        || "none".equals(normalizeGroup(widget.groupBy()));
        boolean undefinedCurrent = undefinedWhenEmpty && current.isEmpty();
        BigDecimal scalarTotal = total;
        Set<String> scalarUnits = unitKeys;
        if (authoritativeTotalRows != null) {
            scalarTotal = BigDecimal.ZERO;
            scalarUnits = new LinkedHashSet<>();
            for (ReportAggregateRow row : authoritativeTotalRows) {
                scalarTotal = scalarTotal.add(safe(row.value()));
                scalarUnits.add(unitKey(row.unit()));
            }
        }
        String unavailabilityReason = null;
        if (scalarUnits.size() > 1) {
            unavailabilityReason = "mixed_currency";
        } else if (!additive) {
            unavailabilityReason = "non_additive";
        } else if (undefinedCurrent) {
            unavailabilityReason = "undefined";
        }
        BigDecimal publicTotal = unavailabilityReason == null ? scalarTotal : null;
        BigDecimal publicPrior = unitKeys.size() <= 1 && additive && priorTotalDefined ? priorTotal : null;
        BigDecimal change = "attainment".equals(widget.measure())
                ? attainmentPercent(publicTotal, publicPrior)
                : percentChange(publicTotal, publicPrior);
        ReportWidgetDataDto data = new ReportWidgetDataDto(
                widget.id(), displayTitle(widget), widget.chartType(), widget.dataSource(), widget.measure(),
                widget.groupBy(), unit, publicTotal, publicPrior, change, List.copyOf(points));
        return new WidgetResult(data, List.copyOf(appendix), unavailabilityReason);
    }

    /**
     * Whether the measure is a ratio, average, or attainment figure that has no value on an empty
     * cohort, so an absent row means undefined rather than zero.
     */
    private static boolean undefinedWhenEmpty(ReportWidgetConfig widget) {
        return "attainment".equals(widget.measure())
                || UNDEFINED_WHEN_EMPTY_MEASURES.contains(widget.measure());
    }

    /**
     * The prior-period value aligned to one current-period group, or {@code null} when the periods
     * are not comparable or the prior period has no cohort for a measure that is undefined without
     * one. A zero here would be published as a measured prior figure and as a real change.
     */
    private static BigDecimal priorPointValue(
            ReportAggregateRow priorRow, boolean priorComparable, boolean undefinedWhenEmpty) {
        if (!priorComparable || (priorRow == null && undefinedWhenEmpty)) {
            return null;
        }
        return priorRow == null ? BigDecimal.ZERO : safe(priorRow.value());
    }

    private List<ReportAggregateRow> aggregate(String dataSource, ReportAggregateQuery query) {
        return switch (dataSource) {
            case "deals" -> DISCOUNT_MEASURES.contains(query.measure())
                    ? reportMapper.aggregateDealDiscount(query)
                    : reportMapper.aggregateDeals(query);
            case "activities" -> reportMapper.aggregateActivities(query);
            case "tasks" -> reportMapper.aggregateTasks(query);
            case "people" -> EMPLOYMENT_MEASURES.contains(query.measure())
                    ? reportMapper.aggregateEmployment(query)
                    : reportMapper.aggregatePeople(query);
            case "leads" -> reportMapper.aggregateLeadLifecycle(query);
            case "companies" -> reportMapper.aggregateCompanies(query);
            case "documents" -> DOCUMENT_APPROVAL_MEASURES.contains(query.measure())
                    ? reportMapper.aggregateDocumentApprovals(query)
                    : DOCUMENT_OUTCOME_MEASURES.contains(query.measure())
                            ? reportMapper.aggregateDocumentOutcomes(query)
                            : reportMapper.aggregateDocuments(query);
            default -> throw new BadRequestException("Unsupported report data source: " + dataSource);
        };
    }

    private static List<ReportAggregateRow> countTotalRow(
            ReportWidgetConfig widget, List<ReportAggregateRow> rows) {
        if (!rows.isEmpty() || !Set.of("coverage_gap_count", "single_threaded_deal_count")
                .contains(widget.measure()) || !"none".equals(normalizeGroup(widget.groupBy()))) {
            return rows;
        }
        return List.of(new ReportAggregateRow("total", "Total", "count", BigDecimal.ZERO));
    }

    /**
     * Aligns won-revenue actuals to same-currency goals. Owner actuals without a
     * matching goal are omitted; workspace goals include all workspace actuals.
     */
    private RowPair aggregateAttainment(
            int workspaceId,
            ReportWidgetConfig widget,
            ReportFilters filters,
            PeriodWindow period,
            GenerationInputs inputs) {
        workspaceService.requirePermission(Permission.GOAL_READ);
        String periodType = switch (period.cadence()) {
            case "monthly" -> "month";
            case "quarterly" -> "quarter";
            default -> throw new BadRequestException("Attainment requires a monthly or quarterly report cadence");
        };
        LocalDate goalPeriodStart = "month".equals(periodType)
                ? period.start().withDayOfMonth(1)
                : LocalDate.of(
                        period.start().getYear(),
                        ((period.start().getMonthValue() - 1) / 3) * 3 + 1,
                        1);
        ReportWidgetConfig actualWidget = new ReportWidgetConfig(
                widget.id(), widget.title(), "deals", "won_revenue", widget.groupBy(), widget.chartType());
        List<ReportAggregateRow> actualRows = reportMapper.aggregateDeals(query(
                workspaceId, actualWidget, filters, "month",
                period.currentStartUtc(), period.currentEndUtc(), period.zone()));
        Map<String, BigDecimal> actualByKey = new HashMap<>();
        for (ReportAggregateRow row : actualRows) {
            int separator = row.groupKey().indexOf(':');
            String suffix = separator >= 0 ? row.groupKey().substring(separator + 1) : row.groupKey();
            actualByKey.put(unitKey(row.unit()) + ':' + suffix, safe(row.value()));
        }
        String group = normalizeGroup(widget.groupBy());
        List<ReportAggregateRow> current = new ArrayList<>();
        List<ReportAggregateRow> targets = new ArrayList<>();
        Set<String> emitted = new HashSet<>();
        for (ReportGoal goal : goalMapper.getGoalsForPeriod(
                workspaceId, "won_revenue", periodType, goalPeriodStart)) {
            if ("owner".equals(group) != (goal.getOwnerId() != null)) {
                continue;
            }
            if ("owner".equals(group) && filters != null && filters.ownerIds() != null
                    && !filters.ownerIds().isEmpty() && !filters.ownerIds().contains(goal.getOwnerId())) {
                continue;
            }
            String currency = unitKey(goal.getCurrency());
            String ownerKey = goal.getOwnerId() == null ? "total" : Integer.toString(goal.getOwnerId());
            String key = currency + ':' + ownerKey;
            if (!emitted.add(key)) {
                continue;
            }
            String label = currency + " · " + (goal.getOwnerId() == null
                    ? "Workspace-wide"
                    : inputs.ownerLabels().getOrDefault(ownerKey, "Unassigned"));
            current.add(new ReportAggregateRow(
                    key, label, currency, actualByKey.getOrDefault(key, BigDecimal.ZERO)));
            targets.add(new ReportAggregateRow(key, label, currency, safe(goal.getTargetValue())));
        }
        return new RowPair(List.copyOf(current), List.copyOf(targets));
    }

    private static boolean priorComparable(ReportWidgetConfig widget, ReportFilters filters) {
        if (Set.of(
                "at_risk_revenue", "coverage_gap_open_pipeline_value",
                "single_threaded_deal_count", "single_threaded_deal_value")
                .contains(widget.measure()) || FORECAST_MEASURES.contains(widget.measure())
                || WARM_INTRO_MEASURES.contains(widget.measure())
                || REVERSE_INTRO_MEASURES.contains(widget.measure())) {
            return false;
        }
        if (!"coverage_gap_count".equals(widget.measure())) {
            return true;
        }
        boolean dealFilters = filters != null && (
                filters.pipelineIds() != null && !filters.pipelineIds().isEmpty()
                || filters.ownerIds() != null && !filters.ownerIds().isEmpty()
                || filters.statuses() != null && !filters.statuses().isEmpty());
        return "none".equals(normalizeGroup(widget.groupBy())) && !dealFilters;
    }

    private static List<ReportAggregateRow> hydrateOwnerLabels(
            List<ReportAggregateRow> rows,
            ReportWidgetConfig widget,
            Map<String, String> ownerLabels) {
        if (!"owner".equals(normalizeGroup(widget.groupBy()))) {
            return rows;
        }
        return rows.stream().map(row -> {
            int keySeparator = row.groupKey().lastIndexOf(':');
            String ownerId = keySeparator >= 0
                    ? row.groupKey().substring(keySeparator + 1)
                    : row.groupKey();
            String ownerLabel = ownerLabels.getOrDefault(ownerId, "Unassigned");
            int labelSeparator = row.groupLabel().indexOf(" · ");
            String label = labelSeparator >= 0
                    ? row.groupLabel().substring(0, labelSeparator + 3) + ownerLabel
                    : ownerLabel;
            return new ReportAggregateRow(row.groupKey(), label, row.unit(), row.value());
        }).toList();
    }

    private List<ReportAggregateRow> relationshipRows(ReportWidgetConfig widget,
            ReportFilters filters, List<RelationshipTemperatureDto> scores) {
        Map<String, Long> counts = new LinkedHashMap<>();
        Set<String> allowedBands = filters == null || filters.warmthBands() == null
                ? Set.of()
                : new HashSet<>(filters.warmthBands());
        for (RelationshipTemperatureDto score : scores) {
            if (!allowedBands.isEmpty() && !allowedBands.contains(score.getBand())) {
                continue;
            }
            String key = switch (normalizeGroup(widget.groupBy())) {
                case "warmth_band" -> score.getBand();
                case "trend" -> score.getTrend();
                default -> "total";
            };
            counts.merge(key, 1L, Long::sum);
        }
        return counts.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new ReportAggregateRow(entry.getKey(), title(entry.getKey()), "count",
                        BigDecimal.valueOf(entry.getValue())))
                .toList();
    }

    private List<ReportAggregateRow> riskRows(
            int workspaceId,
            ReportWidgetConfig widget,
            ReportFilters filters,
            LocalDate start,
            LocalDate end,
            ZoneId zone,
            GenerationInputs inputs) {
        Map<String, List<Integer>> idsByLevel = new LinkedHashMap<>();
        for (DealRiskDto risk : inputs.risks()) {
            String level = "risk".equals(normalizeGroup(widget.groupBy())) ? risk.getLevel() : "total";
            idsByLevel.computeIfAbsent(level, ignored -> new ArrayList<>()).add(risk.getDealId());
        }
        Map<RiskRowKey, BigDecimal> values = new LinkedHashMap<>();
        LocalDateTime startUtc = LocalDateTime.ofInstant(start.atStartOfDay(zone).toInstant(), ZoneOffset.UTC);
        LocalDateTime endUtc = LocalDateTime.ofInstant(end.plusDays(1).atStartOfDay(zone).toInstant(), ZoneOffset.UTC);
        for (Map.Entry<String, List<Integer>> entry : idsByLevel.entrySet()) {
            for (int from = 0; from < entry.getValue().size(); from += RISK_ID_BATCH_SIZE) {
                List<Integer> batch = entry.getValue().subList(
                        from, Math.min(from + RISK_ID_BATCH_SIZE, entry.getValue().size()));
                ReportAggregateQuery query = query(
                        workspaceId,
                        new ReportWidgetConfig(
                                widget.id(), widget.title(), "deals", "open_pipeline_value", "none", widget.chartType()),
                        filters,
                        "month",
                        startUtc,
                        endUtc,
                        zone,
                        batch);
                for (ReportAggregateRow row : reportMapper.aggregateDeals(query)) {
                    RiskRowKey key = new RiskRowKey(row.unit().strip(), entry.getKey());
                    values.merge(key, safe(row.value()), BigDecimal::add);
                }
            }
        }
        return values.entrySet().stream()
                .map(entry -> new ReportAggregateRow(
                        entry.getKey().currency() + ':' + entry.getKey().level(),
                        entry.getKey().currency() + " · " + title(entry.getKey().level()),
                        entry.getKey().currency(),
                        entry.getValue()))
                .toList();
    }

    /**
     * Aggregates the forward window {@code [today, today + 3 months)} into expected-close months.
     * For each non-negative open-deal value {@code v}, the probability {@code p} is an empirical
     * blend of distinct closed deals that reached the stage before closing and the legacy
     * close-at-stage rate. The legacy rate contributes ten prior deals while transition history
     * accrues, and falls back to the workspace-wide closed-deal rate and then the neutral rate
     * {@code 0.5}. Best is {@code sum(v)}, likely is {@code sum(v * p)}, and commit/worst is
     * {@code sum(v * p^2)}.
     * Squaring is a conservative floor and, because {@code 0 <= p <= 1}, guarantees
     * {@code worst <= likely <= best}. One mapper statement computes all three bands from one
     * database snapshot, and the result is reused by sibling widgets with the same grouping. The
     * mapper defensively treats negative stored values as zero to preserve the invariant for legacy
     * data.
     */
    private List<ReportAggregateRow> aggregateForecast(
            int workspaceId,
            ReportWidgetConfig widget,
            ReportFilters filters,
            ZoneId zone,
            GenerationInputs inputs) {
        String cacheKey = normalizeGroup(widget.groupBy());
        List<ReportForecastAggregateRow> bands = inputs.forecastRows().get(cacheKey);
        if (bands == null) {
            LocalDate start = inputs.forecastStart();
            LocalDate endExclusive = start.plusMonths(FORECAST_HORIZON_MONTHS);
            LocalDateTime startUtc = LocalDateTime.ofInstant(start.atStartOfDay(zone).toInstant(), ZoneOffset.UTC);
            LocalDateTime endUtc = LocalDateTime.ofInstant(
                    endExclusive.atStartOfDay(zone).toInstant(), ZoneOffset.UTC);
            ReportAggregateQuery query = query(
                    workspaceId, widget, filters, "month", startUtc, endUtc, zone, null,
                    FORECAST_NEUTRAL_WIN_RATE);
            bands = reportMapper.aggregateForecast(query);
            inputs.forecastRows().put(cacheKey, bands);
        }
        return bands.stream().map(row -> new ReportAggregateRow(
                row.groupKey(), row.groupLabel(), row.unit(), switch (widget.measure()) {
                    case "forecast_best" -> safe(row.bestValue());
                    case "forecast_weighted" -> safe(row.weightedValue());
                    case "forecast_worst" -> safe(row.worstValue());
                    default -> throw new BadRequestException("Unsupported forecast measure: " + widget.measure());
                })).toList();
    }

    private GenerationInputs generationInputs(
            int workspaceId,
            ReportConfig config,
            List<ReportWidgetConfig> requestedWidgets,
            PeriodWindow period) {
        boolean contactRelationships = requestedWidgets.stream()
                .anyMatch(widget -> "relationships".equals(widget.dataSource())
                        && "count".equals(widget.measure()));
        boolean companyRelationships = requestedWidgets.stream()
                .anyMatch(widget -> "relationships".equals(widget.dataSource())
                        && "company_count".equals(widget.measure()));
        boolean risk = requestedWidgets.stream()
                .anyMatch(widget -> "at_risk_revenue".equals(widget.measure()));
        boolean owners = requestedWidgets.stream()
                .anyMatch(widget -> "owner".equals(normalizeGroup(widget.groupBy())));
        boolean network = requestedWidgets.stream()
                .anyMatch(widget -> WARM_INTRO_MEASURES.contains(widget.measure())
                        || REVERSE_INTRO_MEASURES.contains(widget.measure()));
        List<ReportWidgetConfig> warmIntroWidgets = network
                ? config.widgets().stream()
                        .filter(widget -> WARM_INTRO_MEASURES.contains(widget.measure()))
                        .toList()
                : List.of();
        boolean includeReverseIntros = network && config.widgets().stream()
                .anyMatch(widget -> REVERSE_INTRO_MEASURES.contains(widget.measure()));
        ReportNetworkService.NetworkSnapshot networkSnapshot;
        if (warmIntroWidgets.isEmpty()) {
            networkSnapshot = new ReportNetworkService.NetworkSnapshot(
                    List.of(),
                    includeReverseIntros
                            ? reportNetworkService.reverseIntroSuggestions(workspaceId)
                            : List.of());
        } else {
            ReportWidgetConfig networkWidget = warmIntroWidgets.getFirst();
            networkSnapshot = reportNetworkService.snapshot(query(
                        workspaceId,
                        networkWidget,
                        config.filters(),
                        config.bucket(),
                        period.currentStartUtc(),
                        period.currentEndUtc(),
                        period.zone()), includeReverseIntros);
        }
        Set<Integer> currentPeople = contactRelationships
                ? new HashSet<>(reportMapper.getVisiblePersonIdsAt(
                        workspaceId,
                        LocalDateTime.ofInstant(period.currentEndInstant(), ZoneOffset.UTC)))
                : Set.of();
        Set<Integer> priorPeople = contactRelationships
                ? new HashSet<>(reportMapper.getVisiblePersonIdsAt(
                        workspaceId,
                        LocalDateTime.ofInstant(period.priorEndInstant(), ZoneOffset.UTC)))
                : Set.of();
        List<RelationshipTemperatureDto> currentRelationships = contactRelationships
                ? visibleRelationshipScores(
                        scoringService.scoreContacts(workspaceId, period.currentEndInstant()),
                        currentPeople)
                : List.of();
        List<RelationshipTemperatureDto> priorRelationships = contactRelationships
                ? visibleRelationshipScores(
                        scoringService.scoreContacts(workspaceId, period.priorEndInstant()),
                        priorPeople)
                : List.of();
        Set<Integer> currentCompanies = companyRelationships
                ? new HashSet<>(reportMapper.getVisibleCompanyIdsAt(
                        workspaceId,
                        LocalDateTime.ofInstant(period.currentEndInstant(), ZoneOffset.UTC)))
                : Set.of();
        Set<Integer> priorCompanies = companyRelationships
                ? new HashSet<>(reportMapper.getVisibleCompanyIdsAt(
                        workspaceId,
                        LocalDateTime.ofInstant(period.priorEndInstant(), ZoneOffset.UTC)))
                : Set.of();
        List<RelationshipTemperatureDto> currentCompanyRelationships = companyRelationships
                ? visibleRelationshipScores(
                        scoringService.scoreCompanies(workspaceId, period.currentEndInstant()),
                        currentCompanies)
                : List.of();
        List<RelationshipTemperatureDto> priorCompanyRelationships = companyRelationships
                ? visibleRelationshipScores(
                        scoringService.scoreCompanies(workspaceId, period.priorEndInstant()),
                        priorCompanies)
                : List.of();
        Map<String, String> ownerLabels = new HashMap<>();
        if (owners) {
            for (User user : workspaceService.getMembers(workspaceId)) {
                ownerLabels.put(Integer.toString(user.getId()), user.getDisplayName());
            }
        }
        return new GenerationInputs(
                currentRelationships,
                priorRelationships,
                currentCompanyRelationships,
                priorCompanyRelationships,
                risk ? dealRiskService.assessWorkspace(workspaceId) : List.of(),
                networkSnapshot.warmIntroOpportunities(),
                networkSnapshot.reverseIntroSuggestions(),
                Map.copyOf(ownerLabels),
                LocalDate.now(clock.withZone(period.zone())),
                new HashMap<>(),
                new HashMap<>(),
                new HashMap<>());
    }

    private static List<RelationshipTemperatureDto> visibleRelationshipScores(
            List<RelationshipTemperatureDto> scores, Set<Integer> visiblePersonIds) {
        return scores.stream().filter(score -> visiblePersonIds.contains(score.getId())).toList();
    }

    private ReportAggregateQuery query(int workspaceId, ReportWidgetConfig widget, ReportFilters filters,
            String bucket, LocalDateTime startUtc, LocalDateTime endUtc, ZoneId zone) {
        return query(workspaceId, widget, filters, bucket, startUtc, endUtc, zone, null);
    }

    private ReportAggregateQuery query(int workspaceId, ReportWidgetConfig widget, ReportFilters filters,
            String bucket, LocalDateTime startUtc, LocalDateTime endUtc, ZoneId zone, List<Integer> riskIds) {
        return query(workspaceId, widget, filters, bucket, startUtc, endUtc, zone, riskIds, null);
    }

    private ReportAggregateQuery query(int workspaceId, ReportWidgetConfig widget, ReportFilters filters,
            String bucket, LocalDateTime startUtc, LocalDateTime endUtc, ZoneId zone, List<Integer> riskIds,
            BigDecimal fallbackWinRate) {
        ReportFilters safeFilters = filters == null ? new ReportFilters(null, null, null, null, null) : filters;
        return new ReportAggregateQuery(workspaceId, widget.measure(), normalizeGroup(widget.groupBy()), bucket,
                startUtc, endUtc,
                LocalDate.ofInstant(startUtc.toInstant(ZoneOffset.UTC), zone),
                LocalDate.ofInstant(endUtc.toInstant(ZoneOffset.UTC), zone),
                safeFilters.pipelineIds(), safeFilters.ownerIds(),
                safeFilters.statuses(), safeFilters.tagIds(), riskIds, fallbackWinRate,
                FORECAST_HISTORY_PRIOR_DEALS,
                offsetSegments(startUtc, endUtc, zone));
    }

    private static List<ReportOffsetSegment> offsetSegments(
            LocalDateTime startUtc, LocalDateTime endUtc, ZoneId zone) {
        Instant cursor = startUtc.toInstant(ZoneOffset.UTC);
        Instant end = endUtc.toInstant(ZoneOffset.UTC);
        List<ReportOffsetSegment> segments = new ArrayList<>();
        while (cursor.isBefore(end)) {
            ZoneOffset offset = zone.getRules().getOffset(cursor);
            ZoneOffsetTransition transition = zone.getRules().nextTransition(cursor);
            Instant segmentEnd = transition == null || !transition.getInstant().isBefore(end)
                    ? end
                    : transition.getInstant();
            segments.add(new ReportOffsetSegment(
                    LocalDateTime.ofInstant(cursor, ZoneOffset.UTC),
                    LocalDateTime.ofInstant(segmentEnd, ZoneOffset.UTC),
                    offset.getTotalSeconds() / 60));
            cursor = segmentEnd;
        }
        return List.copyOf(segments);
    }

    private ValidatedDefinition validate(ReportDefinitionRequest request) {
        if (request == null) {
            throw new BadRequestException("Report definition is required");
        }
        if (request.name() == null || request.name().isBlank() || request.name().trim().length() > 128) {
            throw new BadRequestException("Report name is required and must be 128 characters or fewer");
        }
        String cadence = normalized(request.cadence());
        String templateKey = blankToNull(request.templateKey());
        validateConfig(cadence, templateKey, request.config());
        String configJson = serialize(request.config(), MAX_CONFIG_BYTES, "Report configuration is too large");
        return new ValidatedDefinition(cadence, templateKey, configJson);
    }

    void validateProposal(ReportDefinitionRequest request) {
        validate(request);
    }

    static void validateConfig(String cadence, String templateKey, ReportConfig config) {
        if (!CADENCES.contains(cadence)) {
            throw new BadRequestException("Invalid report cadence: " + cadence);
        }
        if (templateKey != null && !TEMPLATE_KEYS.contains(templateKey)) {
            throw new BadRequestException("Invalid report template: " + templateKey);
        }
        if (config == null || config.widgets() == null || config.widgets().isEmpty()
                || config.widgets().size() > 16) {
            throw new BadRequestException("A report requires between 1 and 16 widgets");
        }
        String bucket = normalized(config.bucket());
        if (!BUCKETS.contains(bucket) || !bucket.equals(config.bucket())) {
            throw new BadRequestException("Invalid report bucket: " + config.bucket());
        }
        validateRange(cadence, config.range());
        validateFilters(config.filters());
        Set<String> widgetIds = new LinkedHashSet<>();
        for (ReportWidgetConfig widget : config.widgets()) {
            validateWidget(widget);
            if (!widgetIds.add(widget.id())) {
                throw new BadRequestException("Duplicate report widget id: " + widget.id());
            }
        }
        List<ReportWidgetConfig> attainmentWidgets = config.widgets().stream()
                .filter(widget -> "attainment".equals(widget.measure()))
                .toList();
        if (!attainmentWidgets.isEmpty() && !Set.of("monthly", "quarterly").contains(cadence)) {
            throw new BadRequestException("Attainment widgets require a monthly or quarterly report cadence");
        }
        if (!attainmentWidgets.isEmpty() && config.filters() != null && (
                hasValues(config.filters().pipelineIds())
                || hasValues(config.filters().statuses())
                || hasValues(config.filters().tagIds()))) {
            throw new BadRequestException("Attainment widgets do not support pipeline, status, or tag filters");
        }
        if (attainmentWidgets.stream().anyMatch(widget -> "none".equals(normalizeGroup(widget.groupBy())))
                && config.filters() != null && hasValues(config.filters().ownerIds())) {
            throw new BadRequestException("Workspace-wide attainment does not support owner filters");
        }
        if (config.layout() == null || config.layout().size() != widgetIds.size()) {
            throw new BadRequestException("Report layout must contain every widget exactly once");
        }
        Set<String> layoutIds = new HashSet<>();
        for (ReportLayoutItem item : config.layout()) {
            if (item == null || !widgetIds.contains(item.widgetId()) || !layoutIds.add(item.widgetId())
                    || item.x() < 0 || item.width() < 1 || item.x() + item.width() > 12
                    || item.y() < 0 || item.height() < 1 || item.height() > 12) {
                throw new BadRequestException("Invalid report widget layout");
            }
        }
    }

    static Set<String> supportedMeasures() {
        return SUPPORTED_MEASURES;
    }

    private static Set<String> supportedMeasureCatalog() {
        Set<String> measures = new HashSet<>(DEAL_MEASURES);
        measures.addAll(LEAD_MEASURES);
        measures.addAll(COMPANY_MEASURES);
        measures.addAll(RELATIONSHIP_MEASURES);
        measures.addAll(EMPLOYMENT_MEASURES);
        measures.addAll(DOCUMENT_MEASURES);
        measures.addAll(COUNT_MEASURES);
        return Set.copyOf(measures);
    }

    private static void validateWidget(ReportWidgetConfig widget) {
        if (widget == null || widget.id() == null || !widget.id().matches("[A-Za-z0-9_-]{1,64}")) {
            throw new BadRequestException("Invalid report widget id");
        }
        String source = normalized(widget.dataSource());
        String measure = normalized(widget.measure());
        String group = normalizeGroup(widget.groupBy());
        String chart = normalized(widget.chartType());
        String suppliedGroup = widget.groupBy() == null || widget.groupBy().isBlank()
                ? "none"
                : widget.groupBy();
        if (source == null || measure == null || chart == null
                || !source.equals(widget.dataSource()) || !measure.equals(widget.measure())
                || !group.equals(suppliedGroup) || !chart.equals(widget.chartType())) {
            throw new BadRequestException("Report widget keys must be normalized lowercase values");
        }
        if (!DATA_SOURCES.contains(source) || !CHART_TYPES.contains(chart)) {
            throw new BadRequestException("Invalid report widget configuration");
        }
        Set<String> measures = switch (source) {
            case "deals" -> DEAL_MEASURES;
            case "people" -> supportedPeopleMeasures();
            case "companies" -> COMPANY_MEASURES;
            case "relationships" -> RELATIONSHIP_MEASURES;
            case "documents" -> DOCUMENT_MEASURES;
            case "leads" -> LEAD_MEASURES;
            default -> COUNT_MEASURES;
        };
        Set<String> groups = switch (source) {
            case "deals" -> DEAL_GROUPS;
            case "activities" -> ACTIVITY_GROUPS;
            case "tasks" -> TASK_GROUPS;
            case "people" -> PEOPLE_GROUPS;
            case "companies" -> COMPANY_GROUPS;
            case "relationships" -> RELATIONSHIP_GROUPS;
            case "documents" -> DOCUMENT_GROUPS;
            case "leads" -> LEAD_GROUPS;
            default -> Set.of();
        };
        if (!measures.contains(measure) || !groups.contains(group)
                || "risk".equals(group) && !"at_risk_revenue".equals(measure)
                || "at_risk_revenue".equals(measure) && !Set.of("none", "risk").contains(group)
                || "deal".equals(group) && !Set.of(
                        "single_threaded_deal_count", "single_threaded_deal_value").contains(measure)
                || Set.of("single_threaded_deal_count", "single_threaded_deal_value").contains(measure)
                        && !Set.of("none", "company", "deal").contains(group)
                || "company".equals(group) && "companies".equals(source)
                        && !Set.of(
                                "coverage_gap_count", "coverage_gap_open_pipeline_value",
                                "warm_intro_opportunity_value").contains(measure)
                || Set.of("coverage_gap_count", "coverage_gap_open_pipeline_value").contains(measure)
                        && !Set.of("none", "company").contains(group)
                || "warm_intro_opportunity_value".equals(measure)
                        && !Set.of("none", "company", "connector").contains(group)
                || "warm_intro_reachable_account_count".equals(measure)
                        && !Set.of("none", "connector").contains(group)
                || "connector".equals(group) && !WARM_INTRO_MEASURES.contains(measure)
                || "reverse_intro_weighted_opportunities".equals(measure)
                        && !Set.of("none", "pair").contains(group)
                || "pair".equals(group) && !REVERSE_INTRO_MEASURES.contains(measure)
                || DISCOUNT_MEASURES.contains(measure) && !DISCOUNT_GROUPS.contains(group)
                || DOCUMENT_MEASURES.contains(measure) && !DOCUMENT_GROUPS.contains(group)
                || EMPLOYMENT_MEASURES.contains(measure) && !EMPLOYMENT_GROUPS.contains(group)
                || LEAD_MEASURES.contains(measure) && !"leads".equals(source)
                || "leads".equals(source) && !LEAD_MEASURES.contains(measure)
                || "people".equals(source) && "count".equals(measure)
                        && !Set.of("none", "company").contains(group)
                || FORECAST_MEASURES.contains(measure)
                        && !Set.of("none", "date", "pipeline", "stage").contains(group)
                || "attainment".equals(measure) && (
                        !Set.of("none", "owner").contains(group)
                        || !Set.of("bar", "kpi").contains(chart))) {
            throw new BadRequestException("Unsupported report measure or grouping");
        }
    }

    private static Set<String> supportedPeopleMeasures() {
        Set<String> measures = new HashSet<>(COUNT_MEASURES);
        measures.addAll(EMPLOYMENT_MEASURES);
        return Set.copyOf(measures);
    }

    private static void validateFilters(ReportFilters filters) {
        if (filters == null) {
            return;
        }
        if (filters.statuses() != null) {
            for (String status : filters.statuses()) {
                String normalized = normalized(status);
                if (!DEAL_STATUSES.contains(normalized) && !TASK_STATUSES.contains(normalized)) {
                    throw new BadRequestException("Invalid report status filter: " + status);
                }
                if (!normalized.equals(status)) {
                    throw new BadRequestException("Report status filters must be normalized lowercase values");
                }
            }
        }
        if (filters.warmthBands() != null) {
            for (String band : filters.warmthBands()) {
                if (!WARMTH_BANDS.contains(normalized(band))) {
                    throw new BadRequestException("Invalid report warmth filter: " + band);
                }
                if (!normalized(band).equals(band)) {
                    throw new BadRequestException("Report warmth filters must be normalized lowercase values");
                }
            }
        }
    }

    private static void validateRange(String cadence, ReportRange range) {
        if ("custom".equals(cadence) && (range == null || range.start() == null || range.end() == null)) {
            throw new BadRequestException("Custom reports require a start and end date");
        }
        if (range != null && (range.start() == null || range.end() == null)) {
            throw new BadRequestException("Report ranges require both start and end dates");
        }
        if (range != null) {
            validateDates(range.start(), range.end());
        }
    }

    private PeriodWindow resolvePeriod(
            String cadence, ReportRange configured, ReportGenerateRequest request, String bucket) {
        ZoneId zone = ZoneId.of(workspaceService.getCurrentAnalyticsTimezone());
        LocalDate startOverride = request == null ? null : request.start();
        LocalDate endOverride = request == null ? null : request.end();
        if ((startOverride == null) != (endOverride == null)) {
            throw new BadRequestException("Generation overrides require both start and end dates");
        }
        LocalDate start;
        LocalDate end;
        if (startOverride != null) {
            start = startOverride;
            end = endOverride;
        } else if ("custom".equals(cadence)) {
            start = configured.start();
            end = configured.end();
        } else {
            end = LocalDate.now(clock.withZone(zone));
            start = switch (cadence) {
                case "weekly" -> end.minusDays(6);
                case "monthly" -> end.withDayOfMonth(1);
                case "quarterly" -> LocalDate.of(end.getYear(), ((end.getMonthValue() - 1) / 3) * 3 + 1, 1);
                default -> throw new BadRequestException("Invalid report cadence: " + cadence);
            };
        }
        validateDates(start, end);
        LocalDate priorStart;
        LocalDate priorEnd;
        if (startOverride == null && !"custom".equals(cadence)) {
            if ("weekly".equals(cadence)) {
                priorStart = start.minusWeeks(1);
                priorEnd = end.minusWeeks(1);
            } else {
                int months = "quarterly".equals(cadence) ? 3 : 1;
                priorStart = start.minusMonths(months);
                priorEnd = end.minusMonths(months);
            }
        } else if ("month".equals(bucket)) {
            long months = ChronoUnit.MONTHS.between(
                    start.withDayOfMonth(1), end.withDayOfMonth(1)) + 1;
            priorStart = start.minusMonths(months);
            priorEnd = end.minusMonths(months);
        } else {
            long days = ChronoUnit.DAYS.between(start, end) + 1;
            priorEnd = start.minusDays(1);
            priorStart = priorEnd.minusDays(days - 1);
        }
        return new PeriodWindow(start, end, priorStart, priorEnd, zone, cadence);
    }

    private static void validateDates(LocalDate start, LocalDate end) {
        if (start == null || end == null || end.isBefore(start)) {
            throw new BadRequestException("Report end date must be on or after its start date");
        }
        if (ChronoUnit.DAYS.between(start, end) + 1 > MAX_RANGE_DAYS) {
            throw new BadRequestException("Report range cannot exceed five years");
        }
    }

    private static void validateAttainmentPeriod(ReportConfig config, PeriodWindow period) {
        if (config.widgets().stream().noneMatch(widget -> "attainment".equals(widget.measure()))) {
            return;
        }
        LocalDate expectedStart = switch (period.cadence()) {
            case "monthly" -> period.start().withDayOfMonth(1);
            case "quarterly" -> LocalDate.of(
                    period.start().getYear(),
                    ((period.start().getMonthValue() - 1) / 3) * 3 + 1,
                    1);
            default -> throw new BadRequestException("Attainment requires a monthly or quarterly report cadence");
        };
        LocalDate nextPeriod = "monthly".equals(period.cadence())
                ? expectedStart.plusMonths(1)
                : expectedStart.plusMonths(3);
        if (!period.start().equals(expectedStart) || !period.end().isBefore(nextPeriod)) {
            throw new BadRequestException("Attainment generation must stay within one calendar period");
        }
    }

    private void requireGoalReadForAttainment(ReportDocumentDto document) {
        if (document.widgets().stream().anyMatch(widget -> "attainment".equals(widget.measure()))) {
            workspaceService.requirePermission(Permission.GOAL_READ);
        }
    }

    private static boolean hasValues(List<?> values) {
        return values != null && !values.isEmpty();
    }

    private ReportDefinition requireDefinition(int id) {
        ReportDefinition definition = reportMapper.getDefinition(workspaceService.getCurrentWorkspaceId(), id);
        if (definition == null) {
            throw new ResourceNotFoundException("Report not found with id: " + id);
        }
        return definition;
    }

    private void apply(ReportDefinition definition, ReportDefinitionRequest request, ValidatedDefinition validated) {
        definition.setName(request.name().trim());
        definition.setDescription(blankToNull(request.description()));
        definition.setCadence(validated.cadence());
        definition.setTemplateKey(validated.templateKey());
        definition.setConfigJson(validated.configJson());
    }

    private ReportDefinitionDto toDefinitionDto(ReportDefinition definition) {
        return new ReportDefinitionDto(definition.getId(), definition.getName(), definition.getDescription(),
                definition.getCadence(), definition.getTemplateKey(), parseConfig(definition.getConfigJson()),
                definition.getCreatedBy(), definition.getCreatedAt(), definition.getUpdatedAt());
    }

    private ReportSnapshotDto toSnapshotDto(ReportSnapshot snapshot) {
        ReportDocumentDto document;
        try {
            document = objectMapper.readValue(snapshot.getComputedResult(), ReportDocumentDto.class);
        } catch (JacksonException exception) {
            throw new BadRequestException("Corrupt report snapshot");
        }
        return new ReportSnapshotDto(snapshot.getId(), snapshot.getReportDefinitionId(),
                snapshot.getPeriodStart(), snapshot.getPeriodEnd(), document,
                snapshot.getOrigin(), snapshot.getGeneratedBy(), snapshot.getGeneratedAt());
    }

    private ReportConfig parseConfig(String configJson) {
        try {
            return objectMapper.readValue(configJson, ReportConfig.class);
        } catch (JacksonException | IllegalArgumentException exception) {
            throw new BadRequestException("Corrupt report configuration");
        }
    }

    private String serialize(Object value, int maxBytes, String tooLargeMessage) {
        String json;
        try {
            json = objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new BadRequestException("Invalid report data");
        }
        if (json.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            throw new BadRequestException(tooLargeMessage);
        }
        return json;
    }

    private static ReportTemplateDto template(String key, String name, String description,
            String cadence, List<ReportWidgetConfig> widgets) {
        return template(key, name, description, cadence, "week", widgets);
    }

    private static ReportTemplateDto template(String key, String name, String description,
            String cadence, String bucket, List<ReportWidgetConfig> widgets) {
        List<ReportLayoutItem> layout = new ArrayList<>();
        for (int index = 0; index < widgets.size(); index++) {
            layout.add(new ReportLayoutItem(widgets.get(index).id(), index % 2 * 6, index / 2 * 4, 6, 4));
        }
        return new ReportTemplateDto(key, name, description, cadence,
                new ReportConfig(widgets, new ReportFilters(null, null, null, null, null), null,
                        bucket, List.copyOf(layout)));
    }

    private static ReportWidgetConfig widget(String id, String title, String source,
            String measure, String group, String chart) {
        return new ReportWidgetConfig(id, title, source, measure, group, chart);
    }

    private static String appendixCsv(ReportDocumentDto document) {
        StringBuilder csv = new StringBuilder();
        csv.append("source_id,widget_id,label,current_value,prior_value,unit\r\n");
        for (ReportAppendixRowDto row : document.appendix()) {
            csv.append(cell(row.sourceId())).append(',')
                    .append(cell(row.widgetId())).append(',')
                    .append(cell(row.label())).append(',')
                    .append(cell(decimal(row.value()))).append(',')
                    .append(cell(decimal(row.priorValue()))).append(',')
                    .append(cell(row.unit())).append("\r\n");
        }
        return csv.toString();
    }

    private static String cell(String value) {
        String safe = value == null ? "" : value;
        if (!safe.isEmpty() && (Character.isWhitespace(safe.charAt(0))
                || "=+-@\t\r\n".indexOf(safe.charAt(0)) >= 0)) {
            safe = "'" + safe;
        }
        return '"' + safe.replace("\"", "\"\"") + '"';
    }

    private static String decimal(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }

    private static List<ReportCitationDto> citations(
            ReportNarrativeDto narrative, List<ReportAppendixRowDto> appendix) {
        if (narrative == null || !narrative.available()) {
            return List.of();
        }
        Map<String, ReportAppendixRowDto> sources = new LinkedHashMap<>();
        appendix.forEach(row -> sources.put(row.sourceId(), row));
        LinkedHashSet<String> cited = new LinkedHashSet<>();
        narrative.sections().forEach(section -> section.claims().forEach(claim -> cited.addAll(claim.sourceIds())));
        narrative.findings().forEach(claim -> cited.addAll(claim.sourceIds()));
        List<ReportCitationDto> result = new ArrayList<>();
        for (String sourceId : cited) {
            ReportAppendixRowDto source = sources.get(sourceId);
            if (source != null) {
                result.add(new ReportCitationDto(source.sourceId(), source.widgetId(), source.label(),
                        source.value(), source.priorValue(), source.unit()));
            }
        }
        return List.copyOf(result);
    }

    private static String displayTitle(ReportWidgetConfig widget) {
        return widget.title() == null || widget.title().isBlank() ? title(widget.measure()) : widget.title().trim();
    }

    private static String title(String value) {
        if (value == null || value.isBlank()) {
            return "Total";
        }
        String normalized = value.replace('_', ' ').replace('-', ' ');
        return normalized.substring(0, 1).toUpperCase(Locale.ROOT) + normalized.substring(1);
    }

    private static BigDecimal percentChange(BigDecimal current, BigDecimal prior) {
        if (current == null || prior == null || prior.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        return current.subtract(prior).multiply(BigDecimal.valueOf(100))
                .divide(prior.abs(), 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal attainmentPercent(BigDecimal actual, BigDecimal target) {
        if (actual == null || target == null || target.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        return actual.multiply(BigDecimal.valueOf(100)).divide(target, 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static String rowKey(ReportAggregateRow row) {
        return row.groupKey() + '\u0000' + row.unit();
    }

    private static String comparisonKey(
            ReportAggregateRow row, ReportWidgetConfig widget, String bucket, LocalDate periodStart) {
        if (!"date".equals(normalizeGroup(widget.groupBy()))) {
            return rowKey(row);
        }
        LocalDate bucketDate = parseBucketDate(row.groupKey(), bucket);
        LocalDate periodAnchor = bucketAnchor(periodStart, bucket);
        long position = switch (bucket) {
            case "day" -> ChronoUnit.DAYS.between(periodAnchor, bucketDate);
            case "week" -> ChronoUnit.WEEKS.between(periodAnchor, bucketDate);
            case "month" -> ChronoUnit.MONTHS.between(periodAnchor, bucketDate);
            default -> throw new BadRequestException("Invalid report bucket: " + bucket);
        };
        return position + "\u0000" + dateGroupPartition(row.groupKey())
                + "\u0000" + unitKey(row.unit());
    }

    private static String dateGroupPartition(String groupKey) {
        int separator = groupKey.lastIndexOf(':');
        return separator >= 0 ? groupKey.substring(0, separator) : "";
    }

    private static ReportAggregateRow alignPriorDateRow(
            ReportAggregateRow row,
            ReportWidgetConfig widget,
            String bucket,
            LocalDate priorStart,
            LocalDate currentStart) {
        if (!"date".equals(normalizeGroup(widget.groupBy()))) {
            return row;
        }
        LocalDate priorDate = parseBucketDate(row.groupKey(), bucket);
        LocalDate priorAnchor = bucketAnchor(priorStart, bucket);
        LocalDate currentAnchor = bucketAnchor(currentStart, bucket);
        long position = switch (bucket) {
            case "day" -> ChronoUnit.DAYS.between(priorAnchor, priorDate);
            case "week" -> ChronoUnit.WEEKS.between(priorAnchor, priorDate);
            case "month" -> ChronoUnit.MONTHS.between(priorAnchor, priorDate);
            default -> throw new BadRequestException("Invalid report bucket: " + bucket);
        };
        LocalDate alignedDate = switch (bucket) {
            case "day" -> currentAnchor.plusDays(position);
            case "week" -> currentAnchor.plusWeeks(position);
            case "month" -> currentAnchor.plusMonths(position);
            default -> throw new BadRequestException("Invalid report bucket: " + bucket);
        };
        String priorToken = bucketToken(priorDate, bucket);
        String alignedToken = bucketToken(alignedDate, bucket);
        return new ReportAggregateRow(
                row.groupKey().replace(priorToken, alignedToken),
                row.groupLabel().replace(priorToken, alignedToken),
                row.unit(),
                row.value());
    }

    private static LocalDate parseBucketDate(String groupKey, String bucket) {
        int separator = groupKey.lastIndexOf(':');
        String token = separator >= 0 ? groupKey.substring(separator + 1) : groupKey;
        try {
            return LocalDate.parse("month".equals(bucket) ? token + "-01" : token);
        } catch (DateTimeException exception) {
            throw new BadRequestException("Invalid date bucket returned by report aggregation");
        }
    }

    private static LocalDate bucketAnchor(LocalDate date, String bucket) {
        return switch (bucket) {
            case "day" -> date;
            case "week" -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case "month" -> date.withDayOfMonth(1);
            default -> throw new BadRequestException("Invalid report bucket: " + bucket);
        };
    }

    private static String bucketToken(LocalDate date, String bucket) {
        return "month".equals(bucket) ? date.toString().substring(0, 7) : date.toString();
    }

    private static String normalized(String value) {
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizedUnit(String value) {
        if (value == null || value.isBlank()) {
            return "count";
        }
        String unit = value.strip();
        if (Set.of("count", "percent", "days", "mixed", "opportunities").contains(unit)) {
            return unit;
        }
        return unit.matches("[A-Za-z]{3,8}") ? unit.toUpperCase(Locale.ROOT) : "currency";
    }

    private static String unitKey(String value) {
        String unit = normalizedUnit(value);
        return "currency".equals(unit) ? value.strip() : unit;
    }

    private static String normalizeGroup(String value) {
        String normalized = normalized(value);
        return normalized == null || normalized.isBlank() ? "none" : normalized;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record ValidatedDefinition(String cadence, String templateKey, String configJson) {
    }

    private record WidgetSelection(ReportWidgetConfig widget, int index) {
    }

    private record RiskRowKey(String currency, String level) {
    }

    private record WidgetResult(
            ReportWidgetDataDto widget,
            List<ReportAppendixRowDto> appendix,
            String unavailabilityReason) {
    }

    private record GenerationInputs(
            List<RelationshipTemperatureDto> currentRelationships,
            List<RelationshipTemperatureDto> priorRelationships,
            List<RelationshipTemperatureDto> currentCompanyRelationships,
            List<RelationshipTemperatureDto> priorCompanyRelationships,
            List<DealRiskDto> risks,
            List<ReportNetworkService.WarmIntroOpportunity> warmIntroOpportunities,
            List<IntroSuggestionDto> reverseIntroSuggestions,
            Map<String, String> ownerLabels,
            LocalDate forecastStart,
            Map<String, List<ReportForecastAggregateRow>> forecastRows,
            Map<String, List<ReportAggregateRow>> currentRows,
            Map<String, List<ReportAggregateRow>> priorRows) {
    }

    private record GeneratedFigures(
            List<ReportWidgetDataDto> widgets,
            List<ReportAppendixRowDto> appendix) {
    }

    private record PreparedReport(
            ReportDefinitionDto definition,
            String definitionName,
            PeriodWindow period,
            GeneratedFigures figures,
            long restrictionEpoch,
            String generatedAt) {
    }

    private record ReportGenerationIdentity(
            int reportId,
            ReportDefinitionDto definition,
            LocalDate periodStart,
            LocalDate periodEnd,
            GeneratedFigures figures,
            long restrictionEpoch) {
    }

    private record RowPair(List<ReportAggregateRow> current, List<ReportAggregateRow> prior) {
    }

    private record PeriodWindow(
            LocalDate start,
            LocalDate end,
            LocalDate priorStart,
            LocalDate priorEnd,
            ZoneId zone,
            String cadence) {

        LocalDateTime currentStartUtc() {
            return utc(start);
        }

        LocalDateTime currentEndUtc() {
            return utc(end.plusDays(1));
        }

        LocalDateTime priorStartUtc() {
            return utc(priorStart);
        }

        LocalDateTime priorEndUtc() {
            return utc(priorEnd.plusDays(1));
        }

        Instant currentEndInstant() {
            return end.plusDays(1).atStartOfDay(zone).toInstant().minusNanos(1);
        }

        Instant priorEndInstant() {
            return priorEnd.plusDays(1).atStartOfDay(zone).toInstant().minusNanos(1);
        }

        private LocalDateTime utc(LocalDate date) {
            return LocalDateTime.ofInstant(date.atStartOfDay(zone).toInstant(), ZoneOffset.UTC);
        }
    }
}
