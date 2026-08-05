package ooo.klae.connex.backend.beans;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Projection of a workspace-owned contact with logged engagement or explicit graph evidence,
 * carrying just the attributes the introduction ranking and display need. Populated by a MyBatis
 * projection in {@code IntroductionMapper}.
 */
@Data
@NoArgsConstructor
public class IntroCandidatePerson {
    private int id;
    private String name;
    private String title;
    private Integer companyId;
    private String companyName;
    private String imageUrl;
}
