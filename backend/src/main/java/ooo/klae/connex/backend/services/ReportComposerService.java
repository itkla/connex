package ooo.klae.connex.backend.services;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;

import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.AiFeature;
import ooo.klae.connex.backend.ai.AiFeatureGate;
import ooo.klae.connex.backend.ai.AiGenerationProfile;
import ooo.klae.connex.backend.ai.AiInvocation;
import ooo.klae.connex.backend.ai.AiInvocationAdmissionService;
import ooo.klae.connex.backend.ai.AiInvocationAdmissionService.Admission;
import ooo.klae.connex.backend.ai.AiInvocationAdmissionService.CacheIdentity;
import ooo.klae.connex.backend.ai.AiInvocationAdmissionService.Decision;
import ooo.klae.connex.backend.ai.AiInvocationAdmissionService.LeaderOutcome;
import ooo.klae.connex.backend.ai.AiInvocationService;
import ooo.klae.connex.backend.ai.AiOutputCacheStore;
import ooo.klae.connex.backend.ai.AiRawOutputGuard;
import ooo.klae.connex.backend.ai.AiRestrictionEpoch;
import ooo.klae.connex.backend.ai.AiStructuredOutcome;
import ooo.klae.connex.backend.ai.report.AiReportComposerAssembler;
import ooo.klae.connex.backend.ai.report.AiReportComposerAssembly;
import ooo.klae.connex.backend.ai.report.AiReportComposerContent;
import ooo.klae.connex.backend.beans.AiOutputCache;
import ooo.klae.connex.backend.dto.ReportComposerAvailabilityDto;
import ooo.klae.connex.backend.dto.ReportComposerEvidenceDto;
import ooo.klae.connex.backend.dto.ReportComposerPreviewDto;
import ooo.klae.connex.backend.dto.ReportComposerRequest;
import ooo.klae.connex.backend.dto.ReportConfig;
import ooo.klae.connex.backend.dto.ReportDefinitionRequest;
import ooo.klae.connex.backend.dto.ReportFilters;
import ooo.klae.connex.backend.dto.ReportRange;
import ooo.klae.connex.backend.dto.ReportWidgetConfig;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;

/**
 * Produces validated report definitions without exposing CRM records or computing report figures.
 */
@Service
@RequiredArgsConstructor
public class ReportComposerService {
    static final int MAX_TOKENS = 4096;
    static final double TEMPERATURE = 0.1;

    private static final Set<String> ASSUMPTION_CODES = Set.of(
            "current_workspace",
            "accessible_records",
            "current_owners",
            "server_computed_figures",
            "date_range_inferred");
    private static final Set<String> REQUIRED_ASSUMPTIONS = Set.of(
            "current_workspace",
            "accessible_records",
            "server_computed_figures");
    private static final Set<String> QUOTA_MEASURES = Set.of("attainment", "won_revenue");
    private static final Set<String> FORECAST_MEASURES = Set.of(
            "forecast_best", "forecast_weighted", "forecast_worst");
    private static final Set<String> NETWORK_MEASURES = Set.of(
            "warm_intro_opportunity_value",
            "warm_intro_reachable_account_count",
            "reverse_intro_weighted_opportunities");
    private static final Set<String> EMPLOYMENT_MEASURES = Set.of(
            "employment_departure_count", "employment_arrival_count");
    private static final Set<String> RELATIONSHIP_HEALTH_MEASURES = Set.of(
            "company_count",
            "coverage_gap_count",
            "coverage_gap_open_pipeline_value",
            "single_threaded_deal_count",
            "single_threaded_deal_value");
    private static final Set<String> PIPELINE_MEASURES = Set.of(
            "open_pipeline_value", "open_deal_count", "at_risk_revenue");
    private static final Set<String> SALES_MEASURES = Set.of(
            "count", "new_pipeline_value", "won_revenue", "win_rate", "avg_cycle_days");

