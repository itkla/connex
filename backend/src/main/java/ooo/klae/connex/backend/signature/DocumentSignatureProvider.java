package ooo.klae.connex.backend.signature;

import java.util.Map;
import java.util.Optional;

/**
 * Provider-neutral SPI for commercial-document signature envelopes.
 * Networked outbound execution remains disabled until a durable dispatcher can invoke it outside
 * delivery metadata transactions; webhook parsing is independently available to adapters.
 */
public interface DocumentSignatureProvider {
    /** Returns the stable provider key persisted with envelopes. */
    String key();

    /** Creates or refreshes provider recipient delivery state without network I/O in this release. */
    SendOutcome send(SendCommand command);

    /** Voids provider state without network I/O in this release. */
    void voidEnvelope(VoidCommand command);

    /** Authenticates and translates one provider webhook, or returns empty when unsupported. */
    Optional<ProviderEvent> parseWebhook(
            String provider, Map<String, String> headers, byte[] body);
}
