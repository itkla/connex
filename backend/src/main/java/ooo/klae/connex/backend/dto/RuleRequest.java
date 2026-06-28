package ooo.klae.connex.backend.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Create/replace payload for a rule. The trigger, optional condition, and actions are validated
 * structurally here and semantically (per trigger/action type, and RBAC for {@code system} mode)
 * in the service.
 */
@Data
@NoArgsConstructor
public class RuleRequest {

    @NotBlank
    @Size(max = 128)
    private String name;

    @Size(max = 512)
    private String description;

    private Boolean enabled;

    @NotBlank
    @Size(max = 16)
    private String recordType;

    @NotNull
    @Valid
    private RuleTrigger trigger;

    @Valid
    private SegmentDefinition condition;

    @NotEmpty
    @Size(max = 16)
    private List<@Valid RuleAction> actions;

    @NotBlank
    @Size(max = 8)
    private String executionMode;

    private Integer runAsUserId;
}
