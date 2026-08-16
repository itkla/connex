package ooo.klae.connex.backend.dto;

import java.util.ArrayList;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body widening or replacing the approvers of one active step of a frozen approval chain.
 * {@code POST} appends the listed approvers to the current set; {@code PUT} replaces it, opening a
 * new reassignment round. Neither ever rewrites the frozen snapshot.
 */
@Data
@NoArgsConstructor
public class ApprovalStepApproverChangeRequest {

    @Valid
    @NotEmpty(message = "at least one approver is required")
    @Size(max = 20, message = "a step may not have more than 20 approvers")
    private List<@NotNull(message = "approvers must not contain empty entries") ApprovalStepApproverDto>
        approvers = new ArrayList<>();

    @Size(max = 500)
    private String comment;
}
