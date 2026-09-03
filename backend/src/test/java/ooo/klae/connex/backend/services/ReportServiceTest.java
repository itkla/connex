package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import ooo.klae.connex.backend.ai.AiGenerationService;
import ooo.klae.connex.backend.ai.AiRestrictionEpoch;
import ooo.klae.connex.backend.ai.report.AiReportNarrativeService;
import ooo.klae.connex.backend.beans.ReportDefinition;
import ooo.klae.connex.backend.dto.DealRiskDto;
import ooo.klae.connex.backend.dto.ReportAggregateQuery;
import ooo.klae.connex.backend.dto.ReportAggregateRow;
import ooo.klae.connex.backend.dto.ReportKpiDto;
import ooo.klae.connex.backend.dto.ReportNarrativeClaimDto;
import ooo.klae.connex.backend.dto.ReportNarrativeDto;
import ooo.klae.connex.backend.dto.ReportNarrativeSectionDto;
import ooo.klae.connex.backend.dto.ReportWidgetConfig;
import ooo.klae.connex.backend.mappers.GoalMapper;
import ooo.klae.connex.backend.mappers.ReportMapper;
import ooo.klae.connex.backend.mappers.ScheduleMapper;
import ooo.klae.connex.backend.tenant.Permission;
import tools.jackson.databind.ObjectMapper;

class ReportServiceTest {
    private static final int WORKSPACE_ID = 7;
    private static final int REPORT_ID = 11;
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-07-12T12:00:00Z"), ZoneOffset.UTC);

    private final ReportMapper reportMapper = mock(ReportMapper.class);
    private final WorkspaceService workspaceService = mock(WorkspaceService.class);
    private final DealRiskService dealRiskService = mock(DealRiskService.class);
    private final AiReportNarrativeService aiReportNarrativeService = mock(AiReportNarrativeService.class);
    private final AiRestrictionEpoch aiRestrictionEpoch = mock(AiRestrictionEpoch.class);
    private final ReportPermissionPolicy reportPermissionPolicy = mock(ReportPermissionPolicy.class);
    private ReportService service;

    @BeforeEach
    void setUp() {
        service = new ReportService(
                reportMapper,
                mock(ScheduleMapper.class),
                mock(GoalMapper.class),
                workspaceService,
                mock(AuthService.class),
                mock(ScoringService.class),
                dealRiskService,
                mock(ReportNetworkService.class),
                aiReportNarrativeService,
                mock(AiGenerationService.class),
                aiRestrictionEpoch,
                reportPermissionPolicy,
                mock(AuditService.class),
                mock(DeletionPolicy.class),
                new ObjectMapper(),
                CLOCK,
                mock(TransactionTemplate.class));
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(WORKSPACE_ID);
        when(workspaceService.getCurrentAnalyticsTimezone()).thenReturn("UTC");
        when(reportPermissionPolicy.requiredFor(any(ReportDefinition.class)))
                .thenReturn(Set.of(Permission.REPORT_READ));
    }

    @Test
    void widgetKpiReturnsUnavailableWithoutCallingUnboundedRiskAssessmentWhenCandidateCeilingIsExceeded() {
        definition(widget("risk-revenue", "at_risk_revenue", "risk", "table"));
        when(dealRiskService.assessBoundedWorkspace(WORKSPACE_ID))
                .thenReturn(new DealRiskService.BoundedRiskAssessment(List.of(), true));

        ReportKpiDto result = service.widgetKpi(REPORT_ID, "risk-revenue", null);

        assertFalse(result.available());
        assertEquals("input_limit_exceeded", result.reason());
        assertNull(result.total());
        assertNull(result.unit());
        verify(dealRiskService).assessBoundedWorkspace(WORKSPACE_ID);
        verify(dealRiskService, never()).assessWorkspace(WORKSPACE_ID);
        verify(reportMapper, never()).aggregateDeals(any(ReportAggregateQuery.class));
    }

    @Test
    void widgetKpiPreservesSoleNonIsoCurrencyUnit() {
        definition(widget("revenue", "won_revenue", "none", "kpi"));
        List<ReportAggregateRow> current = List.of(
                new ReportAggregateRow("US$:total", "Total", "US$", new BigDecimal("50.00")));
        List<ReportAggregateRow> prior = List.of(
                new ReportAggregateRow("US$:total", "Total", "US$", new BigDecimal("25.00")));
        when(reportMapper.aggregateDeals(any(ReportAggregateQuery.class)))
                .thenReturn(current)
                .thenReturn(prior)
                .thenReturn(current)
                .thenReturn(prior);
        when(aiReportNarrativeService.generate(
                anyInt(),
                anyString(),
                any(LocalDate.class),
                any(LocalDate.class),
                anyList(),
                anyLong()))
                .thenReturn(new ReportNarrativeDto(
                        true,
                        List.of(new ReportNarrativeSectionDto(
                                "Summary",
                                List.of(new ReportNarrativeClaimDto(
                                        "Revenue is supported by the appendix.",
                                        List.of("metric.0.0"))))),
                        List.of(),
                        null,
                        "2026-07-12T12:00:00Z",
                        0));

        ReportKpiDto result = service.widgetKpi(REPORT_ID, "revenue", null);
        var document = service.generate(REPORT_ID, null, ReportService.NarrativeMode.FULL);

        assertTrue(result.available());
        assertEquals("US$", result.unit());
        assertEquals(0, new BigDecimal("50.00").compareTo(result.total()));
        assertEquals(0, new BigDecimal("25.00").compareTo(result.priorTotal()));
        assertEquals(0, new BigDecimal("100.00").compareTo(result.changePercent()));
        assertEquals("US$", document.widgets().getFirst().unit());
        assertEquals(0, new BigDecimal("25.00").compareTo(
                document.widgets().getFirst().points().getFirst().priorValue()));
        assertEquals("US$", document.appendix().getFirst().unit());
        assertEquals("US$", document.citations().getFirst().unit());
    }

