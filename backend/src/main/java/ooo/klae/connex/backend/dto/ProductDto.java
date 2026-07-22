package ooo.klae.connex.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import lombok.Data;
import lombok.NoArgsConstructor;

import ooo.klae.connex.backend.beans.Product;

/**
 * Client-facing product representation. {@code workspaceId} is never accepted from the client —
 * the service sets it from the active workspace.
 */
@Data
@NoArgsConstructor
public class ProductDto {

    private Integer id;

    @Size(max = 64)
    private String sku;

    @NotBlank
    @Size(max = 255)
    private String name;

    @Size(max = 1024)
    private String description;

    private Boolean active;

    @Size(max = 32)
    private String unit;

    @DecimalMin(value = "0", message = "unitPrice must not be negative")
    private BigDecimal unitPrice;

    @Size(max = 8)
    private String currency;

    @DecimalMin(value = "0", message = "taxRate must not be negative")
    private BigDecimal taxRate;

    @Pattern(regexp = "one_time|recurring", message = "billingFrequency must be one_time or recurring")
    private String billingFrequency;

    private LocalDate effectiveStart;
    private LocalDate effectiveEnd;

    private String createdAt;
    private String updatedAt;

    public static ProductDto from(Product p) {
        if (p == null) return null;
        ProductDto dto = new ProductDto();
        dto.id = p.getId();
        dto.sku = p.getSku();
        dto.name = p.getName();
        dto.description = p.getDescription();
        dto.active = p.isActive();
        dto.unit = p.getUnit();
        dto.unitPrice = p.getUnitPrice();
        dto.currency = p.getCurrency();
        dto.taxRate = p.getTaxRate();
        dto.billingFrequency = p.getBillingFrequency();
        dto.effectiveStart = p.getEffectiveStart();
        dto.effectiveEnd = p.getEffectiveEnd();
        dto.createdAt = p.getCreatedAt();
        dto.updatedAt = p.getUpdatedAt();
        return dto;
    }

    public Product toBean() {
        Product p = new Product();
        if (id != null) p.setId(id);
        p.setSku(sku);
        p.setName(name);
        p.setDescription(description);
        p.setActive(active == null ? true : active);
        p.setUnit(unit);
        p.setUnitPrice(unitPrice == null ? BigDecimal.ZERO : unitPrice);
        p.setCurrency(currency == null || currency.isBlank() ? "USD" : currency);
        p.setTaxRate(taxRate);
        p.setBillingFrequency(billingFrequency == null || billingFrequency.isBlank() ? "one_time" : billingFrequency);
        p.setEffectiveStart(effectiveStart);
        p.setEffectiveEnd(effectiveEnd);
        return p;
    }
}
