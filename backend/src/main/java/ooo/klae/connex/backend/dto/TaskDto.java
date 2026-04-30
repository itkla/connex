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
public class TaskDto {

    private int id;

    @Size(max = 255)
    private String description;

    private boolean completed;

    @Size(max = 20)
    @NotBlank
    private String dueDate;

    @NotNull
    private User assignedTo;

    @NotNull
    private Person person; // target contact

    @NotNull
    private Deal deal;
}