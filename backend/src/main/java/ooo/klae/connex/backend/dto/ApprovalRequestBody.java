package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.Size;

import lombok.Data;
import lombok.NoArgsConstructor;

/** Request body when asking for approval on a generated document. */
@Data
@NoArgsConstructor
public class ApprovalRequestBody {

    @Size(max = 1000)
    private String comment;
}
