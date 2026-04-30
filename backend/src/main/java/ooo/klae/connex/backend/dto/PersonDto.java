package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import ooo.klae.connex.backend.beans.Company;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PersonDto {

    private int id;

    @NotBlank
    @Size(max = 255)
    private String name;

    @Email
    @Size(max = 255)
    private String email;

    @Size(max = 64)
    private String phone;

    private Company company;

    @Size(max = 128)
    private String title;
}
