package ooo.klae.connex.backend.sso;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.util.OutboundAddressSafety;

/**
 * Guards outbound SSO discovery/metadata fetches against SSRF. An admin-supplied OIDC issuer
 * is fetched server-side ({@code /.well-known/openid-configuration}), so it must be an absolute
 * HTTPS URL (HTTP requires the explicit private-issuer exemption) whose host does not resolve to
 * a loopback, link-local, private (RFC1918/CGNAT/ULA),
 * wildcard, or multicast address — blocking internal services and the cloud metadata endpoint
 * ({@code 169.254.169.254}).
 */
public final class SsoUrlSafety {

    private SsoUrlSafety() {
    }

    /**
     * Validates the syntax of an admin-supplied URL at configuration time, throwing when it is not
     * a well-formed absolute http(s) URL. DNS resolution is deliberately deferred to
     * {@link SsoHttpClient}, both because the host may not be resolvable from the request thread
     * and because a save-time DNS check is defeated by rebinding.
     * @param url the admin-supplied URL
     * @throws BadRequestException when the URL is malformed or not http(s)
     */
    public static void requireValidHttpUrl(String url) {
        if (parseHttpHost(url) == null) {
            throw new BadRequestException("The issuer URL must be an absolute http(s) URL");
        }
    }

    /**
     * Whether a URL is a well-formed absolute http(s) URL, without resolving its host.
     * @param url the URL to check
     * @return true when the URL is syntactically usable as a server-side destination
     */
    static boolean isWellFormedHttpUrl(String url) {
        return parseHttpHost(url) != null;
    }

    /**
     * Resolves a destination and returns every validated address, so the transport can pin the
     * whole answer and still fail over between them.
     * @param url the destination URL
     * @param allowPrivate when true, HTTP URLs and private or loopback answers are permitted
     * @param resolver the host resolver to consult
     * @return the validated addresses, in resolution order
     * @throws UnknownHostException when the URL is malformed, unresolvable, or refused by policy
     */
    static InetAddress[] resolveFetchableHttpUrl(String url, boolean allowPrivate, HostResolver resolver)
            throws UnknownHostException {
        String host = parseHttpHost(url);
        if (host == null || (!allowPrivate && !"https".equalsIgnoreCase(URI.create(url.trim()).getScheme()))) {
            throw new RefusedDestinationException("OIDC destination is not a permitted URL");
        }
        InetAddress[] addresses = resolver.resolve(host);
        if (addresses == null || addresses.length == 0) {
            throw new UnknownHostException("OIDC destination has no addresses");
        }
        for (InetAddress address : addresses) {
            if (address == null || (!allowPrivate && OutboundAddressSafety.isInternalAddress(address))) {
                throw new RefusedDestinationException("OIDC destination is not permitted");
            }
        }
        return addresses.clone();
    }

    /** A malformed, cleartext, or private destination refused before egress. */
    static final class RefusedDestinationException extends UnknownHostException {
        RefusedDestinationException(String message) {
            super(message);
        }
    }

    @FunctionalInterface
    interface HostResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private static String parseHttpHost(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (RuntimeException e) {
            return null;
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            return null;
        }
        if (uri.getRawUserInfo() != null || uri.getRawFragment() != null
                || uri.getPort() == 0 || uri.getPort() > 65535) {
            return null;
        }
        String host = uri.getHost();
        return host == null || host.isBlank() ? null : host;
    }
}
