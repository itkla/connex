package ooo.klae.connex.backend.dto;

import com.fasterxml.jackson.annotation.JsonIdentityReference;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import ooo.klae.connex.backend.beans.Activity;
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

    @JsonIdentityReference(alwaysAsId = true)
    private Person person;

    @JsonIdentityReference(alwaysAsId = true)
    private Deal deal;

    @NotNull
    @JsonIdentityReference(alwaysAsId = true)
    private User createdBy;

    @Size(max = 32)
    private String timestamp;

    public static ActivityDto from(Activity a) {
        if (a == null) return null;
        ActivityDto dto = new ActivityDto();
        dto.id = a.getId();
        dto.type = a.getType();
        dto.subject = a.getSubject();
        dto.notes = a.getNotes();
        dto.person = a.getPerson();
        dto.deal = a.getDeal();
        dto.createdBy = a.getCreatedBy();
        dto.timestamp = a.getTimestamp();
        return dto;
    }

    public Activity toBean() {
        Activity a = new Activity();
        a.setId(id);
        a.setType(type);
        a.setSubject(subject);
        a.setNotes(notes);
        a.setPerson(person);
        a.setDeal(deal);
        a.setCreatedBy(createdBy);
        a.setTimestamp(timestamp);
        return a;
    }
}
