package ooo.klae.connex.backend.dto;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import lombok.Data;
import lombok.NoArgsConstructor;

import ooo.klae.connex.backend.beans.ApprovalPolicy;

/** Client-facing approval policy. {@code workspaceId} is never accepted from the client. */
@Data
@NoArgsConstructor
public class ApprovalPolicyDto {

    private Integer id;

    @NotBlank
    @Size(max = 255)
    private String name;

    private Boolean active;

    @Pattern(regexp = "quote|proposal|order_form|contract",
        message = "documentType must be quote, proposal, order_form, or contract")
    private String documentType;

    @Size(max = 8)
    private String currency;

    @DecimalMin(value = "0")
    @Digits(integer = 13, fraction = 2)
    private BigDecimal minTotal;

    @DecimalMin(value = "0")
    @DecimalMax(value = "100")
    @Digits(integer = 3, fraction = 3)
    private BigDecimal minDiscountPercent;

    @Pattern(regexp = "sequential|parallel", message = "mode must be sequential or parallel")
    private String mode;

    @Pattern(regexp = "strict|requester|off",
        message = "separationOfDuties must be strict, requester, or off")
    private String separationOfDuties;

    @Valid
    @Size(max = 10, message = "a policy may not have more than 10 approval steps")
    private List<@NotNull(message = "steps must not contain empty entries") ApprovalPolicyStepDto> steps =
        new ArrayList<>();

    private String createdAt;
    private String updatedAt;

    public static ApprovalPolicyDto from(ApprovalPolicy p) {
        if (p == null) return null;
        ApprovalPolicyDto dto = new ApprovalPolicyDto();
        dto.id = p.getId();
        dto.name = p.getName();
        dto.active = p.isActive();
        dto.documentType = p.getDocumentType();
        dto.currency = p.getCurrency();
        dto.minTotal = p.getMinTotal();
        dto.minDiscountPercent = p.getMinDiscountPercent();
        dto.mode = p.getMode();
        dto.separationOfDuties = p.getSeparationOfDuties();
        dto.steps = p.getSteps().stream().map(ApprovalPolicyStepDto::from).toList();
        dto.createdAt = p.getCreatedAt();
        dto.updatedAt = p.getUpdatedAt();
        return dto;
    }

    public ApprovalPolicy toBean() {
        ApprovalPolicy p = new ApprovalPolicy();
        if (id != null) p.setId(id);
        p.setName(name);
        p.setActive(active == null ? true : active);
        p.setDocumentType(documentType == null || documentType.isBlank() ? null : documentType);
        p.setCurrency(currency == null || currency.isBlank() ? null : currency);
        p.setMinTotal(minTotal);
        p.setMinDiscountPercent(minDiscountPercent);
        p.setMode(mode == null || mode.isBlank() ? "sequential" : mode);
        p.setSeparationOfDuties(
            separationOfDuties == null || separationOfDuties.isBlank() ? "strict" : separationOfDuties);
        p.setSteps(steps == null ? List.of() : steps.stream().map(ApprovalPolicyStepDto::toBean).toList());
        return p;
    }
}
