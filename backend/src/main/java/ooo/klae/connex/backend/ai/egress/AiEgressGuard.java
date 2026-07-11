package ooo.klae.connex.backend.ai.egress;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Set;

import ooo.klae.connex.backend.ai.provider.AiProviderException;

/**
 * Fail-closed outbound guard for AI provider calls. Cloud-provider destinations
 * must use a closed hostname allowlist and every destination is resolved
 * immediately before a send. Unless an organization explicitly allows a private
 * OpenAI-compatible endpoint, every resolved A or AAAA record must be public.
 */
public final class AiEgressGuard {

    private AiEgressGuard() {
    }

    /**
     * Requires an allowlisted host that resolves only to public addresses.
     * @param host provider host
     * @param allowedHosts closed set of provider hosts
     * @throws AiProviderException when the host is absent, disallowed, unresolved, or unsafe
     */
    public static void requireAllowlistedHost(String host, Set<String> allowedHosts) {
        requireAllowedHost(host, allowedHosts);
        requireFetchableHost(host, false);
    }

    /**
     * Requires a resolvable host and optionally permits private addresses.
     * @param host provider host
     * @param allowPrivate whether blocked private and special-use addresses are permitted
     * @throws AiProviderException when the host is absent, unresolved, or unsafe
     */
    public static void requireFetchableHost(String host, boolean allowPrivate) {
        if (host == null || host.isBlank()) {
            throw new AiProviderException("AI provider egress host is required");
        }
        try {
            requireFetchableHost(host, allowPrivate, InetAddress.getAllByName(host.trim()));
        } catch (UnknownHostException exception) {
            throw new AiProviderException("AI provider egress host could not be resolved", exception);
        }
    }

    static void requireAllowlistedHost(String host, Set<String> allowedHosts, InetAddress[] addresses) {
        requireAllowedHost(host, allowedHosts);
        requireFetchableHost(host, false, addresses);
    }

    static void requireFetchableHost(String host, boolean allowPrivate, InetAddress[] addresses) {
        if (host == null || host.isBlank()) {
            throw new AiProviderException("AI provider egress host is required");
        }
        if (allowPrivate) {
            requirePrivateAddresses(addresses);
            return;
        }
        requirePublicAddresses(host, addresses);
    }

    static void requirePublicAddresses(String host, InetAddress[] addresses) {
        if (host == null || host.isBlank()) {
            throw new AiProviderException("AI provider egress host is required");
        }
        requireResolvedAddresses(addresses);
        for (InetAddress address : addresses) {
            if (isBlocked(address)) {
                throw new AiProviderException("AI provider egress host resolved to a blocked address");
            }
        }
    }

    static void requirePrivateAddresses(InetAddress[] addresses) {
        requireResolvedAddresses(addresses);
        for (InetAddress address : addresses) {
            if (!isPrivateReachable(address)) {
                throw new AiProviderException("AI provider internal endpoint must resolve to a private address");
            }
        }
    }

    static boolean isPrivateReachable(InetAddress address) {
        if (address.isMulticastAddress() || address.isAnyLocalAddress()) {
            return false;
        }
        if (address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int first = bytes[0] & 0xFF;
            int second = bytes[1] & 0xFF;
            return first == 100 && second >= 64 && second <= 127;
        }
        return (bytes[0] & 0xFE) == 0xFC;
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

    private static void requireAllowedHost(String host, Set<String> allowedHosts) {
        if (host == null || host.isBlank() || allowedHosts == null
                || allowedHosts.stream().noneMatch(allowed -> allowed != null && allowed.equalsIgnoreCase(host.trim()))) {
            throw new AiProviderException("AI provider egress host is not allowed");
        }
    }

    private static void requireResolvedAddresses(InetAddress[] addresses) {
        if (addresses == null || addresses.length == 0) {
            throw new AiProviderException("AI provider egress host resolved no addresses");
        }
        for (InetAddress address : addresses) {
            if (address == null) {
                throw new AiProviderException("AI provider egress host resolved to a blocked address");
            }
        }
    }
}
