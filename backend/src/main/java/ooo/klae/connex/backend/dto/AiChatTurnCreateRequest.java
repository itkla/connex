package ooo.klae.connex.backend.dto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request body for starting one bounded assistant turn.
 *
 * @param content the member's literal request
 * @param pageContext records the active page anchors the turn to
 * @param scope declared query scope the server validates, authorizes, and then executes
 */
public record AiChatTurnCreateRequest(
        @NotBlank
        @Size(max = 16000)
        String content,
        @Size(max = 10)
        List<@NotNull @Valid AiChatPageContextDto> pageContext,
        @Valid
        AiChatQueryScopeRequest scope) {

    /** Creates a turn request that declares no query scope. */
    public AiChatTurnCreateRequest(String content, List<AiChatPageContextDto> pageContext) {
        this(content, pageContext, null);
    }

    public AiChatTurnCreateRequest {
        pageContext = pageContext == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(pageContext));
    }
}
