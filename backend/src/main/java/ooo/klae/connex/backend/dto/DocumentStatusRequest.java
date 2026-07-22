package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.Pattern;

import lombok.Data;
import lombok.NoArgsConstructor;

/** Client input to transition a document's status. */
@Data
@NoArgsConstructor
public class DocumentStatusRequest {
    @Pattern(regexp = "draft|final|superseded", message = "status must be draft, final, or superseded")
    private String status;
}
