package ooo.klae.connex.backend.beans;

import lombok.Data;
import lombok.NoArgsConstructor;

/** Mutable draft step belonging to a sales sequence. */
@Data
@NoArgsConstructor
public class SequenceStep {
    private long id;
    private int workspaceId;
    private int sequenceId;
    private int position;
    private String stepType;
    private int delayValue;
    private String delayUnit;
    private String advancePolicy;
}
