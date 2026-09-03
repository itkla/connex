package ooo.klae.connex.backend.beans;

import lombok.Data;
import lombok.NoArgsConstructor;

/** One localized content variant for a mutable sequence step. */
@Data
@NoArgsConstructor
public class SequenceStepContent {
    private int workspaceId;
    private long stepId;
    private String locale;
    private String subject;
    private String bodyText;
    private String bodyHtml;
}
