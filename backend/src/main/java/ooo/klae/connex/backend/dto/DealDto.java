package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import ooo.klae.connex.backend.beans.Company;
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
    private Pipeline pipeline;

    @NotNull
    private Stage stage;

    private Company company;

    @Size(max = 20)
    private String expectedCloseDate;

    @Size(max = 20)
    private String closedAt;
}
