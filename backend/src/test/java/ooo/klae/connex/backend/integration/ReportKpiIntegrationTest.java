package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.request.RequestContextHolder;

import ooo.klae.connex.backend.ai.AiGenerationService;
import ooo.klae.connex.backend.ai.AiRestrictionEpoch;
import ooo.klae.connex.backend.ai.report.AiReportNarrativeService;
import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.ReportDefinition;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.beans.WorkspaceRole;
import ooo.klae.connex.backend.dto.ReportNarrativeDto;
import ooo.klae.connex.backend.mail.MailService;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.PersonEdgeMapper;
import ooo.klae.connex.backend.mappers.ReportMapper;
import ooo.klae.connex.backend.mappers.RoleMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.services.DealRiskService;
import ooo.klae.connex.backend.services.OrganizationWorkspaceScopeControlAccess;
import ooo.klae.connex.backend.services.OrganizationWorkspaceScopeControlOperations;
import ooo.klae.connex.backend.services.ReportNetworkService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Transaction-boundary HTTP coverage for deterministic report KPI projections and authorization. */
@SpringBootTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ReportKpiIntegrationTest {

    private static final String PASSWORD = "Report-Kpi-Test-Pw1!";
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
    private static final String NETWORK_REPORT_BODY = commercialReportBody(List.of(
            new CommercialWidget(
                    "reachable-pipeline", "companies", "warm_intro_opportunity_value", "none", "kpi"),
            new CommercialWidget(
                    "reverse-intro-value",
                    "relationships",
                    "reverse_intro_weighted_opportunities",
                    "none",
                    "kpi")));
    private static final String ATTAINMENT_BODY = """
        {
          "name": "July Quota Attainment",
          "description": "Revenue targets and actuals",
          "cadence": "monthly",
          "templateKey": "quota-attainment",
          "config": {
            "widgets": [
              {
                "id": "owner-attainment",
                "title": "Attainment by owner",
                "dataSource": "deals",
                "measure": "attainment",
                "groupBy": "owner",
                "chartType": "bar"
              },
              {
                "id": "workspace-attainment",
                "title": "Overall attainment",
                "dataSource": "deals",
                "measure": "attainment",
                "groupBy": "none",
                "chartType": "kpi"
              }
            ],
            "filters": {
              "pipelineIds": null,
              "ownerIds": null,
              "statuses": null,
              "tagIds": null,
              "warmthBands": null
            },
            "range": null,
            "bucket": "month",
            "layout": [
              {"widgetId": "owner-attainment", "x": 0, "y": 0, "width": 6, "height": 4},
              {"widgetId": "workspace-attainment", "x": 6, "y": 0, "width": 6, "height": 4}
            ]
          }
        }
        """;

    @Autowired private WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") private Filter springSecurityFilterChain;
    @Autowired private UserMapper userMapper;
    @Autowired private WorkspaceMapper workspaceMapper;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private RoleMapper roleMapper;
    @Autowired private ReportMapper reportMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private Clock clock;

    @MockitoBean private AiReportNarrativeService aiReportNarrativeService;
    @MockitoBean private AiGenerationService aiGenerationService;
    @MockitoBean private AiRestrictionEpoch aiRestrictionEpoch;
    @MockitoBean private MailService mailService;
    @MockitoBean private OrganizationWorkspaceScopeControlAccess workspaceScopeControlAccess;
    @MockitoSpyBean private DealRiskService dealRiskService;
    @MockitoSpyBean private PersonEdgeMapper personEdgeMapper;
    @MockitoSpyBean private ReportNetworkService reportNetworkService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        OrganizationWorkspaceScopeControlOperations scopeOperations =
                new OrganizationWorkspaceScopeControlOperations(workspaceMapper);
        when(workspaceScopeControlAccess.getForWorkspace(anyInt())).thenAnswer(invocation ->
                scopeOperations.getForWorkspace(invocation.getArgument(0, Integer.class)));
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(springSecurityFilterChain)
                .build();
        when(aiReportNarrativeService.cachedNarrative(
                anyInt(), anyString(), any(LocalDate.class), any(LocalDate.class), anyList()))
                .thenReturn(ReportNarrativeDto.unavailable("not_cached"));
    }

    @Test
    void widgetKpiMatchesGeneratedWidgetForTheSamePeriodWithoutUsingAi() throws Exception {
        RequestContextHolder.resetRequestAttributes();
        Workspace workspace = newWorkspaceInOrg(newOrganization().getId());
        User member = newReportMember(workspace, "REPORT_CREATE", "REPORT_READ");
        MockHttpSession session = login(member.getUsername());
        int pipeline = insertPipeline(workspace.getId(), "KPI parity");
        int stage = insertStage(workspace.getId(), pipeline, "Won");
        insertWonRevenue(workspace.getId(), pipeline, stage, member.getId(),
                "Current revenue", "125.50", "USD", "2026-01-15 09:00:00");
        insertWonRevenue(workspace.getId(), pipeline, stage, member.getId(),
                "Prior revenue", "100.00", "USD", "2025-12-15 09:00:00");
        int reportId = createReport(session, workspace, commercialReportBody(List.of(
                new CommercialWidget("revenue", "deals", "won_revenue", "none", "kpi"))));

        MvcResult kpiResult = mockMvc.perform(get("/api/reports/{id}/widgets/{widgetId}/kpi", reportId, "revenue")
                .param("start", "2026-01-01")
                .param("end", "2026-01-31")
                .header("X-Workspace-Id", workspace.getId())
                .session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.reportId").value(reportId))
            .andExpect(jsonPath("$.reportName").value("Commercial documents"))
            .andExpect(jsonPath("$.widgetId").value("revenue"))
            .andExpect(jsonPath("$.available").value(true))
            .andExpect(jsonPath("$.reason").doesNotExist())
            .andExpect(jsonPath("$.periodStart").value("2026-01-01"))
            .andExpect(jsonPath("$.periodEnd").value("2026-01-31"))
            .andReturn();

        verify(aiReportNarrativeService, never()).cachedNarrative(
                anyInt(), anyString(), any(LocalDate.class), any(LocalDate.class), anyList());
        verify(aiReportNarrativeService, never()).generate(
                anyInt(), anyString(), any(LocalDate.class), any(LocalDate.class), anyList(), anyLong());
        verify(aiGenerationService, never()).startAtRestrictionEpoch(
                any(), any(), anySet(), any(), any(), anyLong());
        verify(aiRestrictionEpoch, never()).current(anyInt());
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_output_cache WHERE workspace_id = ?",
                Integer.class,
                workspace.getId()));

        MvcResult generatedResult = mockMvc.perform(post("/api/reports/{id}/generate", reportId)
                .header("X-Workspace-Id", workspace.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"start\":\"2026-01-01\",\"end\":\"2026-01-31\"}")
                .session(session)
                .with(csrf().asHeader()))
            .andExpect(status().isOk())
            .andReturn();
        JsonNode kpi = objectMapper.readTree(kpiResult.getResponse().getContentAsString());
        JsonNode generatedWidget = objectMapper.readTree(generatedResult.getResponse().getContentAsString())
                .get("widgets").get(0);
        for (String field : List.of("total", "priorTotal", "changePercent", "unit")) {
            assertEquals(generatedWidget.get(field), kpi.get(field), field);
        }

        mockMvc.perform(get("/api/reports/{id}/widgets/{widgetId}/kpi", reportId, "revenue")
                .param("start", "2020-01-01")
                .param("end", "2026-01-01")
                .header("X-Workspace-Id", workspace.getId())
                .session(session))
            .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/reports/{id}/widgets/{widgetId}/kpi", reportId, "revenue")
                .param("start", "2026-01-01")
                .header("X-Workspace-Id", workspace.getId())
                .session(session))
            .andExpect(status().isBadRequest());
    }

    @Test
    void widgetKpiExplainsUnavailableScalarsWithoutChangingGeneratedFigures() throws Exception {
        RequestContextHolder.resetRequestAttributes();
        Workspace workspace = newWorkspaceInOrg(newOrganization().getId());
        User member = newReportMember(workspace, "REPORT_CREATE", "REPORT_READ");
        MockHttpSession session = login(member.getUsername());
        int pipeline = insertPipeline(workspace.getId(), "KPI availability");
        int stage = insertStage(workspace.getId(), pipeline, "Won");
        insertWonRevenue(workspace.getId(), pipeline, stage, member.getId(),
                "Dollar revenue", "50.00", "USD", "2026-01-10 09:00:00");
        insertWonRevenue(workspace.getId(), pipeline, stage, member.getId(),
                "Yen revenue", "5000.00", "JPY", "2026-01-11 09:00:00");
        int reportId = createReport(session, workspace, commercialReportBody(List.of(
                new CommercialWidget("revenue", "deals", "won_revenue", "none", "kpi"),
                new CommercialWidget("win-rate", "deals", "win_rate", "owner", "bar"),
                new CommercialWidget("issue-rate", "documents", "quote_issue_rate", "none", "kpi"))));

        mockMvc.perform(get("/api/reports/{id}/widgets/{widgetId}/kpi", reportId, "revenue")
                .header("X-Workspace-Id", workspace.getId())
                .session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").doesNotExist())
            .andExpect(jsonPath("$.available").value(false))
            .andExpect(jsonPath("$.reason").value("mixed_currency"));

        mockMvc.perform(get("/api/reports/{id}/widgets/{widgetId}/kpi", reportId, "win-rate")
                .header("X-Workspace-Id", workspace.getId())
                .session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").doesNotExist())
            .andExpect(jsonPath("$.available").value(false))
            .andExpect(jsonPath("$.reason").value("non_additive"));

        mockMvc.perform(get("/api/reports/{id}/widgets/{widgetId}/kpi", reportId, "issue-rate")
                .header("X-Workspace-Id", workspace.getId())
                .session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").doesNotExist())
            .andExpect(jsonPath("$.available").value(false))
            .andExpect(jsonPath("$.reason").value("undefined"));

        mockMvc.perform(post("/api/reports/{id}/generate", reportId)
                .header("X-Workspace-Id", workspace.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .session(session)
                .with(csrf().asHeader()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.widgets[0].total").doesNotExist())
            .andExpect(jsonPath("$.widgets[1].total").doesNotExist())
            .andExpect(jsonPath("$.widgets[2].total").doesNotExist());
    }

    @Test
    void widgetKpiRejectsDistinctNonIsoCurrenciesWithoutBlendingGeneratedFigure() throws Exception {
        RequestContextHolder.resetRequestAttributes();
        Workspace workspace = newWorkspaceInOrg(newOrganization().getId());
        User member = newReportMember(workspace, "REPORT_CREATE", "REPORT_READ");
        MockHttpSession session = login(member.getUsername());
        int pipeline = insertPipeline(workspace.getId(), "Non-ISO currencies");
        int stage = insertStage(workspace.getId(), pipeline, "Won");
        insertWonRevenue(workspace.getId(), pipeline, stage, member.getId(),
                "Dollar-symbol revenue", "50.00", "US$", "2026-01-10 09:00:00");
        insertWonRevenue(workspace.getId(), pipeline, stage, member.getId(),
                "Yen-symbol revenue", "5000.00", "JP$", "2026-01-11 09:00:00");
        int reportId = createReport(session, workspace, commercialReportBody(List.of(
                new CommercialWidget("revenue", "deals", "won_revenue", "none", "kpi"))));

        mockMvc.perform(get("/api/reports/{id}/widgets/{widgetId}/kpi", reportId, "revenue")
                .header("X-Workspace-Id", workspace.getId())
                .session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").doesNotExist())
            .andExpect(jsonPath("$.unit").value("mixed"))
            .andExpect(jsonPath("$.available").value(false))
            .andExpect(jsonPath("$.reason").value("mixed_currency"));

        JsonNode generatedWidget = findWidget(
                generateDocument(session, workspace, reportId).get("widgets"), "revenue");
        assertNoScalarTotal(generatedWidget, "revenue");
        assertEquals("mixed", generatedWidget.get("unit").asText());
        assertEquals(Set.of("US$:total", "JP$:total"), pointValues(generatedWidget).keySet());
    }

    @Test
    void widgetKpiRejectsDelimiterContainingAtRiskCurrenciesWithoutBlendingGeneratedFigure() throws Exception {
        RequestContextHolder.resetRequestAttributes();
        Workspace workspace = newWorkspaceInOrg(newOrganization().getId());
        User member = newReportMember(workspace, "REPORT_CREATE", "REPORT_READ");
        MockHttpSession session = login(member.getUsername());
        int pipeline = insertPipeline(workspace.getId(), "At-risk currencies");
        int stage = insertStage(workspace.getId(), pipeline, "Open");
        insertOpenDeal(workspace.getId(), pipeline, stage,
                "Colon dollar risk", "50.00", "A:USD", LocalDate.of(2026, 1, 10));
        insertOpenDeal(workspace.getId(), pipeline, stage,
                "Colon yen risk", "5000.00", "A:JPY", LocalDate.of(2026, 1, 11));
        int reportId = createReport(session, workspace, commercialReportBody(List.of(
                new CommercialWidget(
                        "risk-revenue", "deals", "at_risk_revenue", "risk", "table"))));

        mockMvc.perform(get("/api/reports/{id}/widgets/{widgetId}/kpi", reportId, "risk-revenue")
                .header("X-Workspace-Id", workspace.getId())
                .session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").doesNotExist())
            .andExpect(jsonPath("$.unit").value("mixed"))
            .andExpect(jsonPath("$.available").value(false))
            .andExpect(jsonPath("$.reason").value("mixed_currency"));

        JsonNode generatedWidget = findWidget(
                generateDocument(session, workspace, reportId).get("widgets"), "risk-revenue");
        assertNoScalarTotal(generatedWidget, "risk-revenue");
        assertEquals("mixed", generatedWidget.get("unit").asText());
        Map<String, BigDecimal> values = pointValues(generatedWidget);
        assertEquals(Set.of("A:USD:high", "A:JPY:high"), values.keySet());
        assertDecimal("50", values.get("A:USD:high"));
        assertDecimal("5000", values.get("A:JPY:high"));
    }

    @Test
    void atRiskRevenueKpiMatchesGeneratedWidgetWhenBoundedInputIsComplete() throws Exception {
        RequestContextHolder.resetRequestAttributes();
        Workspace workspace = newWorkspaceInOrg(newOrganization().getId());
        User member = newReportMember(workspace, "REPORT_CREATE", "REPORT_READ");
        MockHttpSession session = login(member.getUsername());
        int pipeline = insertPipeline(workspace.getId(), "At-risk parity");
        int stage = insertStage(workspace.getId(), pipeline, "Open");
        insertOpenDeal(workspace.getId(), pipeline, stage,
                "Bounded risky deal", "75.25", "USD", LocalDate.of(2026, 1, 10));
        int reportId = createReport(session, workspace, commercialReportBody(List.of(
                new CommercialWidget(
                        "risk-revenue", "deals", "at_risk_revenue", "risk", "table"))));

        JsonNode generatedWidget = findWidget(
                generateDocument(session, workspace, reportId).get("widgets"), "risk-revenue");
        MvcResult kpiResult = mockMvc.perform(get(
                "/api/reports/{id}/widgets/{widgetId}/kpi", reportId, "risk-revenue")
                .header("X-Workspace-Id", workspace.getId())
                .session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.available").value(true))
            .andExpect(jsonPath("$.reason").doesNotExist())
            .andExpect(jsonPath("$.total").value(75.25))
            .andExpect(jsonPath("$.unit").value("USD"))
            .andReturn();

        JsonNode kpi = objectMapper.readTree(kpiResult.getResponse().getContentAsString());
        assertNotNull(generatedWidget);
        assertEquals(generatedWidget.get("total"), kpi.get("total"));
        assertEquals(generatedWidget.get("unit"), kpi.get("unit"));
        verify(dealRiskService).assessWorkspace(workspace.getId());
        verify(dealRiskService).assessBoundedWorkspace(workspace.getId());
    }

    @Test
    void atRiskRevenueKpiIsUnavailableAboveCandidateCeiling() throws Exception {
        RequestContextHolder.resetRequestAttributes();
        Workspace workspace = newWorkspaceInOrg(newOrganization().getId());
        User member = newReportMember(workspace, "REPORT_CREATE", "REPORT_READ");
        MockHttpSession session = login(member.getUsername());
        int pipeline = insertPipeline(workspace.getId(), "At-risk input ceiling");
        int stage = insertStage(workspace.getId(), pipeline, "Open");
        insertRiskCandidates(workspace.getId(), pipeline, stage, 1_001);
        int reportId = createReport(session, workspace, commercialReportBody(List.of(
                new CommercialWidget(
                        "risk-revenue", "deals", "at_risk_revenue", "risk", "table"))));

        MvcResult result = mockMvc.perform(get(
                "/api/reports/{id}/widgets/{widgetId}/kpi", reportId, "risk-revenue")
                .header("X-Workspace-Id", workspace.getId())
                .session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.available").value(false))
            .andExpect(jsonPath("$.reason").value("input_limit_exceeded"))
            .andExpect(jsonPath("$.total").doesNotExist())
            .andExpect(jsonPath("$.unit").doesNotExist())
            .andReturn();

        JsonNode kpi = objectMapper.readTree(result.getResponse().getContentAsString());
        assertTrue(kpi.get("unit") == null || kpi.get("unit").isNull());
        verify(dealRiskService).assessBoundedWorkspace(workspace.getId());
        verify(dealRiskService, never()).assessWorkspace(workspace.getId());
    }

    @Test
    void widgetKpiSkipsDealRiskInputsForUnrequestedSiblingAndMatchesGeneratedWidget() throws Exception {
        RequestContextHolder.resetRequestAttributes();
        Workspace workspace = newWorkspaceInOrg(newOrganization().getId());
        User member = newReportMember(workspace, "REPORT_CREATE", "REPORT_READ");
        MockHttpSession session = login(member.getUsername());
        int pipeline = insertPipeline(workspace.getId(), "KPI input scope");
        int stage = insertStage(workspace.getId(), pipeline, "Open");
        int company = insertCompany(workspace.getId(), "KPI input company");
        insertDeal(workspace.getId(), pipeline, stage, company, "Counted deal", "100.00", false);
        int reportId = createReport(session, workspace, commercialReportBody(List.of(
                new CommercialWidget("deal-count", "deals", "count", "none", "kpi"),
                new CommercialWidget(
                        "risk-revenue", "deals", "at_risk_revenue", "risk", "table"))));

        MvcResult kpiResult = mockMvc.perform(get(
                "/api/reports/{id}/widgets/{widgetId}/kpi", reportId, "deal-count")
                .header("X-Workspace-Id", workspace.getId())
                .session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.widgetId").value("deal-count"))
            .andExpect(jsonPath("$.measure").value("count"))
            .andExpect(jsonPath("$.total").value(1))
            .andReturn();

        verify(dealRiskService, never()).assessWorkspace(workspace.getId());

        JsonNode generatedWidget = findWidget(
                generateDocument(session, workspace, reportId).get("widgets"), "deal-count");
        JsonNode kpi = objectMapper.readTree(kpiResult.getResponse().getContentAsString());
        assertNotNull(generatedWidget);
        for (String field : List.of("total", "priorTotal", "changePercent", "unit")) {
            assertEquals(generatedWidget.get(field), kpi.get(field), field);
        }
        verify(dealRiskService).assessWorkspace(workspace.getId());
    }

    @Test
    void ordinaryWidgetKpiSkipsNetworkInputsForUnrequestedSibling() throws Exception {
        RequestContextHolder.resetRequestAttributes();
        Workspace workspace = newWorkspaceInOrg(newOrganization().getId());
        User member = newReportMember(workspace, "REPORT_CREATE", "REPORT_READ");
        MockHttpSession session = login(member.getUsername());
        int pipeline = insertPipeline(workspace.getId(), "KPI network input scope");
        int stage = insertStage(workspace.getId(), pipeline, "Open");
        int company = insertCompany(workspace.getId(), "KPI network input company");
        insertDeal(workspace.getId(), pipeline, stage, company, "Counted network deal", "100.00", false);
        int reportId = createReport(session, workspace, commercialReportBody(List.of(
                new CommercialWidget("deal-count", "deals", "count", "none", "kpi"),
                new CommercialWidget(
                        "reachable-pipeline", "companies", "warm_intro_opportunity_value", "none", "kpi"),
                new CommercialWidget(
                        "reverse-intro", "relationships", "reverse_intro_weighted_opportunities", "none", "kpi"))));

        mockMvc.perform(get("/api/reports/{id}/widgets/{widgetId}/kpi", reportId, "deal-count")
                .header("X-Workspace-Id", workspace.getId())
                .session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1));

        verifyNoInteractions(reportNetworkService);
        verify(personEdgeMapper, never()).getEdgesForNetworkReport(
                eq(workspace.getId()), anyString(), anyInt());
        verify(personEdgeMapper, never()).getEdgesForReverseIntroReport(
                eq(workspace.getId()), anyString(), anyList(), anyInt());
    }

    @Test
    void warmIntroKpiUsesFullReportInputsAndMatchesGeneratedWidget() throws Exception {
        RequestContextHolder.resetRequestAttributes();
        Workspace workspace = newWorkspaceInOrg(newOrganization().getId());
        User member = newReportMember(workspace, "REPORT_CREATE", "REPORT_READ");
        MockHttpSession session = login(member.getUsername());
        int reportId = createReport(session, workspace, NETWORK_REPORT_BODY);

        MvcResult kpiResult = mockMvc.perform(get("/api/reports/{id}/widgets/{widgetId}/kpi",
                reportId, "reachable-pipeline")
                .header("X-Workspace-Id", workspace.getId())
                .session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.widgetId").value("reachable-pipeline"))
            .andReturn();

        verify(personEdgeMapper).getEdgesForNetworkReport(
                eq(workspace.getId()), anyString(), anyInt());
        JsonNode generated = generateDocument(session, workspace, reportId);
        JsonNode kpi = objectMapper.readTree(kpiResult.getResponse().getContentAsString());
        JsonNode generatedWidget = findWidget(generated.get("widgets"), "reachable-pipeline");
        assertNotNull(generatedWidget);
        for (String field : List.of("total", "priorTotal", "changePercent", "unit")) {
            assertEquals(generatedWidget.get(field), kpi.get(field), field);
        }
    }

    @Test
    void reverseIntroKpiUsesFullReportInputsAndMatchesGeneratedWidget() throws Exception {
        RequestContextHolder.resetRequestAttributes();
        Workspace workspace = newWorkspaceInOrg(newOrganization().getId());
        User member = newReportMember(workspace, "REPORT_CREATE", "REPORT_READ");
        MockHttpSession session = login(member.getUsername());
        int reportId = createReport(session, workspace, NETWORK_REPORT_BODY);

        MvcResult kpiResult = mockMvc.perform(get("/api/reports/{id}/widgets/{widgetId}/kpi",
                reportId, "reverse-intro-value")
                .header("X-Workspace-Id", workspace.getId())
                .session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.widgetId").value("reverse-intro-value"))
            .andReturn();

        verify(personEdgeMapper).getEdgesForNetworkReport(
                eq(workspace.getId()), anyString(), anyInt());
        verify(personEdgeMapper, never()).getEdgesForReverseIntroReport(
                eq(workspace.getId()), anyString(), anyList(), anyInt());

        JsonNode generated = generateDocument(session, workspace, reportId);
        JsonNode kpi = objectMapper.readTree(kpiResult.getResponse().getContentAsString());
        JsonNode generatedWidget = findWidget(generated.get("widgets"), "reverse-intro-value");
        assertNotNull(generatedWidget);
        for (String field : List.of("total", "priorTotal", "changePercent", "unit")) {
            assertEquals(generatedWidget.get(field), kpi.get(field), field);
        }
    }

    @Test
    void widgetKpiAppliesFullDefinitionPermissionsAndValidation() throws Exception {
        RequestContextHolder.resetRequestAttributes();
        Workspace workspace = newWorkspaceInOrg(newOrganization().getId());
        User member = newReportMember(workspace, "REPORT_CREATE", "REPORT_READ", "GOAL_READ");
        MockHttpSession session = login(member.getUsername());
        String mixedBody = ATTAINMENT_BODY.replaceFirst(
                "\"measure\": \"attainment\"", "\"measure\": \"count\"");
        int reportId = createReport(session, workspace, mixedBody);

        mockMvc.perform(get("/api/reports/{id}/widgets/{widgetId}/kpi", reportId, "owner-attainment")
                .param("start", "2026-07-15")
                .param("end", "2026-08-14")
                .header("X-Workspace-Id", workspace.getId())
                .session(session))
            .andExpect(status().isBadRequest());

        assignCustomRole(workspace, member, "REPORT_READ");

        mockMvc.perform(get("/api/reports/{id}/widgets/{widgetId}/kpi", reportId, "owner-attainment")
                .header("X-Workspace-Id", workspace.getId())
                .session(session))
            .andExpect(status().isForbidden());

        assignCustomRole(workspace, member);

        mockMvc.perform(get("/api/reports/{id}/widgets/{widgetId}/kpi", reportId, "owner-attainment")
                .header("X-Workspace-Id", workspace.getId())
                .session(session))
            .andExpect(status().isForbidden());
    }

    @Test
    void widgetKpiRejectsUnknownForeignAndCorruptSiblingWidgets() throws Exception {
        RequestContextHolder.resetRequestAttributes();
        Organization organization = newOrganization();
        Workspace workspace = newWorkspaceInOrg(organization.getId());
        User member = newReportMember(workspace, "REPORT_CREATE", "REPORT_READ");
        MockHttpSession session = login(member.getUsername());
        int reportId = createReport(session, workspace, commercialReportBody(List.of(
                new CommercialWidget("deal-count", "deals", "count", "none", "kpi"),
                new CommercialWidget(
                        "open-pipeline", "deals", "open_pipeline_value", "none", "kpi"))));
        int otherReportId = createReport(session, workspace, REPORT_BODY.replace(
                "activity-total", "other-widget"));

        mockMvc.perform(get("/api/reports/{id}/widgets/{widgetId}/kpi", reportId, "unknown-widget")
                .header("X-Workspace-Id", workspace.getId())
                .session(session))
            .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/reports/{id}/widgets/{widgetId}/kpi", reportId, "other-widget")
                .header("X-Workspace-Id", workspace.getId())
                .session(session))
            .andExpect(status().isNotFound());

        ReportDefinition definition = reportMapper.getDefinition(workspace.getId(), reportId);
        assertNotNull(definition);
        JsonNode config = objectMapper.readTree(definition.getConfigJson());
        JsonNode sibling = findConfigWidget(config.get("widgets"), "open-pipeline");
        assertTrue(sibling instanceof ObjectNode);
        ((ObjectNode) sibling).put("measure", "future_sensitive");
        String corruptConfig = objectMapper.writeValueAsString(config);
        definition.setConfigJson(corruptConfig);
        assertEquals(1, reportMapper.updateDefinition(definition));

        String persistedConfig = jdbcTemplate.queryForObject(
                "SELECT config_json FROM report_definition WHERE workspace_id = ? AND id = ?",
                String.class,
                workspace.getId(),
                reportId);
        assertNotNull(persistedConfig);
        JsonNode persistedConfigJson = objectMapper.readTree(persistedConfig);
        assertEquals(config, persistedConfigJson);
        assertEquals(
                "future_sensitive",
                findConfigWidget(
                        persistedConfigJson.get("widgets"),
                        "open-pipeline").get("measure").asText());

        mockMvc.perform(post("/api/reports/{id}/generate", reportId)
                .header("X-Workspace-Id", workspace.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .session(session)
                .with(csrf().asHeader()))
            .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/reports/{id}/widgets/{widgetId}/kpi", reportId, "deal-count")
                .header("X-Workspace-Id", workspace.getId())
                .session(session))
            .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/reports/{id}/widgets/{widgetId}/kpi", otherReportId, "other-widget")
                .header("X-Workspace-Id", workspace.getId())
                .session(session))
            .andExpect(status().isOk());

        Workspace siblingWorkspace = newWorkspaceInOrg(organization.getId());
        Workspace foreign = newWorkspaceInOrg(newOrganization().getId());
        addReportMember(siblingWorkspace, member, "REPORT_READ");
        addReportMember(foreign, member, "REPORT_READ");
        for (Workspace unauthorized : List.of(siblingWorkspace, foreign)) {
            mockMvc.perform(get("/api/reports/{id}/widgets/{widgetId}/kpi", reportId, "deal-count")
                    .header("X-Workspace-Id", unauthorized.getId())
                    .session(session))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.total").doesNotExist());
        }
    }

    private int createReport(MockHttpSession session, Workspace workspace, String body) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/reports")
                .header("X-Workspace-Id", workspace.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .session(session)
                .with(csrf().asHeader()))
            .andExpect(status().isCreated())
            .andReturn();
        return responseId(result);
    }

    private JsonNode generateDocument(
            MockHttpSession session, Workspace workspace, int reportId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/reports/{id}/generate", reportId)
                .header("X-Workspace-Id", workspace.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .session(session)
                .with(csrf().asHeader()))
            .andExpect(status().isOk())
            .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private int responseId(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asInt();
    }

    private static JsonNode findWidget(JsonNode widgets, String id) {
        for (JsonNode widget : widgets) {
            if (id.equals(widget.get("widgetId").asText())) {
                return widget;
            }
        }
        return null;
    }

    private static JsonNode findConfigWidget(JsonNode widgets, String id) {
        for (JsonNode widget : widgets) {
            if (id.equals(widget.get("id").asText())) {
                return widget;
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

    private static void assertNoScalarTotal(JsonNode widget, String widgetId) {
        assertNotNull(widget, widgetId);
        assertTrue(widget.get("total") == null || widget.get("total").isNull(), widgetId);
    }

    private static void assertDecimal(String expected, BigDecimal actual) {
        assertNotNull(actual);
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
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
        jdbcTemplate.update(
                "INSERT INTO stage (workspace_id, name, pipeline_id, position) VALUES (?, ?, ?, ?)",
                workspaceId, name, pipelineId, 1);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM stage WHERE workspace_id = ? AND pipeline_id = ? AND position = ?",
                Integer.class, workspaceId, pipelineId, 1);
    }

    private int insertCompany(int workspaceId, String name) {
        jdbcTemplate.update(
                "INSERT INTO company (workspace_id, name, created_at) VALUES (?, ?, ?)",
                workspaceId, name, "2026-01-01 00:00:00");
        return jdbcTemplate.queryForObject(
                "SELECT id FROM company WHERE workspace_id = ? AND name = ?",
                Integer.class, workspaceId, name);
    }

    private int insertDeal(
            int workspaceId,
            int pipelineId,
            int stageId,
            int companyId,
            String name,
            String value,
            boolean riskExcluded) {
        jdbcTemplate.update(
                "INSERT INTO deal (workspace_id, name, value, currency, pipeline_id, stage_id, company_id, "
                        + "expected_close_date, risk_excluded, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                workspaceId, name, value, "USD", pipelineId, stageId, companyId,
                "2026-01-25", riskExcluded, "2026-01-01 00:00:00");
        return jdbcTemplate.queryForObject(
                "SELECT id FROM deal WHERE workspace_id = ? AND name = ?",
                Integer.class, workspaceId, name);
    }

    private int insertOpenDeal(
            int workspaceId,
            int pipelineId,
            int stageId,
            String name,
            String value,
            String currency,
            LocalDate expectedCloseDate) {
        jdbcTemplate.update(
                "INSERT INTO deal (workspace_id, name, value, currency, pipeline_id, stage_id, "
                        + "expected_close_date, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                workspaceId, name, value, currency, pipelineId, stageId,
                expectedCloseDate, LocalDateTime.now(clock).minusMonths(1));
        return jdbcTemplate.queryForObject(
                "SELECT id FROM deal WHERE workspace_id = ? AND name = ?",
                Integer.class, workspaceId, name);
    }

    private void insertRiskCandidates(int workspaceId, int pipelineId, int stageId, int count) {
        List<Object[]> candidates = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            candidates.add(new Object[] {
                    workspaceId,
                    "Ceiling risk " + index,
                    new BigDecimal("1.00"),
                    "USD",
                    pipelineId,
                    stageId,
                    LocalDate.of(2026, 1, 10),
                    LocalDateTime.now(clock).minusMonths(1)
            });
        }
        jdbcTemplate.batchUpdate(
                "INSERT INTO deal (workspace_id, name, value, currency, pipeline_id, stage_id, "
                        + "expected_close_date, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                candidates);
    }

    private void insertWonRevenue(
            int workspaceId,
            int pipelineId,
            int stageId,
            int ownerId,
            String name,
            String actualValue,
            String currency,
            String closedAt) {
        jdbcTemplate.update(
                "INSERT INTO deal (workspace_id, name, value, actual_value, currency, pipeline_id, stage_id, "
                        + "owner_id, closed_at, won, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                workspaceId, name, actualValue, actualValue, currency, pipelineId, stageId,
                ownerId, closedAt, true, "2026-06-01 00:00:00");
    }

    private record CommercialWidget(
            String id, String dataSource, String measure, String groupBy, String chartType) {
    }

    private static String commercialReportBody(List<CommercialWidget> widgets) {
        StringBuilder widgetJson = new StringBuilder();
        StringBuilder layoutJson = new StringBuilder();
        for (int index = 0; index < widgets.size(); index++) {
            CommercialWidget widget = widgets.get(index);
            if (index > 0) {
                widgetJson.append(',');
                layoutJson.append(',');
            }
            widgetJson.append(("{\"id\": \"%s\", \"title\": null, \"dataSource\": \"%s\", "
                    + "\"measure\": \"%s\", \"groupBy\": \"%s\", \"chartType\": \"%s\"}").formatted(
                    widget.id(), widget.dataSource(), widget.measure(),
                    widget.groupBy(), widget.chartType()));
            layoutJson.append(
                    "{\"widgetId\": \"%s\", \"x\": %d, \"y\": %d, \"width\": 6, \"height\": 4}"
                            .formatted(widget.id(), index % 2 * 6, index / 2 * 4));
        }
        return """
            {
              "name": "Commercial documents",
              "description": "Quote, approval, and discount metrics",
              "cadence": "custom",
              "templateKey": null,
              "config": {
                "widgets": [%s],
                "filters": {
                  "pipelineIds": null,
                  "ownerIds": null,
                  "statuses": null,
                  "tagIds": null,
                  "warmthBands": null
                },
                "range": {"start": "2026-01-01", "end": "2026-01-31"},
                "bucket": "day",
                "layout": [%s]
              }
            }
            """.formatted(widgetJson, layoutJson);
    }

    private Workspace newWorkspaceInOrg(int organizationId) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Workspace workspace = new Workspace();
        workspace.setName("KPI Workspace " + suffix);
        workspace.setSlug("report-kpi-" + suffix);
        workspace.setOrgId(organizationId);
        workspaceMapper.insert(workspace);
        return workspace;
    }

    private Organization newOrganization() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Organization organization = new Organization();
        organization.setName("Report KPI Org " + suffix);
        organization.setSlug("report-kpi-org-" + suffix);
        organizationMapper.insert(organization);
        return organization;
    }

    private User newReportMember(Workspace workspace, String... permissions) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User user = new User();
        user.setUsername("report_kpi_" + suffix);
        user.setDisplayName("Report KPI " + suffix);
        user.setEmail("report-kpi-" + suffix + "@example.com");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setTimezone("UTC");
        userMapper.insert(user);
        addReportMember(workspace, user, permissions);
        return user;
    }

    private void addReportMember(Workspace workspace, User user, String... permissions) {
        workspaceMapper.addMember(workspace.getId(), user.getId(), "member");
        assignCustomRole(workspace, user, permissions);
    }

    private void assignCustomRole(Workspace workspace, User user, String... permissions) {
        WorkspaceRole role = new WorkspaceRole();
        role.setWorkspaceId(workspace.getId());
        role.setName("Report KPI " + UUID.randomUUID().toString().substring(0, 8));
        roleMapper.insertRole(role);
        if (permissions.length > 0) {
            roleMapper.insertPermissions(workspace.getId(), role.getId(), List.of(permissions));
        }
        workspaceMapper.setMemberCustomRole(workspace.getId(), user.getId(), role.getId());
    }

    private MockHttpSession login(String username) throws Exception {
        String body = "{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}";
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andReturn();
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertNotNull(session, "login did not establish a report KPI test session");
        return session;
    }

    @TestConfiguration
    static class FixedClockConfiguration {

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        }
    }
}
