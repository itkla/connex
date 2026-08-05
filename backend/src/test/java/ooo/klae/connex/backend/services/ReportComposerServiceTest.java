package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import ooo.klae.connex.backend.ai.AiFeature;
import ooo.klae.connex.backend.ai.AiFeatureGate;
import ooo.klae.connex.backend.ai.AiGenerationProfile;
import ooo.klae.connex.backend.ai.AiInvocation;
import ooo.klae.connex.backend.ai.AiInvocationAdmissionService;
import ooo.klae.connex.backend.ai.AiInvocationAdmissionService.Admission;
import ooo.klae.connex.backend.ai.AiInvocationAdmissionService.Decision;
import ooo.klae.connex.backend.ai.AiInvocationAdmissionService.LeaderOutcome;
import ooo.klae.connex.backend.ai.AiInvocationService;
import ooo.klae.connex.backend.ai.AiOutputCacheStore;
import ooo.klae.connex.backend.ai.AiRawOutputGuard;
import ooo.klae.connex.backend.ai.AiRestrictionEpoch;
import ooo.klae.connex.backend.ai.AiStructuredOutcome;
import ooo.klae.connex.backend.ai.report.AiReportComposerAssembler;
import ooo.klae.connex.backend.ai.report.AiReportComposerContent;
import ooo.klae.connex.backend.dto.ReportComposerPreviewDto;
import ooo.klae.connex.backend.dto.ReportComposerRequest;
import ooo.klae.connex.backend.dto.ReportConfig;
import ooo.klae.connex.backend.dto.ReportFilters;
import ooo.klae.connex.backend.dto.ReportLayoutItem;
import ooo.klae.connex.backend.dto.ReportWidgetConfig;

class ReportComposerServiceTest {
    private final AiReportComposerAssembler assembler = mock(AiReportComposerAssembler.class);
    private final AiInvocationService invocationService = mock(AiInvocationService.class);
    private final AiInvocationAdmissionService admissionService = mock(AiInvocationAdmissionService.class);
    private final AiFeatureGate featureGate = mock(AiFeatureGate.class);
    private final AiOutputCacheStore cacheStore = mock(AiOutputCacheStore.class);
    private final AiRestrictionEpoch restrictionEpoch = new AiRestrictionEpoch();
    private final ReportService reportService = mock(ReportService.class);
    private final WorkspaceService workspaceService = mock(WorkspaceService.class);
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
    private final Clock clock = Clock.fixed(Instant.parse("2026-08-05T06:00:00Z"), ZoneOffset.UTC);
    private final ReportComposerService service = new ReportComposerService(
            assembler,
            invocationService,
            admissionService,
            featureGate,
            cacheStore,
            restrictionEpoch,
            reportService,
            workspaceService,
            validator,
            clock);
    private final AiGenerationProfile profile = new AiGenerationProfile(
            "bedrock", "us-east-1", "anthropic.claude-3-sonnet-v1:0",
            null, null, null, null,
            ReportComposerService.MAX_TOKENS, ReportComposerService.TEMPERATURE);

    @Test
    void preview_returnsUnavailableWithoutAssemblingWhenGateClosed() {
        when(featureGate.generationProfileIfUsable(
                AiFeature.REPORT_COMPOSER,
                ReportComposerService.MAX_TOKENS,
                ReportComposerService.TEMPERATURE)).thenReturn(Optional.empty());

        ReportComposerPreviewDto result = service.preview(new ReportComposerRequest("Pipeline health"));

        assertFalse(result.available());
        assertEquals("not_configured", result.reason());
        verifyNoInteractions(assembler, invocationService, admissionService, cacheStore);
    }

    @Test
    void preview_returnsServerValidatedDefinitionWithoutFigures() {
        Admission admission = readyAdmission();
        AiReportComposerContent content = validContent();
        when(invocationService.completeStructured(
                any(AiInvocation.class),
                eq(AiReportComposerContent.class),
                any(AiRawOutputGuard.class),
                eq(admission))).thenReturn(new AiStructuredOutcome.Parsed<>(content, 0, 100, 50, "end_turn"));
        when(cacheStore.save(
                anyInt(), anyString(), anyInt(), anyInt(), anyString(), any(),
                anyInt(), anyString(), anyLong())).thenReturn(true);

        ReportComposerPreviewDto result = service.preview(new ReportComposerRequest("Pipeline health"));

        assertTrue(result.available());
        assertEquals("Report: pipeline-health", result.definition().name());
        assertEquals("monthly", result.definition().cadence());
        assertEquals("pipeline-health", result.definition().templateKey());
        assertNull(result.definition().config().filters().pipelineIds());
        assertNull(result.definition().config().filters().ownerIds());
        assertNull(result.definition().config().filters().tagIds());
        assertEquals("open_pipeline_value", result.evidence().getFirst().measure());
        assertEquals("2026-08-01", result.effectiveRange().start().toString());
        assertEquals("2026-08-05", result.effectiveRange().end().toString());
        assertEquals("2026-08-05T06:00:00Z", result.generatedAt());
        verify(reportService).validateProposal(result.definition());
        verify(admission).completeLeader(LeaderOutcome.CACHE_READY);
    }

