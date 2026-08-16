package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body handing one approver's seat on an active approval step to another member. The
 * delegate must be an active member holding {@code DOCUMENT_APPROVE} who is not excluded by the
 * request's separation-of-duties rule; both checks run in the service under the document lock.
 */
@Data
@NoArgsConstructor
public class ApprovalDelegationRequest {

    @NotNull
    @Min(1)
    private Integer delegateUserId;

    @Size(max = 500)
    private String comment;
}
