package ooo.klae.connex.backend.dto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Request body for starting one bounded assistant turn. */
public record AiChatTurnCreateRequest(
        @NotBlank
        @Size(max = 16000)
        String content,
        @Size(max = 10)
        List<@NotNull @Valid AiChatPageContextDto> pageContext) {

    public AiChatTurnCreateRequest {
        pageContext = pageContext == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(pageContext));
    }
}
