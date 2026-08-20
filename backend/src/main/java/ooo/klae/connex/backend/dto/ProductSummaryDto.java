package ooo.klae.connex.backend.dto;

/**
 * Bounded catalog-product projection for a global-search result row.
 *
 * <p>Search rows render a label and a short qualifier only, so the group deliberately projects a
 * few columns instead of the full {@link ProductDto} with its pricing, tax, and validity window.
 *
 * @param id the product id
 * @param name the product name
 * @param sku the catalog SKU
 * @param active whether the product is currently sellable
 */
public record ProductSummaryDto(
        int id,
        String name,
        String sku,
        boolean active) {
}
