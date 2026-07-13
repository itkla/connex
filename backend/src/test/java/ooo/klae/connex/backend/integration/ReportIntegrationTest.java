package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import jakarta.servlet.Filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.request.RequestContextHolder;

import ooo.klae.connex.backend.ai.report.AiReportNarrativeService;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.beans.WorkspaceRole;
import ooo.klae.connex.backend.dto.ReportNarrativeClaimDto;
import ooo.klae.connex.backend.dto.ReportNarrativeDto;
import ooo.klae.connex.backend.dto.ReportNarrativeSectionDto;
import ooo.klae.connex.backend.mappers.RoleMapper;
import ooo.klae.connex.backend.mappers.ShareMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Full-stack HTTP coverage for workspace-shared reports, RBAC, deterministic generation, and
 * immutable snapshot restore.
 */
@SpringBootTest
@Transactional
class ReportIntegrationTest {

    private static final String PASSWORD = "Report-Test-Pw1!";
    private static final Instant FIXED_NOW = Instant.parse("2026-07-12T12:00:00Z");
    private static final String REPORT_BODY = """
        {
          "name": "January Activity",
          "description": "Monthly activity review",
          "cadence": "custom",
          "templateKey": null,
          "config": {
            "widgets": [{
              "id": "activity-total",
              "title": "Activity total",
              "dataSource": "activities",
              "measure": "count",
              "groupBy": "none",
              "chartType": "kpi"
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
            "layout": [{"widgetId": "activity-total", "x": 0, "y": 0, "width": 6, "height": 4}]
          }
        }
        """;
    private static final String RELATIONSHIP_HEALTH_BODY = """
        {
          "name": "January Relationship Health",
          "description": "Relationship risk review",
          "cadence": "custom",
          "templateKey": "relationship-health",
          "config": {
            "widgets": [
              {
                "id": "cooling",
                "title": "Company relationship trends",
                "dataSource": "relationships",
                "measure": "company_count",
                "groupBy": "trend",
                "chartType": "bar"
              },
              {
                "id": "coverage-count",
                "title": "Coverage gaps",
                "dataSource": "companies",
                "measure": "coverage_gap_count",
                "groupBy": "none",
                "chartType": "kpi"
              },
              {
                "id": "coverage-value",
                "title": "Coverage gaps by open pipeline",
                "dataSource": "companies",
                "measure": "coverage_gap_open_pipeline_value",
                "groupBy": "company",
                "chartType": "table"
              },
              {
                "id": "single-count",
                "title": "Single-threaded deals",
                "dataSource": "deals",
                "measure": "single_threaded_deal_count",
                "groupBy": "none",
                "chartType": "kpi"
              },
              {
                "id": "single-value",
                "title": "Single-threaded deal value",
                "dataSource": "deals",
                "measure": "single_threaded_deal_value",
                "groupBy": "deal",
                "chartType": "table"
              }
            ],
            "filters": {
              "pipelineIds": null,
              "ownerIds": null,
              "statuses": null,
              "tagIds": null,
              "warmthBands": null
            },
            "range": {"start": "2026-01-01", "end": "2026-01-31"},
            "bucket": "day",
            "layout": [
              {"widgetId": "cooling", "x": 0, "y": 0, "width": 6, "height": 4},
              {"widgetId": "coverage-count", "x": 6, "y": 0, "width": 6, "height": 4},
              {"widgetId": "coverage-value", "x": 0, "y": 4, "width": 6, "height": 4},
              {"widgetId": "single-count", "x": 6, "y": 4, "width": 6, "height": 4},
              {"widgetId": "single-value", "x": 0, "y": 8, "width": 6, "height": 4}
            ]
          }
        }
        """;
    private static final String FORECAST_BODY = """
        {
          "name": "Forward Forecast",
          "description": "Weighted pipeline forecast",
          "cadence": "custom",
          "templateKey": "forecasting",
          "config": {
            "widgets": [
              {
                "id": "forecast-best",
                "title": "Best-case forecast",
                "dataSource": "deals",
                "measure": "forecast_best",
                "groupBy": "date",
                "chartType": "line-area"
              },
              {
                "id": "forecast-weighted",
                "title": "Likely forecast",
                "dataSource": "deals",
                "measure": "forecast_weighted",
                "groupBy": "date",
                "chartType": "line-area"
              },
              {
                "id": "forecast-worst",
                "title": "Commit forecast",
                "dataSource": "deals",
                "measure": "forecast_worst",
                "groupBy": "date",
                "chartType": "line-area"
              },
              {
                "id": "forecast-best-summary",
                "title": "Best-case summary",
                "dataSource": "deals",
                "measure": "forecast_best",
                "groupBy": "none",
                "chartType": "kpi"
              },
              {
                "id": "forecast-weighted-summary",
                "title": "Likely summary",
                "dataSource": "deals",
                "measure": "forecast_weighted",
                "groupBy": "none",
                "chartType": "kpi"
              },
              {
                "id": "forecast-worst-summary",
                "title": "Commit summary",
                "dataSource": "deals",
                "measure": "forecast_worst",
                "groupBy": "none",
                "chartType": "kpi"
              },
              {
                "id": "forecast-best-stage",
                "title": "Forward pipeline by stage",
                "dataSource": "deals",
                "measure": "forecast_best",
                "groupBy": "stage",
                "chartType": "bar"
              }
            ],
            "filters": {
              "pipelineIds": null,
              "ownerIds": null,
              "statuses": null,
              "tagIds": null,
              "warmthBands": null
            },
            "range": {"start": "2026-01-01", "end": "2026-01-31"},
            "bucket": "day",
            "layout": [
              {"widgetId": "forecast-best", "x": 0, "y": 0, "width": 6, "height": 4},
              {"widgetId": "forecast-weighted", "x": 6, "y": 0, "width": 6, "height": 4},
              {"widgetId": "forecast-worst", "x": 0, "y": 4, "width": 6, "height": 4},
              {"widgetId": "forecast-best-summary", "x": 6, "y": 4, "width": 6, "height": 4},
              {"widgetId": "forecast-weighted-summary", "x": 0, "y": 8, "width": 6, "height": 4},
              {"widgetId": "forecast-worst-summary", "x": 6, "y": 8, "width": 6, "height": 4},
              {"widgetId": "forecast-best-stage", "x": 0, "y": 12, "width": 12, "height": 4}
            ]
          }
        }
        """;

