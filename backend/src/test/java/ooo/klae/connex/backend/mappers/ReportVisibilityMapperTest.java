package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import ooo.klae.connex.backend.dto.ReportAggregateQuery;
import ooo.klae.connex.backend.dto.ReportAggregateRow;
import ooo.klae.connex.backend.dto.ReportNetworkAccountRow;
import ooo.klae.connex.backend.support.RecordVisibilityPlaneFixture;
import ooo.klae.connex.backend.support.RecordVisibilityPlaneFixture.Records;
import ooo.klae.connex.backend.warmth.RelationshipWarmthModel.SqlParameters;

/** Pins report populations, labels, temporal boundaries and limits against frozen pre-#485 SQL. */
class ReportVisibilityMapperTest {
    private static final LocalDateTime END = LocalDateTime.of(2026, 10, 1, 0, 0);
    private RecordVisibilityPlaneFixture fixture;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() throws Exception {
        fixture = new RecordVisibilityPlaneFixture(Records.REPORTS, false);
        jdbc = fixture.controlJdbc;
        fixture.installTenant();
    }

    @AfterEach
    void tearDown() {
        if (fixture != null) {
            fixture.close();
        }
    }

    @Test
    void coveragePersonCeilingRejectsUnsharedAndForgedForeignEvidence() throws Exception {
        fixture.addReportTouch(fixture.personIds.getFirst(), END.minusSeconds(1));
        int touch = fixture.addReportTouch(fixture.reportEvidencePersonIds.getFirst(), END.minusSeconds(1));
        for (int index = 0; index < 6; index++) {
            jdbc.update("UPDATE activity SET person_id = ? WHERE id = ?", fixture.reportEvidencePersonIds.get(index), touch);
            boolean visible = List.of(0, 1, 4, 5).contains(index);
            for (String measure : List.of("coverage_gap_count", "coverage_gap_open_pipeline_value")) {
                List<ReportAggregateRow> rows = aggregate("aggregateCoverageGaps", measure, "company", END);
                assertEquals(!visible, rows.stream().anyMatch(row -> row.groupLabel().endsWith("A Owned")),
                    measure + " person case " + index);
            }
        }
    }

    @Test
    void coverageCompanyCeilingRejectsUnsharedAndForgedForeignCompanies() {
        for (String measure : List.of("coverage_gap_count", "coverage_gap_open_pipeline_value")) {
            List<ReportAggregateRow> rows = aggregate("aggregateCoverageGaps", measure, "company", END);
            assertEquals(3, rows.size());
            assertEquals(List.of("A Owned", "B Shared", "C Winding"), rows.stream()
                .map(row -> row.groupLabel().replace("USD · ", "")).sorted().toList());
            assertFalse(rows.toString().contains("Hidden"));
            assertFalse(rows.toString().contains("Archived"));
        }
        assertDecimal("3", total(aggregate("aggregateCoverageGaps", "coverage_gap_count", "none", END)));
        assertDecimal("80", total(aggregate("aggregateCoverageGaps", "coverage_gap_open_pipeline_value", "none", END)));
    }

    @Test
    void singleThreadedDealsKeepCountsAndMaskInvisibleCompanies() {
        List<ReportAggregateRow> counts = aggregate("aggregateSingleThreadedDeals", "single_threaded_deal_count", "company", END);
        assertEquals(List.of("Unassigned", "A Owned", "B Shared", "C Winding"),
            counts.stream().map(ReportAggregateRow::groupLabel).toList());
        assertDecimal("3", counts.getFirst().value());
        assertDecimal("6", total(counts));
        List<ReportAggregateRow> values = aggregate("aggregateSingleThreadedDeals", "single_threaded_deal_value", "company", END);
        assertDecimal("130", values.stream().filter(row -> row.groupKey().equals("USD:unassigned"))
            .findFirst().orElseThrow().value());
        assertDecimal("210", total(values));
        assertFalse(counts.toString().contains("Hidden"));
        assertFalse(values.toString().contains("Archived"));
        for (String group : List.of("none", "deal")) {
            assertDecimal("6", total(aggregate("aggregateSingleThreadedDeals", "single_threaded_deal_count", group, END)));
            assertDecimal("210", total(aggregate("aggregateSingleThreadedDeals", "single_threaded_deal_value", group, END)));
        }
    }

