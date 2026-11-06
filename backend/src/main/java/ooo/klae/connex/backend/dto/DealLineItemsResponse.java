package ooo.klae.connex.backend.dto;

import java.math.BigDecimal;
import java.util.List;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The full line-item view for a deal: the ordered items plus their server-computed totals.
 *
 * @param items  ordered line items
 * @param totals the deal roll-up, or null while legacy line currencies disagree with the deal
 * @param manualValueToReplace nonzero manual value requiring consent, present only on previews
 * @param restorableManualValue prospective fallback restored automatically on final-line deletion
 */
public record DealLineItemsResponse(
    List<DealLineItemDto> items,
    @JsonInclude(JsonInclude.Include.ALWAYS) @Nullable DealLineItemTotalsDto totals,
    @Nullable BigDecimal manualValueToReplace,
    @Nullable BigDecimal restorableManualValue
) {
    public DealLineItemsResponse(List<DealLineItemDto> items, @Nullable DealLineItemTotalsDto totals) {
        this(items, totals, null, null);
    }
}
