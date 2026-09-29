package ooo.klae.connex.backend.util;

import java.net.InetAddress;

/** Classifies special-use destinations, including IPv4 addresses embedded in IPv6 transition forms. */
public final class OutboundAddressSafety {

    private OutboundAddressSafety() {
    }

    /** Returns whether an address reaches a private, reserved, or special-use destination. */
    public static boolean isInternalAddress(InetAddress address) {
        if (address.isLoopbackAddress() || address.isAnyLocalAddress()
                || address.isSiteLocalAddress() || address.isLinkLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 16) {
            if ((bytes[0] & 0xFE) == 0xFC || isSpecialUseIpv6(bytes)) {
                return true;
            }
            return containsSpecialUseEmbeddedIpv4(bytes);
        }
        if (bytes.length == 4) {
            return isSpecialUseIpv4(bytes);
        }
        return false;
    }

    private static boolean containsSpecialUseEmbeddedIpv4(byte[] address) {
        if (isIpv4Compatible(address) || isIpv4Mapped(address) || isNat64WellKnown(address)) {
            return isSpecialUseIpv4(address, 12);
        }
        if (isNat64LocalUse(address)) {
            return !hasRfc6052NullOctet(address)
                || isSpecialUseIpv4(rfc6052Prefix48Ipv4(address));
        }
        if (isSixToFour(address)) {
            return isSpecialUseIpv4(address, 2);
        }
        if (isIsatap(address)) {
            return isSpecialUseIpv4(address, 12);
        }
        if (isTeredo(address)) {
            return isSpecialUseIpv4(address, 4)
                || isSpecialUseIpv4(deobfuscateIpv4(address, 12));
        }
        return false;
    }

    private static boolean isSpecialUseIpv4(byte[] address) {
        return isSpecialUseIpv4(address, 0);
    }

    private static boolean isSpecialUseIpv4(byte[] address, int offset) {
        int first = unsigned(address[offset]);
        int second = unsigned(address[offset + 1]);
        int third = unsigned(address[offset + 2]);
        return first == 0 || first == 10 || first == 127 || first >= 224
            || first == 100 && second >= 64 && second <= 127
            || first == 169 && second == 254
            || first == 172 && second >= 16 && second <= 31
            || first == 192 && second == 0 && third == 0
            || first == 192 && second == 0 && third == 2
            || first == 192 && second == 168
            || first == 198 && (second == 18 || second == 19)
            || first == 198 && second == 51 && third == 100
            || first == 203 && second == 0 && third == 113;
    }

    private static byte[] rfc6052Prefix48Ipv4(byte[] address) {
        return new byte[] { address[6], address[7], address[9], address[10] };
    }

    private static byte[] deobfuscateIpv4(byte[] address, int offset) {
        return new byte[] {
            (byte) ~address[offset],
            (byte) ~address[offset + 1],
            (byte) ~address[offset + 2],
            (byte) ~address[offset + 3]
        };
    }

    private static boolean hasRfc6052NullOctet(byte[] address) {
        return address[8] == 0;
    }

    private static boolean isIpv4Compatible(byte[] address) {
        return allZero(address, 0, 12);
    }

    private static boolean isIpv4Mapped(byte[] address) {
        return allZero(address, 0, 10)
            && unsigned(address[10]) == 255
            && unsigned(address[11]) == 255;
    }

    private static boolean isNat64WellKnown(byte[] address) {
        return unsigned(address[0]) == 0
            && unsigned(address[1]) == 100
            && unsigned(address[2]) == 255
            && unsigned(address[3]) == 155
            && allZero(address, 4, 12);
    }

    private static boolean isNat64LocalUse(byte[] address) {
        return unsigned(address[0]) == 0
            && unsigned(address[1]) == 100
            && unsigned(address[2]) == 255
            && unsigned(address[3]) == 155
            && unsigned(address[4]) == 0
            && unsigned(address[5]) == 1;
    }

    private static boolean isSixToFour(byte[] address) {
        return unsigned(address[0]) == 32 && unsigned(address[1]) == 2;
    }

    private static boolean isIsatap(byte[] address) {
        int marker = unsigned(address[8]);
        return (marker == 0 || marker == 2)
            && unsigned(address[9]) == 0
            && unsigned(address[10]) == 94
            && unsigned(address[11]) == 254;
    }

    private static boolean isTeredo(byte[] address) {
        return unsigned(address[0]) == 32
            && unsigned(address[1]) == 1
            && address[2] == 0
            && address[3] == 0;
    }

    private static boolean isSpecialUseIpv6(byte[] address) {
        return unsigned(address[0]) == 1 && allZero(address, 1, 8)
            || unsigned(address[0]) == 32 && unsigned(address[1]) == 1
                && unsigned(address[2]) == 13 && unsigned(address[3]) == 184
            || unsigned(address[0]) == 32 && unsigned(address[1]) == 1
                && unsigned(address[2]) == 0 && unsigned(address[3]) == 2
            || unsigned(address[0]) == 32 && unsigned(address[1]) == 1
                && unsigned(address[2]) == 0
                && unsigned(address[3]) >= 32 && unsigned(address[3]) <= 47;
    }

    private static boolean allZero(byte[] address, int start, int end) {
        for (int index = start; index < end; index++) {
            if (address[index] != 0) return false;
        }
        return true;
    }

    private static int unsigned(byte value) {
        return value & 0xFF;
    }
}
