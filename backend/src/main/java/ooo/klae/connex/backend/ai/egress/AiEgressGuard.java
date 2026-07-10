package ooo.klae.connex.backend.ai.egress;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;

import ooo.klae.connex.backend.ai.provider.AiProviderException;
import ooo.klae.connex.backend.ai.provider.bedrock.BedrockRegion;

/**
 * Fail-closed outbound guard for AI provider calls. Connex only permits the fixed Bedrock
 * runtime hostnames derived from {@link BedrockRegion}; immediately before each HTTPS send, all
 * resolved A/AAAA records must be public addresses and must not be loopback, link-local, private,
 * wildcard, multicast, CGNAT, or IPv6 ULA. Because the destination is a fixed AWS hostname reached
 * over HTTPS with standard TLS hostname verification that is never disabled, a rebound or poisoned
 * DNS answer pointing at an internal IP also fails the TLS handshake. The pre-connect re-vet plus
 * TLS verification is the rebinding-aware strategy; a JVM-wide {@code InetAddressResolverProvider}
 * IP pin is reserved for future hardening.
 */
public final class AiEgressGuard {

    private AiEgressGuard() {
    }

    /**
     * Requires that the host is an allowlisted Bedrock runtime host resolving only to public IPs.
     * @param host the derived Bedrock runtime host
     * @throws AiProviderException when the host is not allowlisted or resolution is unsafe
     */
    public static void requireFetchable(String host) {
        if (!isAllowedHost(host)) {
            throw new AiProviderException("AI provider egress host is not allowed");
        }
        try {
            requirePublicAddresses(host, InetAddress.getAllByName(host));
        } catch (UnknownHostException exception) {
            throw new AiProviderException("AI provider egress host could not be resolved", exception);
        }
    }

    static void requirePublicAddresses(String host, InetAddress[] addresses) {
        if (addresses == null || addresses.length == 0) {
            throw new AiProviderException("AI provider egress host resolved no addresses");
        }
        for (InetAddress address : addresses) {
            if (address == null || isBlocked(address)) {
                throw new AiProviderException("AI provider egress host resolved to a blocked address");
            }
        }
    }

    static boolean isBlocked(InetAddress address) {
        if (address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isAnyLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int first = bytes[0] & 0xFF;
            int second = bytes[1] & 0xFF;
            int third = bytes[2] & 0xFF;
            return first == 0
                    || first == 100 && second >= 64 && second <= 127
                    || first == 192 && second == 0 && third == 0
                    || first == 198 && (second == 18 || second == 19)
                    || first >= 240;
        }
        return (bytes[0] & 0xFE) == 0xFC;
    }

    private static boolean isAllowedHost(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        String normalized = host.trim();
        return Arrays.stream(BedrockRegion.values())
                .anyMatch(region -> region.host().equalsIgnoreCase(normalized));
    }
}
