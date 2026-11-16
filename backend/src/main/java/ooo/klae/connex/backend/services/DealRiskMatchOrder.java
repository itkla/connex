package ooo.klae.connex.backend.services;

import java.util.Arrays;
import java.util.Comparator;

import ooo.klae.connex.backend.dto.DealRiskMatchDto;

/** Orders padding-normalized database keys with the list SQL's null placement and ID ties. */
final class DealRiskMatchOrder {
    private DealRiskMatchOrder() {}

    static Comparator<DealRiskMatchDto> comparator(String sort, String direction) {
        Comparator<DealRiskMatchDto> primary = switch (sort == null ? "" : sort) {
            case "name", "company", "pipeline", "stage" -> Comparator.comparing(
                DealRiskMatchDto::getSortKey, Comparator.nullsFirst(Arrays::compareUnsigned));
            case "value" -> Comparator.comparing(row -> row.getDeal().getValue(), Comparator.nullsFirst(Comparator.naturalOrder()));
            case "actual_value" -> Comparator.comparing(row -> row.getDeal().getActualValue(), Comparator.nullsFirst(Comparator.naturalOrder()));
            case "expected_close_date" -> Comparator.comparing(row -> row.getDeal().getExpectedCloseDate(), Comparator.nullsFirst(Comparator.naturalOrder()));
            case "closed_at" -> Comparator.comparing(row -> row.getDeal().getClosedAt(), Comparator.nullsFirst(Comparator.naturalOrder()));
            case "created_at" -> Comparator.comparing(row -> row.getDeal().getCreatedAt(), Comparator.nullsFirst(Comparator.naturalOrder()));
            case "updated_at" -> Comparator.comparing(row -> row.getDeal().getUpdatedAt(), Comparator.nullsFirst(Comparator.naturalOrder()));
            case "stage_id" -> Comparator.comparing(row -> row.getDeal().getStageId(), Comparator.nullsFirst(Comparator.naturalOrder()));
            case "position" -> Comparator.comparingInt(row -> row.getDeal().getPosition());
            case "status" -> Comparator.comparingInt(row -> row.getDeal().getWon() == null ? 0 : 1);
            default -> null;
        };
        if (primary == null) {
            return Comparator.<DealRiskMatchDto, Integer>comparing(
                    row -> row.getDeal().getStageId(), Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparingInt(row -> row.getDeal().getPosition())
                .thenComparingInt(row -> row.getDeal().getId());
        }
        if ("desc".equals(direction)) {
            primary = primary.reversed();
        }
        return primary.thenComparingInt(row -> row.getDeal().getId());
    }
}
