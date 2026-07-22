package ooo.klae.connex.backend.dto;

import java.util.List;

/**
 * The full line-item view for a deal: the ordered items plus their server-computed totals.
 *
 * @param items  ordered line items
 * @param totals the deal roll-up
 */
public record DealLineItemsResponse(
    List<DealLineItemDto> items,
    DealLineItemTotalsDto totals
) {}