    @Test
    void networkAccountsApplyVisibilityBeforeOrderingAndLimit() throws Exception {
        fixture.addReportDeal(fixture.companyIds.getFirst(), "Negative pipeline", "-7", "USD");
        fixture.addReportDeal(fixture.companyIds.getFirst(), "Other currency", "7", "JPY");
        List<ReportNetworkAccountRow> complete = network(100);
        assertEquals(List.of("JPY", "USD", "USD", "USD"), complete.stream().map(ReportNetworkAccountRow::currency).toList());
        assertEquals(List.of("A Owned", "A Owned", "B Shared", "C Winding"),
            complete.stream().map(ReportNetworkAccountRow::companyName).toList());
        assertDecimal("7", complete.getFirst().accountValue());
        assertDecimal("10", complete.get(1).accountValue());
        assertFalse(complete.toString().contains("Hidden"));
        assertFalse(complete.toString().contains("Archived"));
        for (int limit : List.of(0, 1, 2, 4, 100)) {
            assertEquals(complete.stream().limit(limit).toList(), network(limit));
        }
        jdbc.update("UPDATE deal SET workspace_id = ? WHERE id = ?", fixture.workspaceIds.get(2), fixture.dealIds.get(1));
        assertFalse(network(100).stream().anyMatch(row -> row.companyId() == fixture.companyIds.get(1)));
    }

    @Test
    void historicalPersonIdsKeepInclusiveCreationAndShareCutoffs() {
        historicalCutoffs(true);
    }

    @Test
    void historicalCompanyIdsKeepInclusiveCreationAndShareCutoffs() {
        historicalCutoffs(false);
    }

    @Test
    void historicalVisibilityUsesCurrentOrganizationMembership() {
        for (boolean people : List.of(true, false)) {
            int target = (people ? fixture.personIds : fixture.companyIds).get(1);
            assertTrue(historical(people, END).contains(target));
            jdbc.update("UPDATE workspace SET org_id = ? WHERE id = ?", fixture.foreignOrgId, fixture.workspaceIds.get(1));
            assertFalse(historical(people, END).contains(target));
            jdbc.update("UPDATE workspace SET org_id = ? WHERE id = ?", fixture.orgId, fixture.workspaceIds.get(1));
            assertTrue(historical(people, END).contains(target));
            String share = people ? "person_share" : "company_share";
            String column = people ? "person_id" : "company_id";
            jdbc.update("DELETE FROM " + share + " WHERE " + column + " = ? AND workspace_id = ?", target, fixture.workspaceId);
            assertFalse(historical(people, END).contains(target));
        }
    }

    @Test
    void workspaceLifecycleAndRecordRestrictionsKeepLegacyParity() throws Exception {
        jdbc.update("UPDATE organization SET lifecycle_state = 'tearing_down' WHERE id = ?", fixture.orgId);
        assertTrue(fixture.scopeAccess.getForWorkspace(fixture.workspaceId).workspaceIds().contains(fixture.workspaceIds.get(4)));
        coverageCompanyCeilingRejectsUnsharedAndForgedForeignCompanies();
        singleThreadedDealsKeepCountsAndMaskInvisibleCompanies();
        assertTrue(network(100).stream().anyMatch(row -> row.companyId() == fixture.companyIds.get(4)));
        fixture.addReportTouch(fixture.personIds.getFirst(), END.minusSeconds(1));
        int evidence = fixture.reportEvidencePersonIds.get(1);
        fixture.addReportTouch(evidence, END.minusSeconds(1));
        jdbc.update("UPDATE person SET provision_ceased_at = '2026-08-03' WHERE id = ?", evidence);
        assertFalse(aggregate("aggregateCoverageGaps", "coverage_gap_count", "company", END).stream()
            .anyMatch(row -> row.groupLabel().equals("A Owned")));
        jdbc.update("UPDATE person SET archived_at = '2026-08-03' WHERE id = ?", evidence);
        assertTrue(aggregate("aggregateCoverageGaps", "coverage_gap_count", "company", END).stream()
            .anyMatch(row -> row.groupLabel().equals("A Owned")));
        jdbc.update("UPDATE person SET archived_at = NULL, suspended_at = '2026-08-03' WHERE id = ?", evidence);
        assertTrue(aggregate("aggregateCoverageGaps", "coverage_gap_count", "company", END).stream()
            .anyMatch(row -> row.groupLabel().equals("A Owned")));
        jdbc.update("UPDATE person SET archived_at = '2026-08-03', suspended_at = '2026-08-03', "
            + "provision_ceased_at = '2026-08-03' WHERE id = ?", fixture.personIds.getFirst());
        assertTrue(historical(true, END).contains(fixture.personIds.getFirst()));
        assertTrue(historical(false, END).contains(fixture.companyIds.get(5)));
        assertDecimal("5", total(aggregate("aggregateSingleThreadedDeals", "single_threaded_deal_count", "none", END)));
        jdbc.update("UPDATE person SET provision_ceased_at = '2026-08-03' WHERE id = ?", fixture.personIds.get(4));
        assertDecimal("5", total(aggregate("aggregateSingleThreadedDeals", "single_threaded_deal_count", "none", END)));
    }