    @Test
    void preview_rejectsUnclosedAssumptionVocabularyBeforePersistence() {
        Admission admission = readyAdmission();
        AiReportComposerContent content = new AiReportComposerContent(
                "monthly",
                validContent().config(),
                List.of("current_workspace", "accessible_records", "server_computed_figures", "invented_fact"));
        when(invocationService.completeStructured(
                any(AiInvocation.class),
                eq(AiReportComposerContent.class),
                any(AiRawOutputGuard.class),
                eq(admission))).thenReturn(new AiStructuredOutcome.Parsed<>(content, 0, 100, 50, "end_turn"));

        ReportComposerPreviewDto result = service.preview(new ReportComposerRequest("Pipeline health"));

        assertFalse(result.available());
        assertEquals("invalid_definition", result.reason());
        verify(cacheStore, never()).save(
                anyInt(), anyString(), anyInt(), anyInt(), anyString(), any(),
                anyInt(), anyString(), anyLong());
    }

    @Test
    void preview_rejectsAttainmentWithoutGoalReadPermission() {
        Admission admission = readyAdmission();
        ReportWidgetConfig widget = new ReportWidgetConfig(
                "attainment", null, "deals", "attainment", "owner", "bar");
        ReportConfig config = new ReportConfig(
                List.of(widget),
                new ReportFilters(null, null, null, null, null),
                null,
                "month",
                List.of(new ReportLayoutItem("attainment", 0, 0, 12, 4)));
        AiReportComposerContent content = new AiReportComposerContent(
                "monthly",
                config,
                List.of("current_workspace", "accessible_records", "server_computed_figures"));
        when(workspaceService.permissionsFor(11, 7)).thenReturn(Set.of());
        when(invocationService.completeStructured(
                any(AiInvocation.class),
                eq(AiReportComposerContent.class),
                any(AiRawOutputGuard.class),
                eq(admission))).thenReturn(new AiStructuredOutcome.Parsed<>(content, 0, 100, 50, "end_turn"));

        ReportComposerPreviewDto result = service.preview(new ReportComposerRequest("Quota attainment"));

        assertFalse(result.available());
        assertEquals("invalid_definition", result.reason());
        verify(reportService, never()).validateProposal(any());
    }

    private Admission readyAdmission() {
        Admission admission = mock(Admission.class);
        when(featureGate.generationProfileIfUsable(
                AiFeature.REPORT_COMPOSER,
                ReportComposerService.MAX_TOKENS,
                ReportComposerService.TEMPERATURE)).thenReturn(Optional.of(profile));
        when(assembler.assemble(anyString(), any(LocalDate.class))).thenReturn(Optional.of(
                new AiReportComposerAssembler()
                        .assemble("Pipeline health", LocalDate.of(2026, 8, 5))
                        .orElseThrow()));
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(11);
        when(workspaceService.getCurrentUserId()).thenReturn(7);
        when(workspaceService.getCurrentAnalyticsTimezone()).thenReturn("UTC");
        when(cacheStore.contentHash(any(), any(), any())).thenReturn("hash");
        when(cacheStore.find(anyInt(), anyString(), anyInt(), anyInt())).thenReturn(Optional.empty());
        when(admissionService.acquire(any(), eq("hash"), eq(false))).thenReturn(admission);
        when(admission.decision()).thenReturn(Decision.LEADER);
        return admission;
    }

    private static AiReportComposerContent validContent() {
        ReportWidgetConfig widget = new ReportWidgetConfig(
                "pipeline", "Open pipeline", "deals", "open_pipeline_value", "stage", "bar");
        ReportConfig config = new ReportConfig(
                List.of(widget),
                new ReportFilters(List.of(41), List.of(42), List.of("open"), List.of(43), null),
                null,
                "month",
                List.of(new ReportLayoutItem("pipeline", 0, 0, 12, 4)));
        return new AiReportComposerContent(
                " Monthly ",
                config,
                List.of("current_workspace", "accessible_records", "server_computed_figures"));
    }
}
