package ooo.klae.connex.backend.beans;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A dismissed or accepted warm-path suggestion: a {@code null} bridge dismisses every path to the
 * target, a non-null bridge dismisses only that avenue. Populated from {@code warm_path_dismissal}
 * by {@code IntroductionMapper}.
 */
@Data
@NoArgsConstructor
public class WarmPathDismissal {
    private int targetPersonId;
    private Integer bridgePersonId;
}
