package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Request body for editing a comment within its author-only edit window. */
public record RecordCommentEditRequest(
        @NotBlank @Size(max = 5000) String content) {
}
