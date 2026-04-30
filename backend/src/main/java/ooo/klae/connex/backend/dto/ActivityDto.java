package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.User;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ActivityDto {

    private int id;

    @NotBlank
    @Size(max = 32)
    private String type;

    @NotBlank
    @Size(max = 255)
    private String subject;

    private String notes;

    private Person person;

    private Deal deal;

    @NotNull
    private User createdBy;

    @Size(max = 20)
    private String timestamp;
}
