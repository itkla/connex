package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import lombok.Data;
import lombok.NoArgsConstructor;

import ooo.klae.connex.backend.beans.QualificationDimension;

/** A requested qualification criterion; the service validates the combination (#559). */
@Data
@NoArgsConstructor
public class QualificationCriterionRequest {

    @NotBlank
    @Size(max = 200)
    private String label;

    private QualificationDimension dimension;

    @Min(1)
    @Max(100)
    private Integer weight;

    private Boolean required;

    private Integer position;
}
