package ooo.klae.connex.backend.beans;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A single contact-at-company employment record, used to detect a shared employer between two
 * contacts when ranking reverse-introduction candidates. Populated by a MyBatis projection in
 * {@code IntroductionMapper}.
 */
@Data
@NoArgsConstructor
public class IntroEmploymentRow {
    private int personId;
    private Integer companyId;
    private String companyName;
}
