package ooo.klae.connex.backend.dto;

import java.util.List;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The full line-item view for a deal: the ordered items plus their server-computed totals.
 *
 * @param items  ordered line items
 * @param totals the deal roll-up, or null while legacy line currencies disagree with the deal
 */
public record DealLineItemsResponse(
    List<DealLineItemDto> items,
    @JsonInclude(JsonInclude.Include.ALWAYS) @Nullable DealLineItemTotalsDto totals
) {}