    private final AiReportComposerAssembler assembler;
    private final AiInvocationService invocationService;
    private final AiInvocationAdmissionService admissionService;
    private final AiFeatureGate featureGate;
    private final AiOutputCacheStore cacheStore;
    private final AiRestrictionEpoch restrictionEpoch;
    private final ReportService reportService;
    private final WorkspaceService workspaceService;
    private final Validator validator;
    private final Clock clock;

    /** Returns fail-closed composer availability for the current actor and organization. */
    @RequirePermission(Permission.REPORT_CREATE)
    public ReportComposerAvailabilityDto availability() {
        boolean available = featureGate.isAiUsable(AiFeature.REPORT_COMPOSER);
        return new ReportComposerAvailabilityDto(available, available ? null : "not_configured");
    }

    /**
     * Converts a masked request into a validated, unsaved report definition.
     * @param request bounded natural-language request
     * @return preview or a stable unavailable result
     */
    @RequirePermission(Permission.REPORT_CREATE)
    public ReportComposerPreviewDto preview(ReportComposerRequest request) {
        try {
            Optional<AiGenerationProfile> profile = featureGate.generationProfileIfUsable(
                    AiFeature.REPORT_COMPOSER, MAX_TOKENS, TEMPERATURE);
            if (profile.isEmpty()) {
                return ReportComposerPreviewDto.unavailable("not_configured");
            }
            return previewWithProfile(request, profile.get());
        } catch (ForbiddenException exception) {
            return ReportComposerPreviewDto.unavailable("not_configured");
        } catch (RuntimeException exception) {
            return ReportComposerPreviewDto.unavailable("provider_error");
        }
    }

    private ReportComposerPreviewDto previewWithProfile(
            ReportComposerRequest request, AiGenerationProfile profile) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int actorId = workspaceService.getCurrentUserId();
        ZoneId workspaceZone = ZoneId.of(workspaceService.getCurrentAnalyticsTimezone());
        LocalDate today = LocalDate.now(clock.withZone(workspaceZone));
        Optional<AiReportComposerAssembly> assembled = assembler.assemble(request.prompt(), today);
        if (assembled.isEmpty()) {
            return ReportComposerPreviewDto.unavailable("input_restricted");
        }

        AiReportComposerAssembly assembly = assembled.get();
        String contentHash = cacheStore.contentHash(profile, assembly.prompt(), assembly.context());
        String cacheFeature = cacheFeature();
        ReportComposerPreviewDto cached = cached(workspaceId, actorId, cacheFeature, contentHash, today);
        if (cached != null) {
            return cached;
        }

