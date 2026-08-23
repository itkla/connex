package ooo.klae.connex.backend.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One viewer-safe milestone derived from durable assistant execution state.
 *
 * <p>Also serialized verbatim into the durable {@code structured_json} answer document, which is
 * revalidated on read with exact-key-count checks. Property inclusion is pinned to ALWAYS so the
 * application-wide {@code non_null} inclusion cannot drop the null {@code count} that every
 * non-counting milestone carries and silently invalidate the stored document.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record AiChatProgressItemDto(
        int seq,
        String source,
        String status,
        Integer count,
        boolean truncated) {
}
