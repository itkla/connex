package ooo.klae.connex.backend.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import lombok.Data;
import lombok.NoArgsConstructor;

import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.beans.User;

@Data
@NoArgsConstructor
public class TaskDto {

    private int id;

    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private Integer workspaceId;

    @NotBlank
    @Size(max = 1000)
    private String description;

    private boolean completed;

    @Size(max = 10)
    @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$", message = "Due date must use YYYY-MM-DD")
    private String dueDate;

    // @JsonIdentityReference(alwaysAsId = true) // crashes deserialization in Jackson 3; using assignedToId for writes
    private User assignedTo;

    @NotNull
    private Integer assignedToId;

    // @JsonIdentityReference(alwaysAsId = true)
    private Person person;

    private Integer personId;

    // @JsonIdentityReference(alwaysAsId = true)
    private Deal deal;

    private Integer dealId;

    private String createdAt;
    private String updatedAt;

    public static TaskDto from(Task t) {
        if (t == null) return null;
        TaskDto dto = new TaskDto();
        dto.id = t.getId();
        dto.workspaceId = t.getWorkspaceId();
        dto.description = t.getDescription();
        dto.completed = t.isCompleted();
        dto.dueDate = t.getDueDate();
        dto.assignedTo = t.getAssignedTo();
        dto.assignedToId = t.getAssignedTo() == null ? null : t.getAssignedTo().getId();
        dto.person = t.getPerson();
        dto.personId = t.getPerson() == null ? null : t.getPerson().getId();
        dto.deal = t.getDeal();
        dto.dealId = t.getDeal() == null ? null : t.getDeal().getId();
        dto.createdAt = t.getCreatedAt();
        dto.updatedAt = t.getUpdatedAt();
        return dto;
    }

    public Task toBean() {
        Task t = new Task();
        t.setId(id);
        if (workspaceId != null) t.setWorkspaceId(workspaceId);
        t.setDescription(description);
        t.setCompleted(completed);
        t.setDueDate(dueDate);
        if (assignedToId != null) {
            User u = new User();
            u.setId(assignedToId);
            t.setAssignedTo(u);
        } else {
            t.setAssignedTo(assignedTo);
        }
        if (personId != null) {
            Person p = new Person();
            p.setId(personId);
            t.setPerson(p);
        } else {
            t.setPerson(person);
        }
        if (dealId != null) {
            Deal d = new Deal();
            d.setId(dealId);
            t.setDeal(d);
        } else {
            t.setDeal(deal);
        }
        t.setCreatedAt(createdAt);
        t.setUpdatedAt(updatedAt);
        return t;
    }
}
