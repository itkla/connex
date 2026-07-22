package ooo.klae.connex.backend.mail;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Set;

import org.springframework.stereotype.Component;

import ooo.klae.connex.backend.exceptions.BadRequestException;

/**
 * Resolves and validates workspace-managed SMTP destinations immediately before use.
 */
@Component
public class SmtpDestinationGuard {

    private static final Set<Integer> ALLOWED_SMTP_PORTS = Set.of(25, 465, 587, 2525);

    private final MailProperties mailProperties;

    public SmtpDestinationGuard(MailProperties mailProperties) {
        this.mailProperties = mailProperties;
    }

    /**
     * Resolves a workspace-managed destination for a send and returns the address to pin.
     *
     * @param config the resolved transport configuration
     * @return the validated address, or null for trusted instance and explicitly internal transports
     */
    public InetAddress resolveForSend(ResolvedMailConfig config) {
        if (!config.workspaceSupplied()) {
            return null;
        }
        return requirePublicDestination(config.host(), config.port());
    }

    /**
     * Validates a workspace-managed host and port and returns the resolved address to pin.
     *
     * @param host the SMTP host
     * @param port the SMTP port
     * @return the validated address, or null when internal hosts are explicitly allowed
     */
    public InetAddress requirePublicDestination(String host, int port) {
        if (mailProperties.isAllowInternalHosts()) {
            return null;
        }
        if (!ALLOWED_SMTP_PORTS.contains(port)) {
            throw new BadRequestException("SMTP port must be one of 25, 465, 587, or 2525");
        }
        if (host == null || host.isBlank()) {
            throw new BadRequestException("The SMTP host could not be resolved");
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host.trim());
        } catch (UnknownHostException exception) {
            throw new BadRequestException("The SMTP host could not be resolved");
        }
        if (addresses.length == 0) {
            throw new BadRequestException("The SMTP host could not be resolved");
        }
        for (InetAddress address : addresses) {
            if (isInternalAddress(address)) {
                throw new BadRequestException(
                    "The SMTP host must be a public server; private and loopback addresses are not allowed");
            }
        }
        return addresses[0];
    }

    /** Validates a destination using the instance SMTP port when the workspace omits one. */
    public InetAddress requirePublicDestination(String host, Integer port) {
        return requirePublicDestination(host, port == null ? mailProperties.getPort() : port);
    }

    static boolean isInternalAddress(InetAddress address) {
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
