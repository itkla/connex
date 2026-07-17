package ooo.klae.connex.backend.beans;

import java.math.BigDecimal;
import java.time.LocalDate;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A line item on a deal. Catalog values ({@code name}, {@code sku}, {@code unit}, {@code unitPrice},
 * {@code taxRate}, {@code billingFrequency}) are snapshotted at creation so later product edits never
 * change an existing line. {@code lineSubtotal}/{@code lineTax}/{@code lineTotal} are server-computed
 * in {@link BigDecimal} and persisted. {@code currency} always equals the parent deal's currency.
 */
@Data
@NoArgsConstructor
public class DealLineItem {
    private int id;
    private int workspaceId;
    private int dealId;
    private Integer productId;
    private String name;
    private String sku;
    private String unit;
    private BigDecimal unitPrice;
    private BigDecimal quantity;
    private String discountType;
    private BigDecimal discountValue;
    private BigDecimal taxRate;
    private String billingFrequency;
    private String description;
    private LocalDate servicePeriodStart;
    private LocalDate servicePeriodEnd;
    private int position;
    private String currency;
    private BigDecimal lineSubtotal;
    private BigDecimal lineTax;
    private BigDecimal lineTotal;
    private String createdAt;
    private String updatedAt;
}