    @Autowired private WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") private Filter springSecurityFilterChain;
    @Autowired private UserMapper userMapper;
    @Autowired private WorkspaceMapper workspaceMapper;
    @Autowired private RoleMapper roleMapper;
    @Autowired private ShareMapper shareMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ObjectMapper objectMapper;

    @MockitoBean private AiReportNarrativeService aiReportNarrativeService;
    @MockitoBean private Clock clock;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(FIXED_NOW);
        when(clock.millis()).thenReturn(FIXED_NOW.toEpochMilli());
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        when(clock.withZone(any(ZoneId.class))).thenAnswer(invocation ->
                Clock.fixed(FIXED_NOW, invocation.getArgument(0, ZoneId.class)));
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(springSecurityFilterChain)
                .build();
        when(aiReportNarrativeService.generate(
                anyInt(), anyString(), any(LocalDate.class), any(LocalDate.class), anyList()))
                .thenReturn(new ReportNarrativeDto(
                        true,
                        List.of(new ReportNarrativeSectionDto(
                                "Executive Summary",
                                List.of(new ReportNarrativeClaimDto(
                                        "Activity volume is supported by the appendix.",
                                        List.of("metric.0.0"))))),
                        List.of(),
                        null,
                        "2026-02-01T00:00:00Z",
                        0));
    }

    @Test
    void authenticatedCrudGenerationAndSnapshotFreezeRestore() throws Exception {
        RequestContextHolder.resetRequestAttributes();
        Workspace workspace = newWorkspace();
        User member = newMember(workspace, "member");
        MockHttpSession session = login(member.getUsername());

        int reportId = createReport(session, workspace);

        mockMvc.perform(get("/api/reports/{id}", reportId)
                .header("X-Workspace-Id", workspace.getId())
                .session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("January Activity"));

        mockMvc.perform(get("/api/reports")
                .header("X-Workspace-Id", workspace.getId())
                .session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value(reportId));

        String updated = REPORT_BODY.replace("January Activity", "January Activity Review");
        mockMvc.perform(put("/api/reports/{id}", reportId)
                .header("X-Workspace-Id", workspace.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(updated)
                .session(session)
                .with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("January Activity Review"));

        insertActivity(workspace.getId(), member.getId(), "2026-01-05 09:00:00");
        insertActivity(workspace.getId(), member.getId(), "2026-01-20 09:00:00");
        insertActivity(workspace.getId(), member.getId(), "2025-12-10 09:00:00");

        mockMvc.perform(post("/api/reports/{id}/generate", reportId)
                .header("X-Workspace-Id", workspace.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .session(session)
                .with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.widgets[0].total").value(2))
            .andExpect(jsonPath("$.widgets[0].priorTotal").value(1))
            .andExpect(jsonPath("$.appendix[0].sourceId").value("metric.0.0"))
            .andExpect(jsonPath("$.narrative.available").value(true))
            .andExpect(jsonPath("$.citations[0].sourceId").value("metric.0.0"));

        MvcResult snapshotResult = mockMvc.perform(post("/api/reports/{id}/snapshots", reportId)
                .header("X-Workspace-Id", workspace.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .session(session)
                .with(csrf()))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.computedResult.widgets[0].total").value(2))
            .andReturn();
        int snapshotId = responseId(snapshotResult);

        insertActivity(workspace.getId(), member.getId(), "2026-01-25 09:00:00");

        mockMvc.perform(post("/api/reports/{id}/generate", reportId)
                .header("X-Workspace-Id", workspace.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .session(session)
                .with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.widgets[0].total").value(3));

        mockMvc.perform(get("/api/reports/{id}/snapshots/{snapshotId}", reportId, snapshotId)
                .header("X-Workspace-Id", workspace.getId())
                .session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.computedResult.widgets[0].total").value(2));

        mockMvc.perform(delete("/api/reports/{id}/snapshots/{snapshotId}", reportId, snapshotId)
                .header("X-Workspace-Id", workspace.getId())
                .session(session)
                .with(csrf()))
            .andExpect(status().isNoContent());

        mockMvc.perform(delete("/api/reports/{id}", reportId)
                .header("X-Workspace-Id", workspace.getId())
                .session(session)
                .with(csrf()))
            .andExpect(status().isNoContent());
    }

    @Test
    void relationshipHealthTemplateIsAvailable() throws Exception {
        RequestContextHolder.resetRequestAttributes();
        Workspace workspace = newWorkspace();
        User member = newMember(workspace, "member");
        MockHttpSession session = login(member.getUsername());

        mockMvc.perform(get("/api/reports/templates")
                .header("X-Workspace-Id", workspace.getId())
                .session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.key == 'relationship-health')]").isNotEmpty());
    }

    @Test
    void forecastingTemplateIsAvailable() throws Exception {
        RequestContextHolder.resetRequestAttributes();
        Workspace workspace = newWorkspace();
        User member = newMember(workspace, "member");
        MockHttpSession session = login(member.getUsername());

        MvcResult result = mockMvc.perform(get("/api/reports/templates")
                .header("X-Workspace-Id", workspace.getId())
                .session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.key == 'forecasting')]").isNotEmpty())
            .andReturn();

        JsonNode forecasting = findTemplate(
                objectMapper.readTree(result.getResponse().getContentAsString()), "forecasting");
        assertNotNull(forecasting);
        assertEquals("quarterly", forecasting.get("cadence").asText());
        assertEquals("month", forecasting.get("config").get("bucket").asText());
        List<String> measures = new ArrayList<>();
        for (JsonNode widget : forecasting.get("config").get("widgets")) {
            measures.add(widget.get("measure").asText());
        }
        assertTrue(measures.containsAll(List.of("forecast_best", "forecast_weighted", "forecast_worst")));
    }

    @Test
    void relationshipHealthGenerationAggregatesRiskAndPreservesTenantIsolation() throws Exception {
        RequestContextHolder.resetRequestAttributes();
        Workspace workspace = newWorkspace();
        User member = newMember(workspace, "member");
        MockHttpSession session = login(member.getUsername());
        Workspace otherWorkspace = newWorkspace();
        User otherMember = newMember(otherWorkspace, "member");

        int pipelineId = insertPipeline(workspace.getId(), "Health pipeline");
        int stageId = insertStage(workspace.getId(), pipelineId, "Open");
        int gapZero = insertCompany(workspace.getId(), "Gap Zero");
        int gapOne = insertCompany(workspace.getId(), "Gap One");
        int covered = insertCompany(workspace.getId(), "Covered");
        int cooling = insertCompany(workspace.getId(), "Cooling");
        int gapZeroPerson = insertPerson(workspace.getId(), gapZero, "Gap Zero Stakeholder");
        int gapOneWarm = insertPerson(workspace.getId(), gapOne, "Gap One Warm");
        int gapOneCold = insertPerson(workspace.getId(), gapOne, "Gap One Cold");
        int coveredWarmOne = insertPerson(workspace.getId(), covered, "Covered Warm One");
        int coveredWarmTwo = insertPerson(workspace.getId(), covered, "Covered Warm Two");
        int coolingWarm = insertPerson(workspace.getId(), cooling, "Cooling Warm");
        insertPersonActivity(workspace.getId(), member.getId(), gapOneWarm, "2026-01-20 09:00:00");
        insertPersonActivity(workspace.getId(), member.getId(), coveredWarmOne, "2026-01-20 09:00:00");
        insertPersonActivity(workspace.getId(), member.getId(), coveredWarmTwo, "2026-01-20 09:00:00");
        insertPersonActivity(workspace.getId(), member.getId(), coolingWarm, "2025-12-31 09:00:00");

        int singleDeal = insertDeal(workspace.getId(), pipelineId, stageId, gapZero,
                "Single Thread", "100.00", false);
        insertDealPerson(singleDeal, gapZeroPerson);
        jdbcTemplate.update("UPDATE deal SET expected_close_date = NULL WHERE id = ?", singleDeal);
        int twoThreadDeal = insertDeal(workspace.getId(), pipelineId, stageId, gapOne,
                "Two Threads", "200.00", false);
        insertDealPerson(twoThreadDeal, gapOneWarm);
        insertDealPerson(twoThreadDeal, gapOneCold);
        insertDeal(workspace.getId(), pipelineId, stageId, covered,
                "No Threads", "300.00", false);
        int excludedDeal = insertDeal(workspace.getId(), pipelineId, stageId, covered,
                "Excluded Thread", "400.00", true);
        insertDealPerson(excludedDeal, coveredWarmOne);

        int otherPipelineId = insertPipeline(otherWorkspace.getId(), "Other pipeline");
        int otherStageId = insertStage(otherWorkspace.getId(), otherPipelineId, "Other open");
        int otherCompany = insertCompany(otherWorkspace.getId(), "Other Gap");
        int otherPerson = insertPerson(otherWorkspace.getId(), otherCompany, "Other Stakeholder");
        int otherDeal = insertDeal(otherWorkspace.getId(), otherPipelineId, otherStageId, otherCompany,
                "Other Single Thread", "500.00", false);
        insertDealPerson(otherDeal, otherPerson);
        insertPersonActivity(otherWorkspace.getId(), otherMember.getId(), otherPerson, "2026-01-20 09:00:00");
        int sharedCompany = insertCompany(otherWorkspace.getId(), "Shared Gap");
        int sharedPerson = insertPerson(otherWorkspace.getId(), sharedCompany, "Shared Stakeholder");
        insertCompanyShare(sharedCompany, workspace.getId(), member.getId());
        insertPersonShare(sharedPerson, workspace.getId(), member.getId());

        int reportId = createReport(session, workspace, RELATIONSHIP_HEALTH_BODY);

        mockMvc.perform(post("/api/reports/{id}/generate", reportId)
                .header("X-Workspace-Id", workspace.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .session(session)
                .with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.widgets[0].points[0].key").value("cooling"))
            .andExpect(jsonPath("$.widgets[0].points[0].value").value(1))
            .andExpect(jsonPath("$.widgets[0].total").value(5))
            .andExpect(jsonPath("$.widgets[0].points[1].value").value(2))
            .andExpect(jsonPath("$.widgets[0].points[2].value").value(2))
            .andExpect(jsonPath("$.widgets[1].total").value(4))
            .andExpect(jsonPath("$.widgets[2].points[0].label").value("USD · Gap One"))
            .andExpect(jsonPath("$.widgets[2].points[0].value").value(200))
            .andExpect(jsonPath("$.widgets[2].points[1].label").value("USD · Gap Zero"))
            .andExpect(jsonPath("$.widgets[2].points[1].value").value(100))
            .andExpect(jsonPath("$.widgets[3].total").value(1))
            .andExpect(jsonPath("$.widgets[3].priorTotal").doesNotExist())
            .andExpect(jsonPath("$.widgets[4].total").value(100))
            .andExpect(jsonPath("$.widgets[4].points[0].label").value("USD · Single Thread"));
    }

    @Test
    void forecastingGenerationUsesHistoricalRatesForwardWindowAndTenantIsolation() throws Exception {
        RequestContextHolder.resetRequestAttributes();
        Workspace workspace = newWorkspace();
        User member = newMember(workspace, "member");
        MockHttpSession session = login(member.getUsername());
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        LocalDate inHorizon = today;

        int pipelineId = insertPipeline(workspace.getId(), "Forecast pipeline");
        int lowRateStageId = insertStage(workspace.getId(), pipelineId, "Low historical rate", 1);
        int highRateStageId = insertStage(workspace.getId(), pipelineId, "High historical rate", 2);
        int fallbackStageId = insertStage(workspace.getId(), pipelineId, "No history", 3);
        insertClosedDeal(workspace.getId(), pipelineId, lowRateStageId,
                "Future-date closed won", "1000.00", "USD", true, inHorizon);
        for (int index = 0; index < 3; index++) {
            insertClosedDeal(workspace.getId(), pipelineId, lowRateStageId,
                    "Low-rate lost " + index, "10.00", "USD", false, today.minusMonths(2));
        }
        for (int index = 0; index < 4; index++) {
            insertClosedDeal(workspace.getId(), pipelineId, highRateStageId,
                    "High-rate won " + index, "10.00", "USD", true, today.minusMonths(2));
        }
        insertOpenDeal(workspace.getId(), pipelineId, lowRateStageId,
                "Historical stage open", "100.00", "USD", inHorizon);
        insertOpenDeal(workspace.getId(), pipelineId, fallbackStageId,
                "Fallback stage open", "200.00", "USD", inHorizon);
        insertOpenDeal(workspace.getId(), pipelineId, lowRateStageId,
                "Euro open", "50.00", "EUR", inHorizon);
        insertOpenDeal(workspace.getId(), pipelineId, lowRateStageId,
                "Next month open", "40.00", "USD", inHorizon.plusMonths(1));
        insertOpenDeal(workspace.getId(), pipelineId, lowRateStageId,
                "Past open", "1000.00", "USD", today.minusDays(1));
        insertOpenDeal(workspace.getId(), pipelineId, lowRateStageId,
                "Boundary open", "1000.00", "USD", today.plusMonths(3));

        Workspace otherWorkspace = newWorkspace();
        int otherPipelineId = insertPipeline(otherWorkspace.getId(), "Other forecast pipeline");
        int otherStageId = insertStage(otherWorkspace.getId(), otherPipelineId, "Other stage", 1);
        for (int index = 0; index < 5; index++) {
            insertClosedDeal(otherWorkspace.getId(), otherPipelineId, otherStageId,
                    "Other won " + index, "10.00", "USD", true, today.minusMonths(1));
        }
        insertOpenDeal(otherWorkspace.getId(), otherPipelineId, otherStageId,
                "Other open", "5000.00", "USD", inHorizon);

        int reportId = createReport(session, workspace, FORECAST_BODY);
        MvcResult result = mockMvc.perform(post("/api/reports/{id}/generate", reportId)
                .header("X-Workspace-Id", workspace.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .session(session)
                .with(csrf()))
            .andExpect(status().isOk())
            .andReturn();

        JsonNode document = objectMapper.readTree(result.getResponse().getContentAsString());
        JsonNode widgets = document.get("widgets");
        Map<String, BigDecimal> best = pointValues(widgets.get(0));
        Map<String, BigDecimal> weighted = pointValues(widgets.get(1));
        Map<String, BigDecimal> worst = pointValues(widgets.get(2));
        String month = YearMonth.from(inHorizon).toString();
        assertDecimal("300", best.get("USD:" + month));
        assertDecimal("150", weighted.get("USD:" + month));
        assertDecimal("84.375", worst.get("USD:" + month));
        assertDecimal("50", best.get("EUR:" + month));
        assertDecimal("12.5", weighted.get("EUR:" + month));
        assertDecimal("3.125", worst.get("EUR:" + month));
        String nextMonth = YearMonth.from(inHorizon.plusMonths(1)).toString();
        assertDecimal("40", best.get("USD:" + nextMonth));
        assertDecimal("10", weighted.get("USD:" + nextMonth));
        assertDecimal("2.5", worst.get("USD:" + nextMonth));
        assertEquals(Set.of("USD:" + month, "EUR:" + month, "USD:" + nextMonth), best.keySet());
        assertEquals(best.keySet(), weighted.keySet());
        assertEquals(best.keySet(), worst.keySet());
        for (String key : best.keySet()) {
            assertTrue(worst.get(key).compareTo(weighted.get(key)) <= 0);
            assertTrue(weighted.get(key).compareTo(best.get(key)) <= 0);
        }
        Map<String, BigDecimal> bestSummary = pointValues(widgets.get(3));
        Map<String, BigDecimal> weightedSummary = pointValues(widgets.get(4));
        Map<String, BigDecimal> worstSummary = pointValues(widgets.get(5));
        Map<String, BigDecimal> bestByStage = pointValues(widgets.get(6));
        assertDecimal(sumCurrency(best, "USD"), bestSummary.get("USD:total"));
        assertDecimal(sumCurrency(best, "EUR"), bestSummary.get("EUR:total"));
        assertDecimal(sumCurrency(weighted, "USD"), weightedSummary.get("USD:total"));
        assertDecimal(sumCurrency(weighted, "EUR"), weightedSummary.get("EUR:total"));
        assertDecimal(sumCurrency(worst, "USD"), worstSummary.get("USD:total"));
        assertDecimal(sumCurrency(worst, "EUR"), worstSummary.get("EUR:total"));
        assertDecimal(bestSummary.get("USD:total"), sumCurrency(bestByStage, "USD"));
        assertDecimal(bestSummary.get("EUR:total"), sumCurrency(bestByStage, "EUR"));
        for (JsonNode widget : widgets) {
            assertEquals("mixed", widget.get("unit").asText());
            assertTrue(widget.get("total") == null || widget.get("total").isNull());
            assertTrue(widget.get("priorTotal") == null || widget.get("priorTotal").isNull());
            for (JsonNode point : widget.get("points")) {
                assertTrue(point.get("priorValue") == null || point.get("priorValue").isNull());
            }
        }
    }

    @Test
    void forecastingUsesNeutralDefaultWithoutClosedHistory() throws Exception {
        RequestContextHolder.resetRequestAttributes();
        Workspace workspace = newWorkspace();
        User member = newMember(workspace, "member");
        MockHttpSession session = login(member.getUsername());
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        LocalDate inHorizon = today.plusDays(7);
        Integer orgId = workspaceMapper.getOrgId(workspace.getId());
        assertNotNull(orgId);
        Workspace pipelineOwner = newWorkspaceInOrg(orgId);
        User pipelineOwnerMember = newMember(pipelineOwner, "member");
        int pipelineId = insertPipeline(pipelineOwner.getId(), "Neutral forecast pipeline");
        int stageId = insertStage(pipelineOwner.getId(), pipelineId, "Neutral stage", 1);
        assertEquals(1, shareMapper.sharePipeline(
                pipelineId, pipelineOwner.getId(), workspace.getId(), pipelineOwnerMember.getId(), false));
        insertOpenDeal(workspace.getId(), pipelineId, stageId,
                "Neutral open", "100.00", "USD", inHorizon);

        Workspace otherWorkspace = newWorkspace();
        int otherPipelineId = insertPipeline(otherWorkspace.getId(), "Other neutral pipeline");
        int otherStageId = insertStage(otherWorkspace.getId(), otherPipelineId, "Other neutral stage", 1);
        insertClosedDeal(otherWorkspace.getId(), otherPipelineId, otherStageId,
                "Other neutral won", "10.00", "USD", true, today.minusMonths(1));

        int reportId = createReport(session, workspace, FORECAST_BODY);
        MvcResult result = mockMvc.perform(post("/api/reports/{id}/generate", reportId)
                .header("X-Workspace-Id", workspace.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .session(session)
                .with(csrf()))
            .andExpect(status().isOk())
            .andReturn();

        JsonNode widgets = objectMapper.readTree(result.getResponse().getContentAsString()).get("widgets");
        String key = "USD:" + YearMonth.from(inHorizon);
        assertDecimal("100", pointValues(widgets.get(0)).get(key));
        assertDecimal("50", pointValues(widgets.get(1)).get(key));
        assertDecimal("25", pointValues(widgets.get(2)).get(key));
    }

    @Test
    void customRoleWithoutCreatePermissionIsForbidden() throws Exception {
        RequestContextHolder.resetRequestAttributes();
        Workspace workspace = newWorkspace();
        User reader = newMember(workspace, "member");
        WorkspaceRole role = new WorkspaceRole();
        role.setWorkspaceId(workspace.getId());
        role.setName("Report Reader " + UUID.randomUUID().toString().substring(0, 8));
        roleMapper.insertRole(role);
        roleMapper.insertPermissions(workspace.getId(), role.getId(), List.of("REPORT_READ"));
        workspaceMapper.setMemberCustomRole(workspace.getId(), reader.getId(), role.getId());
        MockHttpSession session = login(reader.getUsername());

        mockMvc.perform(post("/api/reports")
                .header("X-Workspace-Id", workspace.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(REPORT_BODY)
                .session(session)
                .with(csrf()))
            .andExpect(status().isForbidden());
    }

    @Test
    void customRoleWithoutReadPermissionIsForbidden() throws Exception {
        RequestContextHolder.resetRequestAttributes();
        Workspace workspace = newWorkspace();
        User member = newMember(workspace, "member");
        MockHttpSession session = login(member.getUsername());
        int reportId = createReport(session, workspace);
        WorkspaceRole role = new WorkspaceRole();
        role.setWorkspaceId(workspace.getId());
        role.setName("No Report Access " + UUID.randomUUID().toString().substring(0, 8));
        roleMapper.insertRole(role);
        roleMapper.insertPermissions(workspace.getId(), role.getId(), List.of("CONTACT_READ"));
        workspaceMapper.setMemberCustomRole(workspace.getId(), member.getId(), role.getId());

        mockMvc.perform(get("/api/reports/{id}", reportId)
                .header("X-Workspace-Id", workspace.getId())
                .session(session))
            .andExpect(status().isForbidden());
    }

    @Test
    void reportIdIsIsolatedAcrossWorkspacesForSameUser() throws Exception {
        RequestContextHolder.resetRequestAttributes();
        Workspace first = newWorkspace();
        Workspace second = newWorkspace();
        User member = newMember(first, "member");
        workspaceMapper.addMember(second.getId(), member.getId(), "member");
        MockHttpSession session = login(member.getUsername());
        int reportId = createReport(session, first);
        MvcResult snapshotResult = mockMvc.perform(post("/api/reports/{id}/snapshots", reportId)
                .header("X-Workspace-Id", first.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .session(session)
                .with(csrf()))
            .andExpect(status().isCreated())
            .andReturn();
        int snapshotId = responseId(snapshotResult);

        mockMvc.perform(get("/api/reports/{id}", reportId)
                .header("X-Workspace-Id", second.getId())
                .session(session))
            .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/reports")
                .header("X-Workspace-Id", second.getId())
                .session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(0));

        mockMvc.perform(post("/api/reports/{id}/generate", reportId)
                .header("X-Workspace-Id", second.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .session(session)
                .with(csrf()))
            .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/reports/{id}/snapshots", reportId)
                .header("X-Workspace-Id", second.getId())
                .session(session))
            .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/reports/{id}/snapshots/{snapshotId}", reportId, snapshotId)
                .header("X-Workspace-Id", second.getId())
                .session(session))
            .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/reports/{id}/export.csv", reportId)
                .header("X-Workspace-Id", second.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .session(session)
                .with(csrf()))
            .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/reports/{id}/snapshots/{snapshotId}", reportId, snapshotId)
                .header("X-Workspace-Id", second.getId())
                .session(session)
                .with(csrf()))
            .andExpect(status().isNotFound());
    }

    @Test
    void dateBucketsAlignPriorValuesByRelativePeriodPosition() throws Exception {
        RequestContextHolder.resetRequestAttributes();
        Workspace workspace = newWorkspace();
        User member = newMember(workspace, "member");
        MockHttpSession session = login(member.getUsername());
        String dateReport = REPORT_BODY
                .replace("\"groupBy\": \"none\"", "\"groupBy\": \"date\"")
                .replace("\"chartType\": \"kpi\"", "\"chartType\": \"line-area\"");
        int reportId = createReport(session, workspace, dateReport);
        insertActivity(workspace.getId(), member.getId(), "2026-01-03 09:00:00");
        insertActivity(workspace.getId(), member.getId(), "2025-12-03 09:00:00");

        mockMvc.perform(post("/api/reports/{id}/generate", reportId)
                .header("X-Workspace-Id", workspace.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .session(session)
                .with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.widgets[0].points.length()").value(1))
            .andExpect(jsonPath("$.widgets[0].points[0].value").value(1))
            .andExpect(jsonPath("$.widgets[0].points[0].priorValue").value(1));
    }

    private int createReport(MockHttpSession session, Workspace workspace) throws Exception {
        return createReport(session, workspace, REPORT_BODY);
    }

    private int createReport(MockHttpSession session, Workspace workspace, String body) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/reports")
                .header("X-Workspace-Id", workspace.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .session(session)
                .with(csrf()))
            .andExpect(status().isCreated())
            .andReturn();
        return responseId(result);
    }

    private int responseId(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asInt();
    }

    private static JsonNode findTemplate(JsonNode templates, String key) {
        for (JsonNode template : templates) {
            if (key.equals(template.get("key").asText())) {
                return template;
            }
        }
        return null;
    }

    private static Map<String, BigDecimal> pointValues(JsonNode widget) {
        Map<String, BigDecimal> values = new LinkedHashMap<>();
        for (JsonNode point : widget.get("points")) {
            values.put(point.get("key").asText(), point.get("value").decimalValue());
        }
        return values;
    }

    private static void assertDecimal(String expected, BigDecimal actual) {
        assertNotNull(actual);
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }

    private static void assertDecimal(BigDecimal expected, BigDecimal actual) {
        assertNotNull(expected);
        assertNotNull(actual);
        assertEquals(0, expected.compareTo(actual));
    }

    private static BigDecimal sumCurrency(Map<String, BigDecimal> values, String currency) {
        return values.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(currency + ":"))
                .map(Map.Entry::getValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private void insertActivity(int workspaceId, int userId, String timestamp) {
        jdbcTemplate.update(
                "INSERT INTO activity (workspace_id, type, subject, created_by_id, timestamp) VALUES (?, ?, ?, ?, ?)",
                workspaceId, "call", "Report integration activity", userId, timestamp);
    }

    private int insertPipeline(int workspaceId, String name) {
        jdbcTemplate.update(
                "INSERT INTO pipeline (workspace_id, name, created_at) VALUES (?, ?, ?)",
                workspaceId, name, "2026-01-01 00:00:00");
        return jdbcTemplate.queryForObject(
                "SELECT id FROM pipeline WHERE workspace_id = ? AND name = ?",
                Integer.class, workspaceId, name);
    }

    private int insertStage(int workspaceId, int pipelineId, String name) {
        return insertStage(workspaceId, pipelineId, name, 1);
    }

    private int insertStage(int workspaceId, int pipelineId, String name, int position) {
        jdbcTemplate.update(
                "INSERT INTO stage (workspace_id, name, pipeline_id, position) VALUES (?, ?, ?, ?)",
                workspaceId, name, pipelineId, position);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM stage WHERE workspace_id = ? AND pipeline_id = ? AND position = ?",
                Integer.class, workspaceId, pipelineId, position);
    }

    private int insertCompany(int workspaceId, String name) {
        jdbcTemplate.update(
                "INSERT INTO company (workspace_id, name, created_at) VALUES (?, ?, ?)",
                workspaceId, name, "2026-01-01 00:00:00");
        return jdbcTemplate.queryForObject(
                "SELECT id FROM company WHERE workspace_id = ? AND name = ?",
                Integer.class, workspaceId, name);
    }

    private int insertPerson(int workspaceId, int companyId, String name) {
        jdbcTemplate.update(
                "INSERT INTO person (workspace_id, company_id, name, created_at) VALUES (?, ?, ?, ?)",
                workspaceId, companyId, name, "2026-01-01 00:00:00");
        return jdbcTemplate.queryForObject(
                "SELECT id FROM person WHERE workspace_id = ? AND name = ?",
                Integer.class, workspaceId, name);
    }

    private void insertPersonActivity(int workspaceId, int userId, int personId, String timestamp) {
        jdbcTemplate.update(
                "INSERT INTO activity (workspace_id, type, subject, created_by_id, person_id, timestamp) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                workspaceId, "meeting", "Relationship health activity", userId, personId, timestamp);
    }

    private int insertDeal(int workspaceId, int pipelineId, int stageId, int companyId,
            String name, String value, boolean riskExcluded) {
        jdbcTemplate.update(
                "INSERT INTO deal (workspace_id, name, value, currency, pipeline_id, stage_id, company_id, "
                        + "expected_close_date, risk_excluded, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                workspaceId, name, value, "USD", pipelineId, stageId, companyId,
                "2026-01-25", riskExcluded, "2026-01-01 00:00:00");
        return jdbcTemplate.queryForObject(
                "SELECT id FROM deal WHERE workspace_id = ? AND name = ?",
                Integer.class, workspaceId, name);
    }

    private void insertOpenDeal(int workspaceId, int pipelineId, int stageId,
            String name, String value, String currency, LocalDate expectedCloseDate) {
        jdbcTemplate.update(
                "INSERT INTO deal (workspace_id, name, value, currency, pipeline_id, stage_id, "
                        + "expected_close_date, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                workspaceId, name, value, currency, pipelineId, stageId,
                expectedCloseDate, LocalDateTime.now(clock).minusMonths(1));
    }

    private void insertClosedDeal(int workspaceId, int pipelineId, int stageId,
            String name, String value, String currency, boolean won, LocalDate expectedCloseDate) {
        jdbcTemplate.update(
                "INSERT INTO deal (workspace_id, name, value, currency, pipeline_id, stage_id, "
                        + "expected_close_date, closed_at, won, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                workspaceId, name, value, currency, pipelineId, stageId, expectedCloseDate,
                LocalDateTime.now(clock).minusMonths(1), won, LocalDateTime.now(clock).minusMonths(2));
    }

    private void insertDealPerson(int dealId, int personId) {
        jdbcTemplate.update(
                "INSERT INTO deal_person (deal_id, person_id, role) VALUES (?, ?, ?)",
                dealId, personId, "stakeholder");
    }

    private void insertCompanyShare(int companyId, int workspaceId, int grantedBy) {
        jdbcTemplate.update(
                "INSERT INTO company_share (company_id, workspace_id, granted_by, created_at) VALUES (?, ?, ?, ?)",
                companyId, workspaceId, grantedBy, "2026-01-01 00:00:00");
    }

    private void insertPersonShare(int personId, int workspaceId, int grantedBy) {
        jdbcTemplate.update(
                "INSERT INTO person_share (person_id, workspace_id, granted_by, created_at) VALUES (?, ?, ?, ?)",
                personId, workspaceId, grantedBy, "2026-01-01 00:00:00");
    }

    private Workspace newWorkspace() {
        String slug = "report-" + UUID.randomUUID().toString().substring(0, 8);
        Workspace workspace = new Workspace();
        workspace.setName(slug);
        workspace.setSlug(slug);
        workspaceMapper.insert(workspace);
        return workspace;
    }

    private Workspace newWorkspaceInOrg(int orgId) {
        String slug = "report-" + UUID.randomUUID().toString().substring(0, 8);
        Workspace workspace = new Workspace();
        workspace.setName(slug);
        workspace.setSlug(slug);
        workspace.setOrgId(orgId);
        workspaceMapper.insert(workspace);
        return workspace;
    }

    private User newMember(Workspace workspace, String role) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User user = new User();
        user.setUsername("report_" + suffix);
        user.setDisplayName("Report " + suffix);
        user.setEmail(suffix + "@example.com");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setTimezone("UTC");
        userMapper.insert(user);
        workspaceMapper.addMember(workspace.getId(), user.getId(), role);
        return user;
    }

    private MockHttpSession login(String username) throws Exception {
        String body = "{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}";
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andReturn();
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertNotNull(session, "login did not establish a report test session");
        return session;
    }
}
