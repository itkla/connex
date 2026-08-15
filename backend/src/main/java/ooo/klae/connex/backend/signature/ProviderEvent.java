package ooo.klae.connex.backend.signature;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;

/** Authenticated provider callback translated into the document-delivery event vocabulary. */
public record ProviderEvent(
        int workspaceId,
        String providerEnvelopeId,
        String providerRecipientId,
        String externalEventId,
        String eventType,
        String detail,
        LocalDateTime occurredAt,
        Optional<ProviderSignedArtifact> signedArtifact) {

    /** Rejects ambiguous null optionals at the authenticated provider boundary. */
    public ProviderEvent {
        Objects.requireNonNull(signedArtifact, "signedArtifact");
    }
}
