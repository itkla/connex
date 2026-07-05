package ooo.klae.connex.backend.dto;

import java.util.List;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The transition layer of a rule. For {@code entity_change} triggers, {@code events} names the
 * mutations that fire the rule (e.g. {@code deal.stage_changed}, {@code task.completed}) with an
 * optional {@code targetStageId} narrowing a stage change and an optional {@code throttleMinutes}
 * cooldown that collapses repeat fires on the same record into one per window. For {@code schedule}
 * triggers, {@code cadence} sets how often the rule re-evaluates its condition over the workspace's
 * records.
 */
@Data
@NoArgsConstructor
public class RuleTrigger {

    @NotBlank
    @Size(max = 16)
    private String type;

    @Size(max = 8)
    private List<@NotBlank @Size(max = 32) String> events;

    private Integer targetStageId;

    @Min(1)
    @Max(10080)
    private Integer throttleMinutes;

    @Size(max = 16)
    private String cadence;
}
