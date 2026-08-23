package ooo.klae.connex.backend.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Accepted assistant turn, its opaque shared-generation polling handle, and the exact scope the
 * server interpreted from the caller's declared filters.
 *
 * <p>Property inclusion is pinned to ALWAYS so an undeclared scope arrives as an explicit null
 * rather than an absent key the browser would have to guess at.
 *
 * @param turnId durable turn identifier
 * @param sessionId owning session
 * @param generationHandle opaque shared-generation polling handle
 * @param status generation admission status
 * @param scope interpreted query scope, or null when the turn declared none
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record AiChatTurnAcceptedDto(
        int turnId,
        int sessionId,
        String generationHandle,
        String status,
        AiChatQueryScopeDto scope) {

    /** Creates an accepted turn that declared no query scope. */
    public AiChatTurnAcceptedDto(
            int turnId, int sessionId, String generationHandle, String status) {
        this(turnId, sessionId, generationHandle, status, null);
    }
}
