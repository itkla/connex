package ooo.klae.connex.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import lombok.Data;
import lombok.NoArgsConstructor;

import ooo.klae.connex.backend.beans.DealLineItem;

/**
 * Client-facing line item. The {@code lineSubtotal}/{@code lineTax}/{@code lineTotal} fields are
 * server-computed and read-only; client input goes through {@link DealLineItemRequest}.
 */
@Data
@NoArgsConstructor
public class DealLineItemDto {
    private Integer id;
    private Integer dealId;
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

    public static DealLineItemDto from(DealLineItem i) {
        if (i == null) return null;
        DealLineItemDto dto = new DealLineItemDto();
        dto.id = i.getId();
        dto.dealId = i.getDealId();
        dto.productId = i.getProductId();
        dto.name = i.getName();
        dto.sku = i.getSku();
        dto.unit = i.getUnit();
        dto.unitPrice = i.getUnitPrice();
        dto.quantity = i.getQuantity();
        dto.discountType = i.getDiscountType();
        dto.discountValue = i.getDiscountValue();
        dto.taxRate = i.getTaxRate();
        dto.billingFrequency = i.getBillingFrequency();
        dto.description = i.getDescription();
        dto.servicePeriodStart = i.getServicePeriodStart();
        dto.servicePeriodEnd = i.getServicePeriodEnd();
        dto.position = i.getPosition();
        dto.currency = i.getCurrency();
        dto.lineSubtotal = i.getLineSubtotal();
        dto.lineTax = i.getLineTax();
        dto.lineTotal = i.getLineTotal();
        dto.createdAt = i.getCreatedAt();
        dto.updatedAt = i.getUpdatedAt();
        return dto;
    }
}
