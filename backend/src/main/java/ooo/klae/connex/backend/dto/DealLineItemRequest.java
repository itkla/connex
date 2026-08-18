package ooo.klae.connex.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Client input for creating or updating a deal line item. When {@code productId} is set, catalog
 * values are snapshotted server-side and any provided overrides apply on top; otherwise the line is
 * ad-hoc and {@code name} + {@code unitPrice} are required.
 */
@Data
@NoArgsConstructor
public class DealLineItemRequest {
    private Integer productId;

    @Size(max = 255)
    private String name;

    @Size(max = 64)
    private String sku;

    @Size(max = 32)
    private String unit;

    @DecimalMin(value = "0", message = "unitPrice must not be negative")
    private BigDecimal unitPrice;

    @NotNull
    @DecimalMin(value = "0", message = "quantity must not be negative")
    private BigDecimal quantity;

    @Pattern(regexp = "amount|percent", message = "Choose either an amount or a percentage discount.")
    private String discountType;

    @DecimalMin(value = "0", message = "discountValue must not be negative")
    private BigDecimal discountValue;

    @DecimalMin(value = "0", message = "taxRate must not be negative")
    private BigDecimal taxRate;

    @Pattern(regexp = "one_time|recurring", message = "billingFrequency must be one_time or recurring")
    private String billingFrequency;

    @Size(max = 1024)
    private String description;

    private LocalDate servicePeriodStart;
    private LocalDate servicePeriodEnd;

    private Integer position;
}
