package ooo.klae.connex.backend.dto;

import java.util.ArrayList;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import lombok.Data;
import lombok.NoArgsConstructor;

import ooo.klae.connex.backend.beans.ApprovalPolicyStep;

/**
 * One step of a policy's approver chain. Step order follows the position in the submitted list, so
 * clients reorder by reordering the array rather than by sending an index. Existing steps retain
 * their returned {@code id}; an omitted ID denotes a new step.
 *
 * <p>An omitted {@code dueIntervalHours} means the step never expires and is never reminded, which
 * is why {@code onExpiry} defaults to {@code expire}: only a step with a deadline may escalate.
 */
@Data
@NoArgsConstructor
public class ApprovalPolicyStepDto {

    private Integer id;

    @Size(max = 255)
    private String name;

    @Min(1)
    @Max(20)
    private int requiredCount = 1;

    @Min(1)
    @Max(8760)
    private Integer dueIntervalHours;

    @Pattern(regexp = "expire|escalate", message = "onExpiry must be expire or escalate")
    private String onExpiry;

    @Valid
    @NotEmpty(message = "each step needs at least one approver")
    @Size(max = 20, message = "a step may not have more than 20 approvers")
    private List<@NotNull(message = "approvers must not contain empty entries") ApprovalStepApproverDto>
        approvers = new ArrayList<>();

    public static ApprovalPolicyStepDto from(ApprovalPolicyStep step) {
        if (step == null) return null;
        ApprovalPolicyStepDto dto = new ApprovalPolicyStepDto();
        dto.id = step.getId();
        dto.name = step.getName();
        dto.requiredCount = step.getRequiredCount();
        dto.dueIntervalHours = step.getDueIntervalHours();
        dto.onExpiry = step.getOnExpiry();
        dto.approvers = step.getApprovers().stream().map(ApprovalStepApproverDto::from).toList();
        return dto;
    }

    public ApprovalPolicyStep toBean() {
        ApprovalPolicyStep step = new ApprovalPolicyStep();
        if (id != null) step.setId(id);
        step.setName(name == null || name.isBlank() ? null : name.trim());
        step.setRequiredCount(requiredCount);
        step.setDueIntervalHours(dueIntervalHours);
        step.setOnExpiry(onExpiry == null || onExpiry.isBlank() ? "expire" : onExpiry);
        step.setApprovers(approvers == null ? List.of()
            : approvers.stream().map(ApprovalStepApproverDto::toBean).toList());
        return step;
    }
}
