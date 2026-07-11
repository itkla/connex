package ooo.klae.connex.backend.ai.egress;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Objects;

import org.apache.hc.client5.http.DnsResolver;

/** Resolves exactly one expected provider hostname to one previously validated address. */
public final class PinnedHostDnsResolver implements DnsResolver {
    private final String host;
    private final InetAddress address;

    public PinnedHostDnsResolver(String host, InetAddress address) {
        this.host = Objects.requireNonNull(host, "host");
        this.address = Objects.requireNonNull(address, "address");
    }

    @Override
    public InetAddress[] resolve(String requestedHost) throws UnknownHostException {
        requireExpectedHost(requestedHost);
        return new InetAddress[] { address };
    }

    @Override
    public String resolveCanonicalHostname(String requestedHost) throws UnknownHostException {
        requireExpectedHost(requestedHost);
        return host;
    }

    private void requireExpectedHost(String requestedHost) throws UnknownHostException {
        if (requestedHost == null || !host.equalsIgnoreCase(requestedHost)) {
            throw new UnknownHostException("AI provider transport attempted an unvalidated host");
        }
    }
}
