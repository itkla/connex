package ooo.klae.connex.backend.dto.recordcreation;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import ooo.klae.connex.backend.beans.Deal;

public record GuidedDealRecordDto(
    @NotBlank @Size(max = 255) String name,
    @NotNull @DecimalMin("0.00") @Digits(integer = 13, fraction = 2) BigDecimal value,
    @NotBlank @Size(max = 8) String currency,
    @NotNull @Positive Integer pipeline,
    @NotNull @Positive Integer stage,
    @Positive Integer company,
    LocalDate expectedCloseDate,
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    @Pattern(regexp = "^[0-9a-f]{64}$") String duplicateReviewToken
) {
    public Deal toBean() {
        Deal deal = new Deal();
        deal.setName(name);
        deal.setValue(value);
        deal.setCurrency(currency);
        deal.setPipelineId(pipeline);
        deal.setStageId(stage);
        deal.setCompanyId(company);
        deal.setExpectedCloseDate(expectedCloseDate == null ? null : expectedCloseDate.toString());
        return deal;
    }
}