    @Test
    void coverageCutoffsAndPriorCurrentPeriodsMatchFrozenSql() throws Exception {
        fixture.addReportTouch(fixture.personIds.getFirst(), END.minusSeconds(1));
        int evidence = fixture.reportEvidencePersonIds.get(1);
        int touch = fixture.addReportTouch(evidence, END.minusSeconds(1));
        for (long seconds : List.of(-1L, 0L, 1L)) {
            jdbc.update("UPDATE person_share SET created_at = ? WHERE person_id = ? AND workspace_id = ?",
                END.plusSeconds(seconds), evidence, fixture.workspaceId);
            List<ReportAggregateRow> rows = aggregate("aggregateCoverageGaps", "coverage_gap_count", "company", END);
            assertEquals(seconds >= 0, rows.stream().anyMatch(row -> row.groupLabel().equals("A Owned")));
        }
        jdbc.update("UPDATE person_share SET created_at = '2026-08-02' WHERE person_id = ?", evidence);
        for (long seconds : List.of(-1L, 0L, 1L)) {
            jdbc.update("UPDATE person SET created_at = ? WHERE id = ?", END.plusSeconds(seconds), evidence);
            List<ReportAggregateRow> rows = aggregate("aggregateCoverageGaps", "coverage_gap_count", "company", END);
            assertEquals(seconds >= 0, rows.stream().anyMatch(row -> row.groupLabel().equals("A Owned")));
        }
        jdbc.update("UPDATE person SET created_at = '2026-08-01' WHERE id = ?", evidence);
        jdbc.update("UPDATE activity SET timestamp = ? WHERE id = ?", END, touch);
        assertTrue(aggregate("aggregateCoverageGaps", "coverage_gap_count", "company", END).stream()
            .anyMatch(row -> row.groupLabel().equals("A Owned")));
        for (long seconds : List.of(-1L, 0L, 1L)) {
            jdbc.update("UPDATE company_share SET created_at = ? WHERE company_id = ? AND workspace_id = ?",
                END.plusSeconds(seconds), fixture.companyIds.get(1), fixture.workspaceId);
            assertEquals(seconds < 0, aggregate("aggregateCoverageGaps", "coverage_gap_count", "company", END).stream()
                .anyMatch(row -> row.groupLabel().equals("B Shared")));
            jdbc.update("UPDATE company SET created_at = ? WHERE id = ?", END.plusSeconds(seconds), fixture.companyIds.get(4));
            assertEquals(seconds < 0, aggregate("aggregateCoverageGaps", "coverage_gap_count", "company", END).stream()
                .anyMatch(row -> row.groupLabel().equals("C Winding")));
        }
        jdbc.update("UPDATE company SET created_at = '2026-08-01' WHERE id = ?", fixture.companyIds.get(4));
        jdbc.update("UPDATE company_share SET created_at = ? WHERE company_id = ? AND workspace_id = ?",
            END.minusDays(1), fixture.companyIds.get(1), fixture.workspaceId);
        jdbc.update("UPDATE person_share SET created_at = ? WHERE person_id = ? AND workspace_id = ?",
            END.minusDays(1), fixture.personIds.get(1), fixture.workspaceId);
        assertDecimal("3", total(aggregate("aggregateCoverageGaps", "coverage_gap_count", "none", END)));
        assertDecimal("2", total(aggregate("aggregateCoverageGaps", "coverage_gap_count", "none", END.minusMonths(1))));
        for (boolean people : List.of(true, false)) {
            int id = (people ? fixture.personIds : fixture.companyIds).get(1);
            assertTrue(historical(people, END).contains(id));
            assertFalse(historical(people, END.minusMonths(1)).contains(id));
        }
        for (LocalDateTime end : List.of(END.minusMonths(1), END)) {
            for (boolean filtered : List.of(false, true)) {
                for (String measure : List.of("coverage_gap_count", "coverage_gap_open_pipeline_value")) {
                    for (String group : List.of("none", "company")) {
                        aggregate("aggregateCoverageGaps", fixture.reportParameters(measure, group, end, 100, filtered));
                    }
                }
                historical(true, end);
                historical(false, end);
            }
        }
    }

