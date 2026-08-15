package ooo.klae.connex.backend.dto;

import java.time.LocalDateTime;
import java.util.List;

import ooo.klae.connex.backend.beans.DocumentDelivery;

/** Authenticated document-delivery representation without bearer-token material. */
public record DocumentDeliveryDto(
        int id,
        int dealId,
        int documentId,
        String provider,
        String providerEnvelopeId,
        String status,
        String message,
        LocalDateTime expiresAt,
        Integer sentBy,
        LocalDateTime sentAt,
        LocalDateTime completedAt,
        LocalDateTime terminatedAt,
        String terminationReason,
        List<Recipient> recipients,
        List<Event> events,
        List<Artifact> artifacts) {

    /** Maps a fully hydrated envelope to its authenticated response shape. */
    public static DocumentDeliveryDto from(DocumentDelivery delivery) {
        return new DocumentDeliveryDto(
            delivery.getId(),
            delivery.getDealId(),
            delivery.getDocumentId(),
            delivery.getProvider(),
            delivery.getProviderEnvelopeId(),
            delivery.getStatus(),
            delivery.getMessage(),
            delivery.getExpiresAt(),
            delivery.getSentBy(),
            delivery.getSentAt(),
            delivery.getCompletedAt(),
            delivery.getTerminatedAt(),
            delivery.getTerminationReason(),
            delivery.getRecipients().stream().map(recipient -> new Recipient(
                recipient.getId(), recipient.getPersonId(), recipient.getName(), recipient.getEmail(),
                recipient.getRole(), recipient.getRecipientOrder(), recipient.getStatus(),
                recipient.getFirstViewedAt(), recipient.getDecidedAt(), recipient.getTypedName(),
                recipient.getDeclineReason())).toList(),
            delivery.getEvents().stream().map(event -> new Event(
                event.getId(), event.getRecipientId(), event.getEventType(), event.getSource(),
                event.getDetail(), event.getOccurredAt())).toList(),
            delivery.getArtifacts().stream().map(artifact -> new Artifact(
                artifact.getId(), artifact.getKind(), artifact.getContentType(),
                artifact.getByteLength(), artifact.getSha256(), artifact.getCreatedAt())).toList());
    }

    /** Frozen recipient identity and visible decision state. */
    public record Recipient(
            int id,
            Integer personId,
            String name,
            String email,
            String role,
            int recipientOrder,
            String status,
            LocalDateTime firstViewedAt,
            LocalDateTime decidedAt,
            String typedName,
            String declineReason) {
    }

    /** Append-only event representation. */
    public record Event(
            int id,
            Integer recipientId,
            String eventType,
            String source,
            String detail,
            LocalDateTime occurredAt) {
    }

    /** Immutable artifact metadata. */
    public record Artifact(
            int id,
            String kind,
            String contentType,
            long byteLength,
            String sha256,
            LocalDateTime createdAt) {
    }
}
