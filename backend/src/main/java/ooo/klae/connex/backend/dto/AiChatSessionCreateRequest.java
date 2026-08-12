package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Request body for creating a private assistant chat session. */
@Data
@NoArgsConstructor
public class AiChatSessionCreateRequest {
    @NotBlank
    @Size(max = 200)
    private String title;
    private boolean autoTitle;
}
