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
        resolveFetchableHost(host, allowPrivate);
    }

    /**
     * Resolves and validates a provider host, returning the address a transport must pin.
     * @param host provider host
     * @param allowPrivate whether only private endpoint addresses are permitted
     * @return the first validated address
     */
    public static InetAddress resolveFetchableHost(String host, boolean allowPrivate) {
        if (host == null || host.isBlank()) {
            throw new AiProviderException("AI provider egress host is required");
        }
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host.trim());
            requireFetchableHost(host, allowPrivate, addresses);
            return addresses[0];
        } catch (UnknownHostException exception) {
            throw new AiProviderException("AI provider egress host could not be resolved", exception);
        }
    }

    static InetAddress resolveOrgConfiguredHost(
            String host, boolean allowPrivate, Nat64PrefixPolicy nat64PrefixPolicy) {
        if (host == null || host.isBlank()) {
            throw new AiProviderException("AI provider egress host is required");
        }
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host.trim());
            requireOrgConfiguredHost(host, allowPrivate, addresses, nat64PrefixPolicy);
            return addresses[0];
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

    static void requireOrgConfiguredHost(
            String host,
            boolean allowPrivate,
            InetAddress[] addresses,
            Nat64PrefixPolicy nat64PrefixPolicy) {
        if (host == null || host.isBlank()) {
            throw new AiProviderException("AI provider egress host is required");
        }
        if (nat64PrefixPolicy == null) {
            throw new AiProviderException("AI provider egress policy is unavailable");
        }
        requireResolvedAddresses(addresses);
        for (InetAddress address : addresses) {
            Nat64PrefixPolicy.TranslationClass translation = nat64PrefixPolicy.classify(address);
            if (allowPrivate) {
                boolean privateDestination = translation == Nat64PrefixPolicy.TranslationClass.PRIVATE
                        || translation == null && isPrivateReachable(address);
                if (!privateDestination) {
                    throw new AiProviderException("AI provider internal endpoint must resolve to a private address");
                }
                continue;
            }
            boolean blockedDestination = isBlocked(address)
                    || translation != null && translation != Nat64PrefixPolicy.TranslationClass.PUBLIC
                    || translation == null && containsBlockedRfc6052Candidate(address.getAddress());
            if (blockedDestination) {
                throw new AiProviderException("AI provider egress host resolved to a blocked address");
            }
        }
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
            return isPrivateReachableIpv4(bytes, 0);
        }
        return (bytes[0] & 0xFE) == 0xFC || containsPrivateEmbeddedIpv4(bytes);
    }

    static boolean isBlocked(InetAddress address) {
        if (address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isAnyLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            return isBlockedIpv4(bytes, 0);
        }
        return (bytes[0] & 0xFE) == 0xFC
            || isSpecialUseIpv6(bytes)
            || containsBlockedEmbeddedIpv4(bytes);
    }

    private static boolean containsBlockedEmbeddedIpv4(byte[] address) {
        if (isIpv4Compatible(address)) {
            return isBlockedIpv4(address, 12);
        }
        if (isIpv4Mapped(address)) {
            return isBlockedIpv4(address, 12);
        }
        if (isNat64WellKnown(address)) {
            return isBlockedIpv4(address, 12);
        }
        if (isNat64LocalUse(address)) {
            return !hasRfc6052NullOctet(address) || isBlockedRfc6052Prefix48(address);
        }
        if (isSixToFour(address)) {
            return isBlockedIpv4(address, 2);
        }
        if (isIsatap(address)) {
            return isBlockedIpv4(address, 12);
        }
        if (isTeredo(address)) {
            return isBlockedIpv4(address, 4) || isBlockedObfuscatedIpv4(address, 12);
        }
        return false;
    }

    private static boolean containsPrivateEmbeddedIpv4(byte[] address) {
        if (isIpv4Compatible(address) || isIpv4Mapped(address) || isNat64WellKnown(address)
                || isIsatap(address)) {
            return isPrivateReachableIpv4(address, 12);
        }
        if (isNat64LocalUse(address)) {
            return hasRfc6052NullOctet(address) && isPrivateRfc6052Prefix48(address);
        }
        if (isSixToFour(address)) {
            return isPrivateReachableIpv4(address, 2);
        }
        if (isTeredo(address)) {
            return isPrivateReachableIpv4(address, 4) || isPrivateObfuscatedIpv4(address, 12);
        }
        return false;
    }

    private static boolean containsBlockedRfc6052Candidate(byte[] address) {
        if (address.length != 16 || !hasRfc6052NullOctet(address)) {
            return false;
        }
        if (isIpv4Compatible(address) || isIpv4Mapped(address) || isNat64WellKnown(address)
                || isNat64LocalUse(address)) {
            return false;
        }
        return isBlockedIpv4(rfc6052Ipv4(address, 32), 0)
                || isBlockedIpv4(rfc6052Ipv4(address, 40), 0)
                || isBlockedIpv4(rfc6052Ipv4(address, 48), 0)
                || isBlockedIpv4(rfc6052Ipv4(address, 56), 0)
                || isBlockedIpv4(rfc6052Ipv4(address, 64), 0)
                || isBlockedIpv4(rfc6052Ipv4(address, 96), 0);
    }

    private static byte[] rfc6052Ipv4(byte[] address, int prefixLength) {
        return switch (prefixLength) {
            case 32 -> new byte[] { address[4], address[5], address[6], address[7] };
            case 40 -> new byte[] { address[5], address[6], address[7], address[9] };
            case 48 -> new byte[] { address[6], address[7], address[9], address[10] };
            case 56 -> new byte[] { address[7], address[9], address[10], address[11] };
            case 64 -> new byte[] { address[9], address[10], address[11], address[12] };
            case 96 -> new byte[] { address[12], address[13], address[14], address[15] };
            default -> throw new IllegalArgumentException("Unsupported RFC 6052 prefix length");
        };
    }

    static boolean isBlockedIpv4(byte[] address, int offset) {
        int first = address[offset] & 0xFF;
        int second = address[offset + 1] & 0xFF;
        int third = address[offset + 2] & 0xFF;
        return first == 0
                || first == 10
                || first == 100 && second >= 64 && second <= 127
                || first == 127
                || first == 169 && second == 254
                || first == 172 && second >= 16 && second <= 31
                || first == 192 && second == 168
                || first == 192 && second == 0 && third == 0
                || first == 192 && second == 0 && third == 2
                || first == 198 && (second == 18 || second == 19)
                || first == 198 && second == 51 && third == 100
                || first == 203 && second == 0 && third == 113
                || first >= 224;
    }

    static boolean isPrivateReachableIpv4(byte[] address, int offset) {
        int first = address[offset] & 0xFF;
        int second = address[offset + 1] & 0xFF;
        return first == 10
                || first == 100 && second >= 64 && second <= 127
                || first == 127
                || first == 169 && second == 254
                || first == 172 && second >= 16 && second <= 31
                || first == 192 && second == 168;
    }

    private static boolean isBlockedObfuscatedIpv4(byte[] address, int offset) {
        byte[] decoded = deobfuscateIpv4(address, offset);
        return isBlockedIpv4(decoded, 0);
    }

    private static boolean isPrivateObfuscatedIpv4(byte[] address, int offset) {
        byte[] decoded = deobfuscateIpv4(address, offset);
        return isPrivateReachableIpv4(decoded, 0);
    }

    private static boolean isBlockedRfc6052Prefix48(byte[] address) {
        byte[] decoded = rfc6052Prefix48Ipv4(address);
        return isBlockedIpv4(decoded, 0);
    }

    private static boolean isPrivateRfc6052Prefix48(byte[] address) {
        byte[] decoded = rfc6052Prefix48Ipv4(address);
        return isPrivateReachableIpv4(decoded, 0);
    }

    private static byte[] rfc6052Prefix48Ipv4(byte[] address) {
        return new byte[] { address[6], address[7], address[9], address[10] };
    }

    private static boolean hasRfc6052NullOctet(byte[] address) {
        return address[8] == 0;
    }

    private static byte[] deobfuscateIpv4(byte[] address, int offset) {
        return new byte[] {
            (byte) ~address[offset],
            (byte) ~address[offset + 1],
            (byte) ~address[offset + 2],
            (byte) ~address[offset + 3]
        };
    }

    private static boolean isIpv4Compatible(byte[] address) {
        return allZero(address, 0, 12);
    }

    private static boolean isIpv4Mapped(byte[] address) {
        return allZero(address, 0, 10)
                && (address[10] & 0xFF) == 0xFF
                && (address[11] & 0xFF) == 0xFF;
    }

    private static boolean isNat64WellKnown(byte[] address) {
        return (address[0] & 0xFF) == 0x00
                && (address[1] & 0xFF) == 0x64
                && (address[2] & 0xFF) == 0xFF
                && (address[3] & 0xFF) == 0x9B
                && allZero(address, 4, 12);
    }

    private static boolean isNat64LocalUse(byte[] address) {
        return (address[0] & 0xFF) == 0x00
                && (address[1] & 0xFF) == 0x64
                && (address[2] & 0xFF) == 0xFF
                && (address[3] & 0xFF) == 0x9B
                && (address[4] & 0xFF) == 0x00
                && (address[5] & 0xFF) == 0x01;
    }

    private static boolean isSixToFour(byte[] address) {
        return (address[0] & 0xFF) == 0x20 && (address[1] & 0xFF) == 0x02;
    }

    private static boolean isIsatap(byte[] address) {
        int marker = address[8] & 0xFF;
        return (marker == 0x00 || marker == 0x02)
                && (address[9] & 0xFF) == 0x00
                && (address[10] & 0xFF) == 0x5E
                && (address[11] & 0xFF) == 0xFE;
    }

    private static boolean isTeredo(byte[] address) {
        return (address[0] & 0xFF) == 0x20
                && (address[1] & 0xFF) == 0x01
                && address[2] == 0
                && address[3] == 0;
    }

    private static boolean isSpecialUseIpv6(byte[] address) {
        return (address[0] & 0xFF) == 0x01 && allZero(address, 1, 8)
            || (address[0] & 0xFF) == 0x01 && allZero(address, 1, 7)
                && (address[7] & 0xFF) == 0x01
            || (address[0] & 0xFF) == 0x20 && (address[1] & 0xFF) == 0x01
                && address[2] == 0 && (address[3] & 0xFF) == 0x02
            || (address[0] & 0xFF) == 0x20 && (address[1] & 0xFF) == 0x01
                && (address[2] & 0xFF) == 0x0D && (address[3] & 0xFF) == 0xB8
            || (address[0] & 0xFF) == 0x20 && (address[1] & 0xFF) == 0x01
                && address[2] == 0 && (address[3] & 0xFF) >= 0x20 && (address[3] & 0xFF) <= 0x2F
            || (address[0] & 0xFF) == 0x3F && (address[1] & 0xFF) == 0xFF
                && (address[2] & 0xF0) == 0
            || (address[0] & 0xFF) == 0x5F;
    }

    private static boolean allZero(byte[] address, int start, int end) {
        for (int index = start; index < end; index++) {
            if (address[index] != 0) {
                return false;
            }
        }
        return true;
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
