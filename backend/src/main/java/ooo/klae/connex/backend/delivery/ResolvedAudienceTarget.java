package ooo.klae.connex.backend.delivery;

import java.util.Objects;

/**
 * The complete current destination for one audience push, resolved from a single workspace connector
 * configuration read immediately before provider egress.
 * @param provider the endpoint and decrypted credential used by the connector
 * @param externalListId the external list that receives the audience
 * @param configId the connector configuration row resolved for the push
 * @param configVersion the configuration generation resolved for the push
 */
public record ResolvedAudienceTarget(
        ResolvedDeliveryProvider provider,
        String externalListId,
        int configId,
        long configVersion) {

    public ResolvedAudienceTarget {
        Objects.requireNonNull(provider, "provider");
        if (externalListId == null || externalListId.isBlank()) {
            throw new IllegalArgumentException("externalListId is required");
        }
        if (configId <= 0 || configVersion <= 0) {
            throw new IllegalArgumentException("connector configuration marker is required");
        }
    }
}
