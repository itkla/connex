package ooo.klae.connex.backend.beans;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A single contact-at-company employment record, used to detect a shared employer between two
 * contacts when ranking reverse-introduction candidates, and a dated tenure overlap when ranking
 * warm-path bridges. Populated by a MyBatis projection in {@code IntroductionMapper}. Timestamps
 * are MySQL {@code yyyy-MM-dd HH:mm:ss} strings (lexicographically ordered); {@code endedAt} is
 * {@code null} for a current employment.
 */
@Data
@NoArgsConstructor
public class IntroEmploymentRow {
    private int personId;
    private Integer companyId;
    private String companyName;
    private String startedAt;
    private String endedAt;
}
