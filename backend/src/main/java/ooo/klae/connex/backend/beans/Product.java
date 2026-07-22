package ooo.klae.connex.backend.beans;

import java.math.BigDecimal;
import java.time.LocalDate;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A workspace-scoped catalog product or service. Money fields are {@link BigDecimal} to stay
 * exact against the {@code DECIMAL} columns. Deal line items snapshot these values at creation,
 * so editing a product never mutates existing lines.
 */
@Data
@NoArgsConstructor
public class Product {
    private int id;
    private int workspaceId;
    private String sku;
    private String name;
    private String description;
    private boolean active = true;
    private String unit;
    private BigDecimal unitPrice;
    private String currency;
    private BigDecimal taxRate;
    private String billingFrequency;
    private LocalDate effectiveStart;
    private LocalDate effectiveEnd;
    private String createdAt;
    private String updatedAt;
}
