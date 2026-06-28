package ooo.klae.connex.backend.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The transition layer of a rule. For {@code entity_change} triggers, {@code events} names the
 * mutations that fire the rule (e.g. {@code deal.stage_changed}, {@code task.completed}) with an
 * optional {@code targetStageId} narrowing a stage change. For {@code schedule} triggers,
 * {@code cadence} sets how often the rule re-evaluates its condition over the workspace's records.
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

    @Size(max = 16)
    private String cadence;
}
