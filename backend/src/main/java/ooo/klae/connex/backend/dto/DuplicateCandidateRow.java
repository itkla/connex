package ooo.klae.connex.backend.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Visible duplicate-candidate evidence projected by the identity mapper.
 */
@Data
@NoArgsConstructor
public class DuplicateCandidateRow {
    private int recordId;
    private int recordWorkspaceId;
    private String name;
    private String companyName;
    private String title;
    private String website;
    private String industry;
    private String kind;
    private String normalizedValue;
}
