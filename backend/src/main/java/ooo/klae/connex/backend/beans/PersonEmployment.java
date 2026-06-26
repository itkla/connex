package ooo.klae.connex.backend.beans;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One stint of a contact's employment history. The row with {@code endedAt == null} is the
 * current employment. {@code companyName} is a snapshot taken when the row was created, so the
 * history reads correctly even after a company is renamed or deleted. Mapped via
 * {@code PersonEmploymentMapper}.
 */
@Data
@NoArgsConstructor
public class PersonEmployment {
    private int id;
    private int workspaceId;
    private int personId;
    private Integer companyId;
    private String companyName;
    private String title;
    private String startedAt;
    private String endedAt;
    private String createdAt;
}
