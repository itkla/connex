package ooo.klae.connex.backend.dto;

import com.fasterxml.jackson.annotation.JsonIdentityReference;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.beans.User;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TaskDto {

    private int id;

    @NotBlank
    @Size(max = 1000)
    private String description;

    private boolean completed;

    @Size(max = 32)
    private String dueDate;

    @NotNull
    @JsonIdentityReference(alwaysAsId = true)
    private User assignedTo;

    @JsonIdentityReference(alwaysAsId = true)
    private Person person;

    @JsonIdentityReference(alwaysAsId = true)
    private Deal deal;

    private String createdAt;
    private String updatedAt;

    public static TaskDto from(Task t) {
        if (t == null) return null;
        TaskDto dto = new TaskDto();
        dto.id = t.getId();
        dto.description = t.getDescription();
        dto.completed = t.isCompleted();
        dto.dueDate = t.getDueDate();
        dto.assignedTo = t.getAssignedTo();
        dto.person = t.getPerson();
        dto.deal = t.getDeal();
        dto.createdAt = t.getCreatedAt();
        dto.updatedAt = t.getUpdatedAt();
        return dto;
    }

    public Task toBean() {
        Task t = new Task();
        t.setId(id);
        t.setDescription(description);
        t.setCompleted(completed);
        t.setDueDate(dueDate);
        t.setAssignedTo(assignedTo);
        t.setPerson(person);
        t.setDeal(deal);
        t.setCreatedAt(createdAt);
        t.setUpdatedAt(updatedAt);
        return t;
    }
}
