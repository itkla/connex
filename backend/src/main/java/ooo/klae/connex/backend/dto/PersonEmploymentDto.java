package ooo.klae.connex.backend.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import ooo.klae.connex.backend.beans.PersonEmployment;

/**
 * API view of one entry in a contact's employment history. {@code current} is true for the
 * open-ended row (the contact's present company).
 */
@Data
@NoArgsConstructor
public class PersonEmploymentDto {
    private int id;
    private int personId;
    private Integer companyId;
    private String companyName;
    private String title;
    private String startedAt;
    private String endedAt;
    private boolean current;

    public static PersonEmploymentDto from(PersonEmployment e) {
        if (e == null) return null;
        PersonEmploymentDto dto = new PersonEmploymentDto();
        dto.id = e.getId();
        dto.personId = e.getPersonId();
        dto.companyId = e.getCompanyId();
        dto.companyName = e.getCompanyName();
        dto.title = e.getTitle();
        dto.startedAt = e.getStartedAt();
        dto.endedAt = e.getEndedAt();
        dto.current = e.getEndedAt() == null;
        return dto;
    }
}
