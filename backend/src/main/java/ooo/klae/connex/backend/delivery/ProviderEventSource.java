package ooo.klae.connex.backend.delivery;

import java.util.List;
import java.util.Map;

/**
 * A delivery provider that emits delivery events (deliveries, bounces, complaints) back to Connex
 * over signed webhooks. An implementation authenticates a raw webhook body and translates the
 * provider's own payload into normalized {@link DeliveryEvent}s; all vendor-specific request,
 * response, and webhook mapping stays inside the one implementing adapter so a second event source
 * is a new adapter and nothing else.
 */
public interface ProviderEventSource extends DeliveryProvider {

    /**
     * Authenticates a raw webhook body against the provider's stored webhook secret. Implementations
     * verify a message authentication code or signature carried in {@code headers} over the exact
     * received bytes and must throw a {@link DeliveryProviderException} on any mismatch, never
     * returning normally for an unauthenticated body.
     * @param target the resolved provider configuration, carrying the decrypted webhook secret
     * @param rawBody the exact received webhook body bytes
     * @param headers the received webhook headers, keyed by lower-case name
     * @throws DeliveryProviderException when the signature is absent, malformed, or does not match
     */
    void verifySignature(ResolvedDeliveryProvider target, byte[] rawBody, Map<String, String> headers);

    /**
     * Translates a raw, already-authenticated webhook body into normalized delivery events. Events the
     * adapter does not recognize are dropped rather than surfaced.
     * @param rawBody the exact received webhook body bytes
     * @return the normalized delivery events, in payload order
     * @throws DeliveryProviderException when the body cannot be parsed
     */
    List<DeliveryEvent> translate(byte[] rawBody);
}
