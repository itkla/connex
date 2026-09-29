package ooo.klae.connex.backend.ai.egress;

import java.net.IDN;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Objects;

import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.core5.net.InetAddressUtils;

/**
 * Resolves one expected provider host to its previously validated addresses. Pins and lookups use
 * the same lowercase ASCII/IDN key, without an IPv6 bracket pair or a DNS root dot.
 */
public final class PinnedHostDnsResolver implements DnsResolver {
    private final String host;
    private final InetAddress[] addresses;

    public PinnedHostDnsResolver(String host, InetAddress... addresses) {
        this.host = canonicalHost(Objects.requireNonNull(host, "host"));
        this.addresses = Objects.requireNonNull(addresses, "addresses").clone();
        if (this.addresses.length == 0) {
            throw new IllegalArgumentException("addresses");
        }
        for (InetAddress address : this.addresses) {
            Objects.requireNonNull(address, "address");
        }
    }

    @Override
    public InetAddress[] resolve(String requestedHost) throws UnknownHostException {
        requireExpectedHost(requestedHost);
        return addresses.clone();
    }

    @Override
    public String resolveCanonicalHostname(String requestedHost) throws UnknownHostException {
        requireExpectedHost(requestedHost);
        return host;
    }

    private void requireExpectedHost(String requestedHost) throws UnknownHostException {
        String requestedKey;
        try {
            requestedKey = canonicalHost(requestedHost);
        } catch (IllegalArgumentException exception) {
            throw new UnknownHostException("AI provider transport attempted an unvalidated host");
        }
        if (!host.equals(requestedKey)) {
            throw new UnknownHostException("AI provider transport attempted an unvalidated host");
        }
    }

    private static String canonicalHost(String host) {
        if (host == null || host.isEmpty()) {
            throw new IllegalArgumentException("Invalid provider hostname");
        }
        String key = host;
        if (key.indexOf(':') >= 0) {
            if (key.startsWith("[") && key.endsWith("]")) {
                key = key.substring(1, key.length() - 1);
            }
            if (!InetAddressUtils.isIPv6(key)) {
                throw new IllegalArgumentException("Invalid provider hostname");
            }
        } else {
            key = IDN.toASCII(key, IDN.USE_STD3_ASCII_RULES);
            if (key.endsWith(".")) {
                key = key.substring(0, key.length() - 1);
            }
            if (key.isEmpty()) {
                throw new IllegalArgumentException("Invalid provider hostname");
            }
        }
        return key.toLowerCase(Locale.ROOT);
    }
}
