package ooo.klae.connex.backend.util;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Resolves the originating client IP for a request. {@code X-Forwarded-For} is
 * honored only when the direct socket peer is a configured trusted reverse proxy,
 * in which case the rightmost non-trusted hop is returned; otherwise the direct
 * peer address is used. With no trusted proxies configured (the default) the
 * client-supplied {@code X-Forwarded-For} header is never trusted, so it cannot be
 * spoofed to forge the source IP for rate-limiting or audit attribution.
 */
@Component
public class ClientIpResolver {

    private final List<IpAddressMatcher> trustedProxies;

    /**
     * @param trustedProxiesCsv comma-separated trusted-proxy IPs or CIDR ranges
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
        if (forwarded == null || forwarded.isBlank()) {
            return remoteAddr;
        }
        String[] hops = forwarded.split(",");
        for (int i = hops.length - 1; i >= 0; i--) {
            String hop = hops[i].trim();
            if (!hop.isEmpty() && !isTrusted(hop)) {
                return hop;
            }
        }
        return remoteAddr;
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
                // Non-IP literal (unlikely from getRemoteAddr); treat as untrusted.
            }
        }
        return false;
    }
}