    @Test
    void mixedCaseIsoCurrenciesAlignAcrossComparisonPeriods() {
        definition(widget("revenue", "won_revenue", "none", "kpi"));
        List<ReportAggregateRow> current = List.of(
                new ReportAggregateRow("USD:total", "Total", "USD", new BigDecimal("50.00")));
        List<ReportAggregateRow> prior = List.of(
                new ReportAggregateRow("usd:total", "Total", "usd", new BigDecimal("25.00")));
        when(reportMapper.aggregateDeals(any(ReportAggregateQuery.class)))
                .thenReturn(current)
                .thenReturn(prior);

        ReportKpiDto result = service.widgetKpi(REPORT_ID, "revenue", null);

        assertTrue(result.available());
        assertEquals("USD", result.unit());
        assertEquals(0, new BigDecimal("25.00").compareTo(result.priorTotal()));
        assertEquals(0, new BigDecimal("100.00").compareTo(result.changePercent()));
    }

    @Test
    void mixedCaseIsoRiskCurrenciesProduceOneCanonicalRow() {
        definition(widget("risk-revenue", "at_risk_revenue", "risk", "table"));
        List<DealRiskDto> risks = List.of(
                risk(21, "USD"),
                risk(22, "usd"));
        when(dealRiskService.assessBoundedWorkspace(WORKSPACE_ID))
                .thenReturn(new DealRiskService.BoundedRiskAssessment(risks, false));
        when(dealRiskService.assessWorkspace(WORKSPACE_ID)).thenReturn(risks);
        when(reportMapper.aggregateDeals(any(ReportAggregateQuery.class))).thenReturn(List.of(
                new ReportAggregateRow("USD:total", "Total", "USD", new BigDecimal("40.00")),
                new ReportAggregateRow("usd:total", "Total", "usd", new BigDecimal("60.00"))));

        ReportKpiDto kpi = service.widgetKpi(REPORT_ID, "risk-revenue", null);

        assertTrue(kpi.available());
        assertEquals("USD", kpi.unit());
        assertEquals(0, new BigDecimal("100.00").compareTo(kpi.total()));
        verify(dealRiskService, never()).assessWorkspace(WORKSPACE_ID);

        var document = service.generate(REPORT_ID, null, ReportService.NarrativeMode.NONE);
        var result = document.widgets().getFirst();

        assertEquals("USD", result.unit());
        assertEquals(0, new BigDecimal("100.00").compareTo(result.total()));
        assertEquals(1, result.points().size());
        assertEquals("USD:high", result.points().getFirst().key());
        assertEquals(0, new BigDecimal("100.00").compareTo(result.points().getFirst().value()));
    }

    private ReportDefinition definition(ReportWidgetConfig widget) {
        ReportDefinition definition = new ReportDefinition();
        definition.setId(REPORT_ID);
        definition.setWorkspaceId(WORKSPACE_ID);
        definition.setName("Test report");
        definition.setCadence("custom");
        definition.setConfigJson("""
                {
                  "widgets": [{
                    "id": "%s",
                    "title": null,
                    "dataSource": "deals",
                    "measure": "%s",
                    "groupBy": "%s",
                    "chartType": "%s"
                  }],
                  "filters": {
                    "pipelineIds": null,
                    "ownerIds": null,
                    "statuses": null,
                    "tagIds": null,
                    "warmthBands": null
                  },
                  "range": {"start": "2026-01-01", "end": "2026-01-31"},
                  "bucket": "day",
                  "layout": [{"widgetId": "%s", "x": 0, "y": 0, "width": 6, "height": 4}]
                }
                """.formatted(
                    widget.id(), widget.measure(), widget.groupBy(), widget.chartType(), widget.id()));
        when(reportMapper.getDefinition(WORKSPACE_ID, REPORT_ID)).thenReturn(definition);
        return definition;
    }

    private static ReportWidgetConfig widget(String id, String measure, String groupBy, String chartType) {
        return new ReportWidgetConfig(id, null, "deals", measure, groupBy, chartType);
    }

    private static DealRiskDto risk(int dealId, String currency) {
        return new DealRiskDto(
                dealId,
                BigDecimal.ZERO,
                currency,
                "high",
                50,
                List.of(),
                "2026-07-12 12:00:00");
    }
}
