package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Request body for creating a record comment thread and its root comment. */
@Data
@NoArgsConstructor
public class RecordCommentCreateThreadRequest {
    @NotBlank
    @Pattern(regexp = "^(person|company|deal)$")
    private String targetType;

    @NotNull
    @Positive
    private Integer targetId;

    @NotBlank
    @Size(max = 5000)
    private String content;

    @NotBlank
    @Pattern(regexp = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    private String clientToken;
}
