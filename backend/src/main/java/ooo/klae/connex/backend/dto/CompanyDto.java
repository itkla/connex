package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CompanyDto {

    private int id;

    @NotBlank
    @Size(max = 255)
    private String name;

    @Size(max = 255)
    private String website;

    @Size(max = 128)
    private String industry;

    @Size(max = 64)
    private String phone;

    @Size(max = 512)
    private String address;
}
