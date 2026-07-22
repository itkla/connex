package ooo.klae.connex.backend.dto;

/**
 * The one-time reveal of a freshly issued or rotated webhook credential pair. The {@code token} is
 * placed in the provider's webhook URL and the {@code secret} is configured as the provider's signing
 * secret; both are returned exactly once and never stored in recoverable form. The provider must send
 * the HMAC-SHA256 of each raw body, hex-encoded, in {@code signatureHeader}.
 * @param token the raw webhook URL token
 * @param secret the raw HMAC signing secret
 * @param signatureHeader the header the provider must carry the body signature in
 */
public record DeliveryWebhookTokenDto(String token, String secret, String signatureHeader) {
}
