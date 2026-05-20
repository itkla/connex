package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import lombok.Data;
import lombok.NoArgsConstructor;

import ooo.klae.connex.backend.beans.Activity;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.User;

@Data
@NoArgsConstructor
public class ActivityDto {

    private int id;

    @NotBlank
    @Size(max = 32)
    private String type;

    @NotBlank
    @Size(max = 255)
    private String subject;

    private String notes;

    // @JsonIdentityReference(alwaysAsId = true) // crashes deserialization in Jackson 3; using personId for writes
    private Person person;

    private Integer personId;

    // @JsonIdentityReference(alwaysAsId = true)
    private Deal deal;

    private Integer dealId;

    // @JsonIdentityReference(alwaysAsId = true)
    private User createdBy;

    @NotNull
    private Integer createdById;

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
        dto.personId = a.getPerson() == null ? null : a.getPerson().getId();
        dto.deal = a.getDeal();
        dto.dealId = a.getDeal() == null ? null : a.getDeal().getId();
        dto.createdBy = a.getCreatedBy();
        dto.createdById = a.getCreatedBy() == null ? null : a.getCreatedBy().getId();
        dto.timestamp = a.getTimestamp();
        return dto;
    }

    public Activity toBean() {
        Activity a = new Activity();
        a.setId(id);
        a.setType(type);
        a.setSubject(subject);
        a.setNotes(notes);
        if (personId != null) {
            Person p = new Person();
            p.setId(personId);
            a.setPerson(p);
        } else {
            a.setPerson(person);
        }
        if (dealId != null) {
            Deal d = new Deal();
            d.setId(dealId);
            a.setDeal(d);
        } else {
            a.setDeal(deal);
        }
        if (createdById != null) {
            User u = new User();
            u.setId(createdById);
            a.setCreatedBy(u);
        } else {
            a.setCreatedBy(createdBy);
        }
        a.setTimestamp(timestamp);
        return a;
    }
}
