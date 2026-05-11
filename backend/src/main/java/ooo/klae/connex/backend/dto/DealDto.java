package ooo.klae.connex.backend.dto;

import com.fasterxml.jackson.annotation.JsonIdentityReference;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DealDto {

    private int id;

    @NotBlank
    @Size(max = 255)
    private String name;

    @PositiveOrZero
    private double value;

    @NotBlank
    @Size(max = 8)
    private String currency;

    @NotNull
    @JsonIdentityReference(alwaysAsId = true)
    private Pipeline pipeline;

    @NotNull
    @JsonIdentityReference(alwaysAsId = true)
    private Stage stage;

    @JsonIdentityReference(alwaysAsId = true)
    private Company company;

    @Size(max = 32)
    private String expectedCloseDate;

    @Size(max = 32)
    private String closedAt;

    private String createdAt;
    private String updatedAt;

    public static DealDto from(Deal d) {
        if (d == null) return null;
        DealDto dto = new DealDto();
        dto.id = d.getId();
        dto.name = d.getName();
        dto.value = d.getValue();
        dto.currency = d.getCurrency();
        dto.pipeline = d.getPipeline();
        dto.stage = d.getStage();
        dto.company = d.getCompany();
        dto.expectedCloseDate = d.getExpectedCloseDate();
        dto.closedAt = d.getClosedAt();
        dto.createdAt = d.getCreatedAt();
        dto.updatedAt = d.getUpdatedAt();
        return dto;
    }

    public Deal toBean() {
        Deal d = new Deal();
        d.setId(id);
        d.setName(name);
        d.setValue(value);
        d.setCurrency(currency);
        d.setPipeline(pipeline);
        d.setStage(stage);
        d.setCompany(company);
        d.setExpectedCloseDate(expectedCloseDate);
        d.setClosedAt(closedAt);
        d.setCreatedAt(createdAt);
        d.setUpdatedAt(updatedAt);
        return d;
    }
}
