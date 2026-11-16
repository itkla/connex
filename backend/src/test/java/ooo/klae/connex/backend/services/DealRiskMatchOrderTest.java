package ooo.klae.connex.backend.services;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.dto.DealRiskMatchDto;

/** Exercises null ordering, unsigned collation keys and stable ID ties for bounded risk pages. */
class DealRiskMatchOrderTest {
    @Test
    void textOrderUsesUnsignedDatabaseWeightsAndIdTiesInBothDirections() {
        var missing = row(4, null);
        var first = row(3, new byte[] {0});
        var equal = row(1, new byte[] {0});
        var last = row(2, new byte[] {-1});
        List<DealRiskMatchDto> rows = List.of(last, first, missing, equal);
        for (String sort : List.of("name", "company", "pipeline", "stage")) {
            assertThat(ids(rows, sort, "asc")).containsExactly(4, 1, 3, 2);
            assertThat(ids(rows, sort, "desc")).containsExactly(2, 1, 3, 4);
        }
    }

    @Test
    void numericAndNullableDatesKeepSqlNullPlacement() {
        var high = row(1, null);
        high.getDeal().setValue(new BigDecimal("100.00"));
        high.getDeal().setExpectedCloseDate("2026-12-01");
        var low = row(2, null);
        low.getDeal().setValue(new BigDecimal("20.00"));
        low.getDeal().setExpectedCloseDate("2026-01-01");
        var missingDate = row(3, null);
        missingDate.getDeal().setValue(new BigDecimal("100.00"));
        var rows = List.of(high, low, missingDate);
        assertThat(ids(rows, "value", "asc")).containsExactly(2, 1, 3);
        assertThat(ids(rows, "value", "desc")).containsExactly(1, 3, 2);
        assertThat(ids(rows, "expected_close_date", "asc")).containsExactly(3, 2, 1);
        assertThat(ids(rows, "expected_close_date", "desc")).containsExactly(1, 2, 3);
    }

    @Test
    void defaultOrderingKeepsStagePositionThenIdRegardlessOfDirection() {
        var first = row(3, null);
        first.getDeal().setStageId(1);
        first.getDeal().setPosition(2);
        var tie = row(2, null);
        tie.getDeal().setStageId(1);
        tie.getDeal().setPosition(2);
        var earlierPosition = row(4, null);
        earlierPosition.getDeal().setStageId(1);
        earlierPosition.getDeal().setPosition(1);
        var nextStage = row(1, null);
        nextStage.getDeal().setStageId(2);
        var rows = List.of(first, nextStage, earlierPosition, tie);
        assertThat(ids(rows, null, "asc")).containsExactly(4, 2, 3, 1);
        assertThat(ids(rows, null, "desc")).containsExactly(4, 2, 3, 1);
    }

    private static DealRiskMatchDto row(int id, byte[] key) {
        Deal deal = new Deal();
        deal.setId(id);
        DealRiskMatchDto row = new DealRiskMatchDto();
        row.setDeal(deal);
        row.setSortKey(key);
        return row;
    }

    private static List<Integer> ids(List<DealRiskMatchDto> rows, String sort, String direction) {
        return rows.stream().sorted(DealRiskMatchOrder.comparator(sort, direction))
            .map(row -> row.getDeal().getId()).toList();
    }
}
