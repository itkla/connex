package ooo.klae.connex.backend.ai.egress;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Validated RFC 6052 network-specific prefixes used to classify translated IPv6 destinations. */
final class Nat64PrefixPolicy {
    private static final Set<Integer> PREFIX_LENGTHS = Set.of(32, 40, 48, 56, 64, 96);

    private final List<Nat64Prefix> prefixes;

    Nat64PrefixPolicy(String configuredPrefixes) {
        this.prefixes = parse(configuredPrefixes);
    }

    TranslationClass classify(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (bytes.length != 16) {
            return null;
        }
        for (Nat64Prefix prefix : prefixes) {
            if (!matchesPrefix(bytes, prefix.address(), prefix.length())) {
                continue;
            }
            byte[] translated = translatedIpv4(bytes, prefix.length());
            if (translated == null || AiEgressGuard.isBlockedIpv4(translated, 0)) {
                return translated != null && AiEgressGuard.isPrivateReachableIpv4(translated, 0)
                        ? TranslationClass.PRIVATE
                        : TranslationClass.BLOCKED;
            }
            return TranslationClass.PUBLIC;
        }
        return null;
    }

    @Override
    public String toString() {
        return "Nat64PrefixPolicy[configuredPrefixes=" + prefixes.size() + "]";
    }

    private static List<Nat64Prefix> parse(String configuredPrefixes) {
        if (configuredPrefixes == null || configuredPrefixes.isBlank()) {
            return List.of();
        }
        List<Nat64Prefix> parsed = new ArrayList<>();
        for (String configured : configuredPrefixes.split(",", -1)) {
            Nat64Prefix candidate = parsePrefix(configured);
            for (Nat64Prefix existing : parsed) {
                if (overlaps(existing, candidate)) {
                    throw invalidConfiguration();
                }
            }
            parsed.add(candidate);
        }
        return List.copyOf(parsed);
    }

    private static Nat64Prefix parsePrefix(String configured) {
        if (configured == null) {
            throw invalidConfiguration();
        }
        String value = configured.strip();
        int slash = value.lastIndexOf('/');
        if (slash <= 0 || slash == value.length() - 1 || value.indexOf('/') != slash) {
            throw invalidConfiguration();
        }
        int length;
        try {
            length = Integer.parseInt(value.substring(slash + 1));
        } catch (NumberFormatException exception) {
            throw invalidConfiguration();
        }
        if (!PREFIX_LENGTHS.contains(length)) {
            throw invalidConfiguration();
        }
        InetAddress literal;
        try {
            literal = InetAddress.ofLiteral(value.substring(0, slash));
        } catch (IllegalArgumentException exception) {
            throw invalidConfiguration();
        }
        if (!(literal instanceof Inet6Address) || value.substring(0, slash).contains("%")) {
            throw invalidConfiguration();
        }
        byte[] address = literal.getAddress();
        if (!hasZeroHostBits(address, length) || address[8] != 0) {
            throw invalidConfiguration();
        }
        return new Nat64Prefix(address, length);
    }

    private static boolean overlaps(Nat64Prefix left, Nat64Prefix right) {
        int commonLength = Math.min(left.length(), right.length());
        return matchesPrefix(left.address(), right.address(), commonLength);
    }

    private static boolean hasZeroHostBits(byte[] address, int prefixLength) {
        for (int index = prefixLength / 8; index < address.length; index++) {
            if (address[index] != 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesPrefix(byte[] address, byte[] prefix, int prefixLength) {
        int bytes = prefixLength / 8;
        for (int index = 0; index < bytes; index++) {
            if (address[index] != prefix[index]) {
                return false;
            }
        }
        return true;
    }

    private static byte[] translatedIpv4(byte[] address, int prefixLength) {
        if (prefixLength < 96 && address[8] != 0) {
            return null;
        }
        return switch (prefixLength) {
            case 32 -> new byte[] { address[4], address[5], address[6], address[7] };
            case 40 -> new byte[] { address[5], address[6], address[7], address[9] };
            case 48 -> new byte[] { address[6], address[7], address[9], address[10] };
            case 56 -> new byte[] { address[7], address[9], address[10], address[11] };
            case 64 -> new byte[] { address[9], address[10], address[11], address[12] };
            case 96 -> new byte[] { address[12], address[13], address[14], address[15] };
            default -> null;
        };
    }

    private static IllegalStateException invalidConfiguration() {
        return new IllegalStateException("AI NAT64 prefixes must be non-overlapping canonical IPv6 CIDRs with RFC 6052 lengths");
    }

    enum TranslationClass {
        PUBLIC,
        PRIVATE,
        BLOCKED
    }

    private record Nat64Prefix(byte[] address, int length) {
        private Nat64Prefix {
            address = address.clone();
        }

        @Override
        public byte[] address() {
            return address.clone();
        }
    }
}
