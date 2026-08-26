package ooo.klae.connex.backend.dto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request body for the interpreted-scope preview a broad assistant request is gated behind.
 *
 * @param content the request the member is about to send, used only for skill recognition
 * @param pageContext records the active page anchors the request to
 * @param scope declared query scope to validate, authorize, and evaluate
 */
public record AiChatScopePreviewRequest(
        @Size(max = 16000)
        String content,
        @Size(max = 10)
        List<@NotNull @Valid AiChatPageContextDto> pageContext,
        @Valid
        AiChatQueryScopeRequest scope) {

    public AiChatScopePreviewRequest {
        pageContext = pageContext == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(pageContext));
    }
}
