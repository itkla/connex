package ooo.klae.connex.backend.dto;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The warmest introduction path to a target contact: the chain from a contact the team already
 * engages, through mutual connections, to the target. {@code steps} is ordered from the entry
 * point to the target (the target itself is the last step).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class IntroPathDto {
    /** Whether any path from an engaged contact to the target exists. */
    private boolean reachable;
    /** Whether the team already engages the target directly (no introduction needed). */
    private boolean directlyKnown;
    private List<IntroPathStepDto> steps;
}
