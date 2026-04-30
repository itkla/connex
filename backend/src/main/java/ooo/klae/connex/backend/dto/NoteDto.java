package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.User;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class NoteDto {

    private int id;

    @NotBlank
    private String content;

    @NotNull
    private User author;

    private Person person;

    private Deal deal;
}
