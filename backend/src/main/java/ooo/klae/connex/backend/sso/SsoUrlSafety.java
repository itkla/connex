package ooo.klae.connex.backend.sso;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

import ooo.klae.connex.backend.exceptions.BadRequestException;

/**
 * Guards outbound SSO discovery/metadata fetches against SSRF. An admin-supplied OIDC issuer
 * is fetched server-side ({@code /.well-known/openid-configuration}), so it must be an absolute
 * http(s) URL whose host does not resolve to a loopback, link-local, private (RFC1918/CGNAT/ULA),
 * wildcard, or multicast address — blocking internal services and the cloud metadata endpoint
 * ({@code 169.254.169.254}).
 */
public final class SsoUrlSafety {

    private SsoUrlSafety() {
    }

    /**
     * Validates the syntax of an admin-supplied URL at configuration time, throwing when it is not
     * a well-formed absolute http(s) URL. DNS resolution is deliberately deferred to
     * {@link #isFetchableHttpUrl(String, boolean)} at fetch time, both because the host may not be resolvable
     * from the request thread and because a save-time DNS check is defeated by rebinding.
     * @param url the admin-supplied URL
     * @throws BadRequestException when the URL is malformed or not http(s)
     */
    public static void requireValidHttpUrl(String url) {
        if (parseHttpHost(url) == null) {
            throw new BadRequestException("The issuer URL must be an absolute http(s) URL");
        }
    }

    /**
     * Whether a URL is safe to fetch server-side — well-formed http(s) whose host resolves only to
     * public addresses. Called immediately before the server fetches the issuer's discovery
     * document, so it is the authoritative SSRF guard.
     * @param url the URL to check
     * @param allowPrivate when true, loopback/private addresses are permitted (trusted on-prem IdPs)
     * @return true when the URL is well-formed http(s) and its addresses satisfy the policy
     */
    public static boolean isFetchableHttpUrl(String url, boolean allowPrivate) {
        String host = parseHttpHost(url);
        if (host == null) {
            return false;
        }
        if (allowPrivate) {
            return true;
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (isBlocked(address)) {
                    return false;
                }
            }
        } catch (UnknownHostException e) {
            return false;
        }
        return true;
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
        String host = uri.getHost();
        return host == null || host.isBlank() ? null : host;
    }

    private static boolean isBlocked(InetAddress address) {
        if (address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isAnyLocalAddress()
                || address.isMulticastAddress()) {
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
}
