package ooo.klae.connex.backend.ai.assistant;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The freshness and subtitle one cited record showed while the answering turn was running.
 *
 * <p>Evidence is only honest if it describes the record the answer was written against. Both values
 * are therefore captured at answer time and stored beside the citation handle, while authorization,
 * visibility, and record identity stay live reads on every transcript projection.
 *
 * <p>Inclusion is pinned to ALWAYS so the application-wide {@code non_null} inclusion cannot drop a
 * null instant or subtitle and turn a real snapshot into an absent one on read.
 *
 * @param asOf ISO-8601 instant the record was last updated when the turn read it, or null
 * @param detail bounded subtitle the record carried when the turn read it, or null
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record AiChatRecordObservation(String asOf, String detail) {
}
