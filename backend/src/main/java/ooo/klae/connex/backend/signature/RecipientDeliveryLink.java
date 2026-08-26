package ooo.klae.connex.backend.signature;

/** Optional Connex-delivered bearer link returned by a provider recipient outcome. */
public record RecipientDeliveryLink(
        String tokenHash,
        String url) {
}
