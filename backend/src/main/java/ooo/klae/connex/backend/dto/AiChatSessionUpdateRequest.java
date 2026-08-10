package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Partial update for an owned assistant chat session. */
@Data
@NoArgsConstructor
public class AiChatSessionUpdateRequest {
    @Size(max = 200)
    private String title;
    private Boolean archived;
}
