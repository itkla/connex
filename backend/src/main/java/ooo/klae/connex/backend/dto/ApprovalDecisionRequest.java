package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body deciding a pending document approval. {@code stepId} targets one step of the
 * approval chain; when omitted the server picks the caller's lowest-order eligible active step.
 */
@Data
@NoArgsConstructor
public class ApprovalDecisionRequest {

    @NotNull
    @Pattern(regexp = "approved|rejected", message = "decision must be approved or rejected")
    private String decision;

    @Size(max = 1000)
    private String comment;

    private Integer stepId;
}
