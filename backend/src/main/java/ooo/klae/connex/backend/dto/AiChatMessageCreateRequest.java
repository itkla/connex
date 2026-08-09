package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Request body for appending a caller-authored assistant chat message. */
@Data
@NoArgsConstructor
public class AiChatMessageCreateRequest {
    @NotBlank
    @Size(max = 16000)
    private String content;
}
