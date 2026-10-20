package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.ReportAggregateQuery;
import ooo.klae.connex.backend.dto.ReportAggregateRow;
import ooo.klae.connex.backend.dto.ReportForecastAggregateRow;
import ooo.klae.connex.backend.dto.ReportOffsetSegment;

/** Real-MySQL coverage for report grouping and its schema-level alias assumptions. */
class ReportMapperTest extends AbstractMapperTest {

    @Autowired private ReportMapper reportMapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void useIsolatedWorkspace() {
        workspace = new Workspace();
        workspace.setName("Report grouping " + unique());
        workspace.setSlug("report-grouping-" + unique());
        workspaceMapper.insert(workspace);
    }

    @Test
    void migratedSchemaHasOnlyAllowlistedGroupingAliasColumns() {
        List<String> columns = jdbcTemplate.queryForList("""
                SELECT CONCAT(table_name, '.', column_name)
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND column_name IN ('unit', 'group_key', 'group_label')
                ORDER BY table_name, column_name
                """, String.class);

        assertEquals(List.of("deal_line_item.unit", "product.unit"), columns,
                "MySQL GROUP BY binds unqualified names to FROM columns before select aliases. "
                        + "A new unit, group_key or group_label column can silently change report groups; "
                        + "review ReportMapper.xml and #1313 before updating this allowlist.");
    }

    @ParameterizedTest
    @ValueSource(strings = {"none", "stage"})
    void forecastAndDealValueAggregatesKeepCurrenciesSeparate(String groupBy) {
        var pipeline = newPipeline();
        var stage = newStage(pipeline, 1);
        var company = newCompany();
        Deal first = newDeal(pipeline, stage, company);
        Deal second = newDeal(pipeline, stage, company);
        Deal third = newDeal(pipeline, stage, company);
        setOpenDealValue(first, "USD", "100.00");
        setOpenDealValue(second, "USD", "200.00");
        setOpenDealValue(third, "EUR", "80.00");

        String suffix = "stage".equals(groupBy) ? Integer.toString(stage.getId()) : "total";
        String label = "stage".equals(groupBy) ? stage.getName() : "Total";
        for (String measure : List.of("forecast_best", "forecast_weighted", "forecast_worst")) {
            List<ReportForecastAggregateRow> rows = reportMapper.aggregateForecast(query(measure, groupBy));
            assertEquals(2, rows.size());
            assertEquals("EUR:" + suffix, rows.get(0).groupKey());
            assertEquals("EUR · " + label, rows.get(0).groupLabel());
            assertEquals("EUR", rows.get(0).unit());
            assertDecimal("80.00", rows.get(0).bestValue());
            assertDecimal("40.00", rows.get(0).weightedValue());
            assertDecimal("20.00", rows.get(0).worstValue());
            assertEquals("USD:" + suffix, rows.get(1).groupKey());
            assertEquals("USD · " + label, rows.get(1).groupLabel());
            assertEquals("USD", rows.get(1).unit());
            assertDecimal("300.00", rows.get(1).bestValue());
            assertDecimal("150.00", rows.get(1).weightedValue());
            assertDecimal("75.00", rows.get(1).worstValue());
        }
        List<ReportAggregateRow> values = reportMapper.aggregateDeals(query("open_pipeline_value", groupBy));
        assertEquals(2, values.size());
        assertEquals("EUR:" + suffix, values.get(0).groupKey());
        assertEquals("EUR", values.get(0).unit());
        assertDecimal("80.00", values.get(0).value());
        assertEquals("USD:" + suffix, values.get(1).groupKey());
        assertEquals("USD", values.get(1).unit());
        assertDecimal("300.00", values.get(1).value());
    }

    @Test
    void constantUnitAggregatesDoNotSplitByLineItemUnits() {
        var pipeline = newPipeline();
        var stage = newStage(pipeline, 1);
        var company = newCompany();
        Deal deal = newDeal(pipeline, stage, company);
        setOpenDealValue(deal, "USD", "240.00");
        for (String unit : List.of("seat", "hour", "license")) {
            jdbcTemplate.update("""
                    INSERT INTO deal_line_item
                        (workspace_id, deal_id, name, unit, unit_price, quantity, discount_type,
                         discount_value, currency, line_subtotal, line_tax, line_total)
                    VALUES (?, ?, ?, ?, 100.00, 1, 'percent', 20.00, 'USD', 80.00, 0.00, 80.00)
                    """, workspace.getId(), deal.getId(), "Reported " + unit, unit);
        }

        List<ReportAggregateRow> discounts = reportMapper.aggregateDealDiscount(query("open_discount_percent", "none"));
        assertEquals(1, discounts.size());
        assertEquals("USD:total", discounts.getFirst().groupKey());
        assertEquals("percent", discounts.getFirst().unit());
        assertDecimal("20.00", discounts.getFirst().value());

        List<ReportAggregateRow> counts = reportMapper.aggregateDeals(query("open_deal_count", "none"));
        assertEquals(1, counts.size());
        assertEquals("total", counts.getFirst().groupKey());
        assertEquals("count", counts.getFirst().unit());
        assertDecimal("1", counts.getFirst().value());
    }

    private void setOpenDealValue(Deal deal, String currency, String value) {
        jdbcTemplate.update("""
                UPDATE deal SET currency = ?, value = ?, expected_close_date = ?, won = NULL
                WHERE workspace_id = ? AND id = ?
                """, currency, new BigDecimal(value), LocalDate.of(2026, 1, 15), workspace.getId(), deal.getId());
    }

    private ReportAggregateQuery query(String measure, String groupBy) {
        LocalDateTime start = LocalDateTime.of(2026, 1, 1, 0, 0);
        LocalDateTime end = start.plusMonths(1);
        return new ReportAggregateQuery(
                workspace.getId(), measure, groupBy, "month", start, end, start.toLocalDate(), end.toLocalDate(),
                null, null, null, null, null, new BigDecimal("0.5"), 10,
                List.of(new ReportOffsetSegment(start, end, 0)));
    }

    private static void assertDecimal(String expected, BigDecimal actual) {
        assertTrue(new BigDecimal(expected).compareTo(actual) == 0, () -> expected + " != " + actual);
    }
}