    @Test
    void liveCompanySharesRemainIndependentOfReportPeriodCutoffs() {
        jdbc.update("UPDATE company_share SET created_at = ? WHERE company_id = ? AND workspace_id = ?",
            END.plusMonths(1), fixture.companyIds.get(1), fixture.workspaceId);
        jdbc.update("UPDATE company SET created_at = ? WHERE id = ?", END.plusMonths(1), fixture.companyIds.get(1));
        assertTrue(network(100).stream().anyMatch(row -> row.companyId() == fixture.companyIds.get(1)));
        assertTrue(aggregate("aggregateSingleThreadedDeals", "single_threaded_deal_count", "company", END).stream()
            .anyMatch(row -> row.groupLabel().equals("B Shared")));
        assertFalse(historical(false, END).contains(fixture.companyIds.get(1)));
    }

    private void historicalCutoffs(boolean people) {
        List<Integer> ids = people ? fixture.personIds : fixture.companyIds;
        String table = people ? "person" : "company";
        String column = people ? "person_id" : "company_id";
        assertTrue(historical(people, END).containsAll(List.of(ids.get(0), ids.get(1), ids.get(4), ids.get(5))));
        assertFalse(historical(people, END).contains(ids.get(2)));
        assertFalse(historical(people, END).contains(ids.get(3)));
        for (long seconds : List.of(-1L, 0L, 1L)) {
            jdbc.update("UPDATE " + table + "_share SET created_at = ? WHERE " + column + " = ? AND workspace_id = ?",
                END.plusSeconds(seconds), ids.get(1), fixture.workspaceId);
            assertEquals(seconds <= 0, historical(people, END).contains(ids.get(1)));
        }
        jdbc.update("UPDATE " + table + "_share SET created_at = '2026-08-02' WHERE " + column + " = ?", ids.get(1));
        for (long seconds : List.of(-1L, 0L, 1L)) {
            for (int id : List.of(ids.get(0), ids.get(1))) {
                jdbc.update("UPDATE " + table + " SET created_at = ? WHERE id = ?", END.plusSeconds(seconds), id);
                assertEquals(seconds <= 0, historical(people, END).contains(id));
            }
        }
    }

    private List<Integer> historical(boolean people, LocalDateTime asOf) {
        String statement = people ? "getVisiblePersonIdsAt" : "getVisibleCompanyIdsAt";
        Map<String, Object> parameters = fixture.reportParameters("count", "none", asOf, 100);
        String scope = fixture.scopeAccess.getForWorkspace(fixture.workspaceId).workspaceIdsJson();
        List<Integer> actual = people
            ? fixture.reportMapper.getVisiblePersonIdsAt(fixture.workspaceId, scope, asOf)
            : fixture.reportMapper.getVisibleCompanyIdsAt(fixture.workspaceId, scope, asOf);
        assertEquals(fixture.legacy.selectList("ReportMapper." + statement, parameters), actual, statement);
        assertEquals(actual.stream().sorted().toList(), actual);
        return actual;
    }

    private List<ReportAggregateRow> aggregate(String statement, String measure, String group, LocalDateTime end) {
        return aggregate(statement, fixture.reportParameters(measure, group, end, 100));
    }

    private List<ReportAggregateRow> aggregate(String statement, Map<String, Object> parameters) {
        String scope = fixture.scopeAccess.getForWorkspace(fixture.workspaceId).workspaceIdsJson();
        ReportAggregateQuery query = (ReportAggregateQuery) parameters.get("query");
        List<ReportAggregateRow> actual = statement.equals("aggregateCoverageGaps")
            ? fixture.reportMapper.aggregateCoverageGaps(query, scope, (LocalDateTime) parameters.get("warmthReference"),
                (SqlParameters) parameters.get("model"))
            : fixture.reportMapper.aggregateSingleThreadedDeals(query, scope);
        assertEquals(fixture.legacy.selectList("ReportMapper." + statement, parameters), actual, statement);
        return actual;
    }

    private List<ReportNetworkAccountRow> network(int limit) {
        Map<String, Object> parameters = fixture.reportParameters("warm_intro_opportunity_value", "company", END, limit);
        List<ReportNetworkAccountRow> actual = fixture.reportMapper.getNetworkAccountValues(
            (ReportAggregateQuery) parameters.get("query"),
            fixture.scopeAccess.getForWorkspace(fixture.workspaceId).workspaceIdsJson(), limit);
        assertEquals(fixture.legacy.selectList("ReportMapper.getNetworkAccountValues", parameters), actual);
        return actual;
    }

    private static BigDecimal total(List<ReportAggregateRow> rows) {
        return rows.stream().map(ReportAggregateRow::value).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static void assertDecimal(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }
}
