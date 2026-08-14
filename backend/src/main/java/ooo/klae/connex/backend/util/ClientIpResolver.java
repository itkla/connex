package ooo.klae.connex.backend.util;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Resolves the originating client IP for a request. {@code X-Forwarded-For} is
 * honored only when the direct socket peer is a configured sanitizing reverse proxy.
 * That proxy must replace the header with exactly one IP literal. The single value is
 * returned without walking or reclassifying it, so a private client address remains
 * distinct from the private proxy peer. Missing, malformed, or multi-hop values fall
 * back to the direct peer. With no trusted proxies configured (the default), the
 * client-supplied header is never trusted.
 */
@Component
public class ClientIpResolver {

    private final List<IpAddressMatcher> trustedProxies;

    /**
     * @param trustedProxiesCsv comma-separated sanitizing-proxy IPs or CIDR ranges
     *     (e.g. {@code 10.0.0.0/8,192.168.1.5}); empty disables X-Forwarded-For trust
     */
    public ClientIpResolver(@Value("${connex.security.trusted-proxies:}") String trustedProxiesCsv) {
        List<IpAddressMatcher> matchers = new ArrayList<>();
        if (trustedProxiesCsv != null && !trustedProxiesCsv.isBlank()) {
            for (String entry : trustedProxiesCsv.split(",")) {
                String trimmed = entry.trim();
                if (!trimmed.isEmpty()) {
                    matchers.add(new IpAddressMatcher(trimmed));
                }
            }
        }
        this.trustedProxies = List.copyOf(matchers);
    }

    /**
     * @param request the inbound request
     * @return the resolved client IP, or {@code null} when unavailable
     */
    public String resolve(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        if (trustedProxies.isEmpty() || !isTrusted(remoteAddr)) {
            return remoteAddr;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.isBlank() || forwarded.contains(",")) {
            return remoteAddr;
        }
        try {
            InetAddress.ofLiteral(forwarded);
            return forwarded;
        } catch (IllegalArgumentException ignored) {
            return remoteAddr;
        }
    }

    private boolean isTrusted(String ip) {
        if (ip == null || ip.isBlank()) {
            return false;
        }
        for (IpAddressMatcher matcher : trustedProxies) {
            try {
                if (matcher.matches(ip)) {
                    return true;
                }
            } catch (IllegalArgumentException ignored) {
                return false;
            }
        }
        return false;
    }
}
