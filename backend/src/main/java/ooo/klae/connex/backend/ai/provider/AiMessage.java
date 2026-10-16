package ooo.klae.connex.backend.ai.provider;

import java.util.Objects;
import java.util.List;

import ooo.klae.connex.backend.ai.masking.OutboundSegment;

/**
 * Single chat message accepted by a provider adapter.
 * @param role provider role, currently {@code user} or {@code assistant}
 * @param content message text
 * @param segments positional origins covering the complete message text
 */
public record AiMessage(String role, String content,
        @com.fasterxml.jackson.annotation.JsonIgnore List<OutboundSegment> segments) {

    /** Unclassified conversation text remains tenant data. */
    public AiMessage(String role, String content) {
        this(role, content, List.of(new OutboundSegment(content, OutboundSegment.Origin.TENANT_DATA)));
    }

    public AiMessage {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(content, "content");
        segments = List.copyOf(Objects.requireNonNull(segments, "segments"));
        if (!segments.stream().map(OutboundSegment::text)
                .collect(java.util.stream.Collectors.joining()).equals(content)) {
            throw new IllegalArgumentException("AI message origins must cover its exact text");
        }
    }

    @Override
    public String toString() {
        return "AiMessage[role=" + role + ", content=<redacted>]";
    }
}
