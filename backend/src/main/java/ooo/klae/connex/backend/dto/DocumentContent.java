package ooo.klae.connex.backend.dto;

import java.util.List;

/**
 * The immutable, resolved snapshot stored on a {@code deal_document}. Captured once at generation
 * time so the document stays stable even if the deal, catalog, or template change later. Serialized
 * to the {@code content} JSON column.
 *
 * @param generatedAt ISO timestamp the document was generated
 * @param workspace   generating workspace
 * @param company     the deal's company (may be null for freelancer deals)
 * @param owner       the deal owner (may be null)
 * @param deal        deal reference
 * @param sections    resolved template sections (merge tokens already substituted)
 * @param lineItems   frozen copy of the deal's line items at generation
 * @param totals      frozen roll-up
 */
public record DocumentContent(
    String generatedAt,
    PartyRef workspace,
    PartyRef company,
    PartyRef owner,
    DealRef deal,
    Sections sections,
    List<DealLineItemDto> lineItems,
    DealLineItemTotalsDto totals
) {
    public record PartyRef(String name, String address) {}
    public record DealRef(String name, String currency) {}
    public record Sections(String title, String intro, String terms, String footer) {}
}
