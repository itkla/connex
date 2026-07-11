package ooo.klae.connex.backend.ai.egress;

import java.net.InetAddress;
import java.util.Objects;

import org.springframework.stereotype.Component;

import ooo.klae.connex.backend.ai.AiProperties;
import ooo.klae.connex.backend.ai.provider.AiProviderException;

/** Resolves configured AI endpoint hosts against the runtime egress address policy. */
@Component
public class AiEndpointAddressValidator {
    private final Nat64PrefixPolicy nat64PrefixPolicy;

    public AiEndpointAddressValidator(AiProperties aiProperties) {
        Objects.requireNonNull(aiProperties, "aiProperties");
        this.nat64PrefixPolicy = new Nat64PrefixPolicy(aiProperties.getNat64Prefixes());
    }

    /**
     * Reports whether the host currently resolves entirely within its permitted address class.
     * @param host configured endpoint host
     * @param allowPrivate whether only private endpoint addresses are permitted
     * @return true when the runtime egress guard accepts the current resolution
     */
    public boolean isFetchable(String host, boolean allowPrivate) {
        try {
            resolveFetchable(host, allowPrivate);
            return true;
        } catch (AiProviderException exception) {
            return false;
        }
    }

    /**
     * Resolves an organization-configured endpoint and returns the validated address to pin.
     * @param host configured endpoint host
     * @param allowPrivate whether only private endpoint addresses are permitted
     * @return first validated address
     */
    public InetAddress resolveFetchable(String host, boolean allowPrivate) {
        return AiEgressGuard.resolveOrgConfiguredHost(host, allowPrivate, nat64PrefixPolicy);
    }
}
