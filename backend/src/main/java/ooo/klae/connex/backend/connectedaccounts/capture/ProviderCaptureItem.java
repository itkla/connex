package ooo.klae.connex.backend.connectedaccounts.capture;

import java.time.Instant;
import java.util.List;

/**
 * Provider-neutral immutable interaction page item.
 */
public record ProviderCaptureItem(
    String sourceId,
    String sourceVersion,
    String conversationId,
    String interactionType,
    String subject,
    String body,
    Instant occurredAt,
    Instant endedAt,
    boolean privateItem,
    boolean tombstone,
    List<ProviderCaptureParticipant> participants
) {
    /** Defensively copies participants. */
    public ProviderCaptureItem {
        participants = List.copyOf(participants);
    }
}