        CacheIdentity identity = CacheIdentity.forSubject(
                workspaceId, AiFeature.REPORT_COMPOSER, actorId, LocaleContextHolder.getLocale());
        while (true) {
            Admission admission;
            try {
                admission = admissionService.acquire(identity, contentHash, false);
            } catch (RuntimeException exception) {
                return ReportComposerPreviewDto.unavailable("provider_error");
            }
            try (admission) {
                if (admission.decision() == Decision.RATE_LIMITED) {
                    return ReportComposerPreviewDto.unavailable("rate_limited");
                }
                if (admission.decision() == Decision.FOLLOWER) {
                    if (admission.awaitLeader() == LeaderOutcome.FAILED) {
                        continue;
                    }
                    ReportComposerPreviewDto joined = cached(
                            workspaceId, actorId, cacheFeature, contentHash, today);
                    return joined != null
                            ? joined
                            : ReportComposerPreviewDto.unavailable("provider_error");
                }
                try {
                    AiStructuredOutcome<AiReportComposerContent> outcome = invocationService.completeStructured(
                            new AiInvocation(
                                    AiFeature.REPORT_COMPOSER,
                                    assembly.context(),
                                    assembly.prompt(),
                                    MAX_TOKENS,
                                    TEMPERATURE),
                            AiReportComposerContent.class,
                            AiRawOutputGuard.PERMIT_ALL,
                            admission);
                    if (!(outcome instanceof AiStructuredOutcome.Parsed<AiReportComposerContent> parsed)) {
                        return ReportComposerPreviewDto.unavailable("provider_error");
                    }
                    ReportComposerPreviewDto preview = validatedPreview(
                            parsed.value(), Instant.now(clock).toString(), today);
                    long epoch = restrictionEpoch.current(workspaceId);
                    boolean saved = cacheStore.save(
                            workspaceId,
                            cacheFeature,
                            actorId,
                            AiOutputCacheStore.NO_SUBJECT,
                            contentHash,
                            parsed.value(),
                            parsed.demaskWarnings(),
                            preview.generatedAt(),
                            epoch);
                    if (!saved) {
                        admission.completeLeader(LeaderOutcome.FAILED);
                        return ReportComposerPreviewDto.unavailable("provider_error");
                    }
                    admission.completeLeader(LeaderOutcome.CACHE_READY);
                    return preview;
                } catch (BadRequestException | IllegalArgumentException exception) {
                    return ReportComposerPreviewDto.unavailable("invalid_definition");
                } catch (ForbiddenException exception) {
                    return ReportComposerPreviewDto.unavailable("not_configured");
                } catch (RuntimeException exception) {
                    return ReportComposerPreviewDto.unavailable("provider_error");
                }
            }
        }
    }

    private ReportComposerPreviewDto cached(
            int workspaceId,
            int actorId,
            String cacheFeature,
            String contentHash,
            LocalDate today) {
        Optional<AiOutputCache> row = cacheStore.find(
                workspaceId, cacheFeature, actorId, AiOutputCacheStore.NO_SUBJECT);
        if (row.isEmpty() || !contentHash.equals(row.get().getContentHash())) {
            return null;
        }
        Optional<AiReportComposerContent> content = cacheStore.read(
                row.get().getPayload(), AiReportComposerContent.class);
        if (content.isEmpty()) {
            return null;
        }
        try {
            return validatedPreview(content.get(), row.get().getGeneratedAt(), today);
        } catch (BadRequestException | IllegalArgumentException exception) {
            cacheStore.deleteIfContentHashMatches(
                    workspaceId, cacheFeature, actorId, AiOutputCacheStore.NO_SUBJECT, contentHash);
            return null;
        }
    }

    private ReportComposerPreviewDto validatedPreview(
            AiReportComposerContent content,
            String generatedAt,
            LocalDate today) {
        if (content == null || content.assumptionCodes() == null) {
            throw new IllegalArgumentException("Composer output is incomplete");
        }
        List<String> assumptions = List.copyOf(new LinkedHashSet<>(content.assumptionCodes()));
        if (assumptions.size() != content.assumptionCodes().size()
                || !ASSUMPTION_CODES.containsAll(assumptions)
                || !assumptions.containsAll(REQUIRED_ASSUMPTIONS)) {
            throw new IllegalArgumentException("Composer assumptions are invalid");
        }
        if (content.config() == null || content.config().widgets() == null
                || content.config().widgets().isEmpty()) {
            throw new IllegalArgumentException("Composer definition is incomplete");
        }
        List<ReportWidgetConfig> widgets = content.config().widgets().stream()
                .map(widget -> new ReportWidgetConfig(
                        widget.id(),
                        null,
                        widget.dataSource(),
                        widget.measure(),
                        widget.groupBy(),
                        widget.chartType()))
                .toList();
        ReportFilters proposedFilters = content.config().filters();
        ReportFilters filters = proposedFilters == null
                ? null
                : new ReportFilters(
                        null,
                        null,
                        proposedFilters.statuses(),
                        null,
                        proposedFilters.warmthBands());
        ReportConfig config = new ReportConfig(
                widgets,
                filters,
                content.config().range(),
                content.config().bucket(),
                content.config().layout());
        String cadence = content.cadence() == null
                ? null
                : content.cadence().strip().toLowerCase(Locale.ROOT);
        String templateKey = templateKeyFor(widgets);
        String nameKey = templateKey == null ? widgets.getFirst().measure() : templateKey;
        ReportDefinitionRequest definition = new ReportDefinitionRequest(
                "Report: " + nameKey.replace('_', ' '),
                null,
                cadence,
                templateKey,
                config);
        if (!validator.validate(definition).isEmpty()) {
            throw new IllegalArgumentException("Composer definition violates report constraints");
        }
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int actorId = workspaceService.getCurrentUserId();
        boolean usesAttainment = widgets.stream()
                .anyMatch(widget -> "attainment".equals(widget.measure()));
        if (usesAttainment
                && !workspaceService.permissionsFor(workspaceId, actorId).contains(Permission.GOAL_READ)) {
            throw new BadRequestException("Quota attainment is unavailable to the current actor");
        }
        reportService.validateProposal(definition);
        List<ReportComposerEvidenceDto> evidence = definition.config().widgets().stream()
                .map(ReportComposerService::evidence)
                .toList();
        return new ReportComposerPreviewDto(
                true, null, definition, assumptions, evidence, effectiveRange(definition, today), generatedAt);
    }

    private static ReportComposerEvidenceDto evidence(ReportWidgetConfig widget) {
        String group = widget.groupBy() == null || widget.groupBy().isBlank() ? "none" : widget.groupBy();
        return new ReportComposerEvidenceDto(
                widget.id(), widget.dataSource(), widget.measure(), group, widget.chartType());
    }

    private static String templateKeyFor(List<ReportWidgetConfig> widgets) {
        Set<String> measures = widgets.stream().map(ReportWidgetConfig::measure).collect(Collectors.toSet());
        if (allDataSource(widgets, "deals")
                && measures.contains("attainment") && QUOTA_MEASURES.containsAll(measures)) {
            return "quota-attainment";
        }
        if (allDataSource(widgets, "deals")
                && !measures.isEmpty() && FORECAST_MEASURES.containsAll(measures)) {
            return "forecasting";
        }
        if (!measures.isEmpty() && NETWORK_MEASURES.containsAll(measures)) {
            return "network-warm-intros";
        }
        if (allDataSource(widgets, "people")
                && !measures.isEmpty() && EMPLOYMENT_MEASURES.containsAll(measures)) {
            return "employment-moves";
        }
        if (measures.stream().anyMatch(RELATIONSHIP_HEALTH_MEASURES::contains)) {
            return "relationship-health";
        }
        if (allDataSource(widgets, "deals")
                && !measures.isEmpty() && PIPELINE_MEASURES.containsAll(measures)) {
            return "pipeline-health";
        }
        if (allDataSource(widgets, "deals")
                && !measures.isEmpty() && SALES_MEASURES.containsAll(measures)) {
            return "sales-performance";
        }
        if (widgets.stream().allMatch(widget -> Set.of("activities", "tasks").contains(widget.dataSource()))) {
            return "activity-team";
        }
        return null;
    }

    private static boolean allDataSource(List<ReportWidgetConfig> widgets, String dataSource) {
        return widgets.stream().allMatch(widget -> dataSource.equals(widget.dataSource()));
    }

    private static ReportRange effectiveRange(ReportDefinitionRequest definition, LocalDate today) {
        if (definition.config().range() != null) {
            return definition.config().range();
        }
        LocalDate end = today;
        LocalDate start = switch (definition.cadence()) {
            case "weekly" -> end.minusDays(6);
            case "monthly" -> end.withDayOfMonth(1);
            case "quarterly" -> LocalDate.of(
                    end.getYear(), ((end.getMonthValue() - 1) / 3) * 3 + 1, 1);
            default -> throw new BadRequestException("Invalid report cadence: " + definition.cadence());
        };
        return new ReportRange(start, end);
    }

    private static String cacheFeature() {
        Locale locale = LocaleContextHolder.getLocale();
        String language = locale.getLanguage();
        return AiFeature.REPORT_COMPOSER.wireKey()
                + ":v1:" + (language.isBlank() ? Locale.ENGLISH.getLanguage() : language);
    }
}
