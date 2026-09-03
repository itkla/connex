package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.beans.ReportDefinition;
import ooo.klae.connex.backend.dto.ReportConfig;
import ooo.klae.connex.backend.dto.ReportDefinitionDto;
import ooo.klae.connex.backend.dto.ReportDocumentDto;
import ooo.klae.connex.backend.dto.ReportFilters;
import ooo.klae.connex.backend.dto.ReportLayoutItem;
import ooo.klae.connex.backend.dto.ReportWidgetConfig;
import ooo.klae.connex.backend.dto.ReportWidgetDataDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.tenant.Permission;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

class ReportPermissionPolicyTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ReportPermissionPolicy policy = new ReportPermissionPolicy(objectMapper);

    @Test
    void permissionMappingExhaustsCanonicalMeasureCatalog() {
        assertEquals(ReportService.supportedMeasures(), ReportPermissionPolicy.mappedMeasures());
    }

    @Test
    void persistedDefinitionRequiresReportReadForOrdinaryContent() throws JacksonException {
        ReportDefinition definition = definition(config("count"));

        assertEquals(Set.of(Permission.REPORT_READ), policy.requiredFor(definition));
    }

    @Test
    void employmentContentRequiresOnlyReportRead() throws JacksonException {
        for (String measure : List.of("employment_departure_count", "employment_arrival_count")) {
            ReportConfig config = config("people", measure, "person");

            assertEquals(Set.of(Permission.REPORT_READ), policy.requiredFor(definition(config)));
            assertEquals(Set.of(Permission.REPORT_READ), policy.requiredFor(document(config)));
        }
    }

    @Test
    void commercialMeasuresRequireOnlyReportRead() throws JacksonException {
        List<ReportWidgetConfig> widgets = List.of(
                new ReportWidgetConfig(
                        "quotes", "Quotes", "documents", "quote_count", "date", "line-area"),
                new ReportWidgetConfig(
                        "issue-rate", "Issue rate", "documents", "quote_issue_rate", "none", "kpi"),
                new ReportWidgetConfig(
                        "doc-to-win", "Doc to win", "documents", "document_to_win_rate", "none", "kpi"),
                new ReportWidgetConfig(
                        "decisions", "Decisions", "documents", "approval_decision_count", "owner", "bar"),
                new ReportWidgetConfig(
                        "cycle", "Cycle", "documents", "approval_cycle_days", "none", "kpi"),
                new ReportWidgetConfig(
                        "won-discount", "Won discount", "deals",
                        "effective_discount_percent", "none", "bar"),
                new ReportWidgetConfig(
                        "open-discount", "Open discount", "deals",
                        "open_discount_percent", "pipeline", "table"));
        List<ReportLayoutItem> layout = new java.util.ArrayList<>();
        for (int index = 0; index < widgets.size(); index++) {
            layout.add(new ReportLayoutItem(
                    widgets.get(index).id(), index % 2 * 6, index / 2 * 4, 6, 4));
        }
        ReportConfig config = new ReportConfig(
                widgets, new ReportFilters(null, null, null, null, null), null, "month", layout);

        assertEquals(Set.of(Permission.REPORT_READ), policy.requiredFor(definition(config)));
        assertEquals(Set.of(Permission.REPORT_READ), policy.requiredFor(document(config)));
    }

    @Test
    void networkContentRequiresReportReadAndValidatesCanonicalGroups() throws JacksonException {
        List<ReportWidgetConfig> widgets = List.of(
                new ReportWidgetConfig(
                        "paths", "Paths", "companies", "warm_intro_opportunity_value", "company", "table"),
                new ReportWidgetConfig(
                        "connectors", "Connectors", "companies",
                        "warm_intro_reachable_account_count", "connector", "bar"),
                new ReportWidgetConfig(
                        "reverse", "Reverse", "relationships",
                        "reverse_intro_weighted_opportunities", "pair", "table"));
        ReportConfig config = new ReportConfig(
                widgets,
                new ReportFilters(null, null, null, null, null),
                null,
                "month",
                List.of(
                        new ReportLayoutItem("paths", 0, 0, 6, 4),
                        new ReportLayoutItem("connectors", 6, 0, 6, 4),
                        new ReportLayoutItem("reverse", 0, 4, 6, 4)));

        assertEquals(Set.of(Permission.REPORT_READ), policy.requiredFor(definition(config)));
    }

    @Test
    void persistedDefinitionAndGeneratedDocumentRequireGoalReadForAttainment() throws JacksonException {
        ReportConfig config = config("attainment");

        assertEquals(
                Set.of(Permission.REPORT_READ, Permission.GOAL_READ),
                policy.requiredFor(definition(config)));
        assertEquals(
                Set.of(Permission.REPORT_READ, Permission.GOAL_READ),
                policy.requiredFor(document(config)));
    }

    @Test
    void persistedDefinitionRequiresGoalReadWhenAttainmentHasAnOrdinarySibling() throws JacksonException {
        List<ReportWidgetConfig> widgets = List.of(
                new ReportWidgetConfig("deals", "Deals", "deals", "count", "none", "kpi"),
                new ReportWidgetConfig(
                        "attainment", "Attainment", "deals", "attainment", "none", "kpi"));
        ReportConfig config = new ReportConfig(
                widgets,
                new ReportFilters(null, null, null, null, null),
                null,
                "month",
                List.of(
                        new ReportLayoutItem("deals", 0, 0, 6, 4),
                        new ReportLayoutItem("attainment", 6, 0, 6, 4)));

        assertEquals(
                Set.of(Permission.REPORT_READ, Permission.GOAL_READ),
                policy.requiredFor(definition(config)));
    }

    @Test
    void malformedPersistedConfigurationsFailClosed() {
        ReportDefinition definition = new ReportDefinition();
        definition.setCadence("monthly");

        definition.setConfigJson("{");
        assertThrows(BadRequestException.class, () -> policy.requiredFor(definition));
        definition.setConfigJson("null");
        assertThrows(BadRequestException.class, () -> policy.requiredFor(definition));
        definition.setConfigJson("{\"widgets\":null}");
        assertThrows(BadRequestException.class, () -> policy.requiredFor(definition));
    }

    @Test
    void incompleteGeneratedDocumentConfigurationsFailClosed() {
        ReportConfig empty = new ReportConfig(
                List.of(), new ReportFilters(null, null, null, null, null), null, "day", List.of());
        ReportConfig missingMeasure = new ReportConfig(
                List.of(new ReportWidgetConfig("summary", "Summary", "deals", null, "none", "kpi")),
                new ReportFilters(null, null, null, null, null),
                null,
                "day",
                List.of(new ReportLayoutItem("summary", 0, 0, 6, 4)));

        assertThrows(BadRequestException.class, () -> policy.requiredFor(document(empty)));
        assertThrows(BadRequestException.class, () -> policy.requiredFor(document(missingMeasure)));
    }

    @Test
    void unsupportedAndNonNormalizedMeasuresFailClosed() throws JacksonException {
        assertThrows(BadRequestException.class, () -> policy.requiredFor(definition(config("future_sensitive"))));
        assertThrows(BadRequestException.class, () -> policy.requiredFor(definition(config("attainment "))));
    }

    @Test
    void networkMeasuresRejectUnrelatedGroups() throws JacksonException {
        assertThrows(BadRequestException.class, () -> policy.requiredFor(definition(
                config("companies", "count", "connector"))));
        assertThrows(BadRequestException.class, () -> policy.requiredFor(definition(
                config("companies", "warm_intro_reachable_account_count", "company"))));
        assertThrows(BadRequestException.class, () -> policy.requiredFor(definition(
                config("relationships", "reverse_intro_weighted_opportunities", "trend"))));
    }

    @Test
    void generatedDocumentMustMatchItsValidatedDefinition() {
        ReportDocumentDto ordinary = document(config("count"));
        ReportWidgetDataDto mismatched = new ReportWidgetDataDto(
                "summary", "Summary", "kpi", "deals", "attainment", "none",
                "count", null, null, null, List.of());
        ReportDocumentDto document = new ReportDocumentDto(
                ordinary.definition(), ordinary.periodStart(), ordinary.periodEnd(),
                ordinary.priorPeriodStart(), ordinary.priorPeriodEnd(), ordinary.narrative(),
                List.of(mismatched), ordinary.appendix(), ordinary.citations(), ordinary.generatedAt());

        assertThrows(BadRequestException.class, () -> policy.requiredFor(document));
    }

    private ReportDefinition definition(ReportConfig config) throws JacksonException {
        ReportDefinition definition = new ReportDefinition();
        definition.setId(33);
        definition.setWorkspaceId(11);
        definition.setCadence("monthly");
        definition.setConfigJson(objectMapper.writeValueAsString(config));
        return definition;
    }

    private static ReportDocumentDto document(ReportConfig config) {
        ReportDefinitionDto definition = new ReportDefinitionDto(
                33, "Report", "Permission test", "monthly", null,
                config, 44, "2026-07-01 00:00:00", "2026-07-01 00:00:00");
        List<ReportWidgetDataDto> widgets = config.widgets() == null
                ? null
                : config.widgets().stream()
                        .map(widget -> widget == null
                                ? null
                                : new ReportWidgetDataDto(
                                        widget.id(), widget.title(), widget.chartType(), widget.dataSource(),
                                        widget.measure(), widget.groupBy(), "count", null, null, null, List.of()))
                        .toList();
        return new ReportDocumentDto(
                definition,
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 7, 31),
                LocalDate.of(2026, 6, 1),
                LocalDate.of(2026, 6, 30),
                null,
                widgets,
                List.of(),
                List.of(),
                "2026-07-14T09:00:00Z");
    }

    private static ReportConfig config(String measure) {
        return config("deals", measure, "none");
    }

    private static ReportConfig config(String source, String measure, String group) {
        ReportWidgetConfig widget = new ReportWidgetConfig(
                "summary", "Summary", source, measure, group, "kpi");
        return new ReportConfig(
                List.of(widget),
                new ReportFilters(null, null, null, null, null),
                null,
                "day",
                List.of(new ReportLayoutItem("summary", 0, 0, 6, 4)));
    }
}
