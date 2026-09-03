package ooo.klae.connex.backend.util;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Resolves the originating client IP for a request. {@code X-Forwarded-For} is
 * honored only when the direct socket peer is a configured sanitizing reverse proxy.
 * Each forwarding proxy appends its immediate peer, and resolution walks that chain
 * right-to-left until it reaches the first untrusted address. Every hop must be an IP
 * literal; missing or malformed chains fall back to the direct peer. With no trusted
 * proxies configured (the default), the client-supplied header is never trusted.
 */
@Component
public class ClientIpResolver {

    private static final Pattern HOSTNAME = Pattern.compile(
        "(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*",
        Pattern.CASE_INSENSITIVE);
    private static final long PERIODIC_REFRESH_NANOS = Duration.ofMinutes(1).toNanos();
    private static final long MISS_REFRESH_NANOS = Duration.ofSeconds(1).toNanos();

    /**
     * @param address the selected client or direct-peer IP
     * @param forwardedByTrustedProxy whether a trusted sanitizing proxy supplied the address
     */
    public record ResolvedClientIp(String address, boolean forwardedByTrustedProxy) {
    }

    private final List<IpAddressMatcher> staticTrustedProxies;
    private final List<String> trustedProxyHostnames;
    private final HostnameResolver hostnameResolver;
    private final LongSupplier nanoTime;
    private volatile ResolvedHostnames resolvedHostnames;

    /**
     * @param trustedProxiesCsv comma-separated sanitizing-proxy IPs, CIDR ranges, or hostnames
     *     (e.g. {@code 10.0.0.0/8,192.168.1.5,caddy}); empty disables X-Forwarded-For trust
     */
    @Autowired
    public ClientIpResolver(@Value("${connex.security.trusted-proxies:}") String trustedProxiesCsv) {
        this(trustedProxiesCsv, InetAddress::getAllByName, System::nanoTime);
    }

    ClientIpResolver(
            String trustedProxiesCsv,
            HostnameResolver hostnameResolver,
            LongSupplier nanoTime) {
        List<IpAddressMatcher> staticMatchers = new ArrayList<>();
        Set<String> hostnames = new LinkedHashSet<>();
        if (trustedProxiesCsv != null && !trustedProxiesCsv.isBlank()) {
            for (String entry : trustedProxiesCsv.split(",")) {
                String trimmed = entry.trim();
                if (!trimmed.isEmpty()) {
                    addTrustedProxy(trimmed, staticMatchers, hostnames);
                }
            }
        }
        this.staticTrustedProxies = List.copyOf(staticMatchers);
        this.trustedProxyHostnames = List.copyOf(hostnames);
        this.hostnameResolver = hostnameResolver;
        this.nanoTime = nanoTime;
        this.resolvedHostnames = resolveHostnames(nanoTime.getAsLong());
    }

    /**
     * @param request the inbound request
     * @return the resolved client IP, or {@code null} when unavailable
     */
    public String resolve(HttpServletRequest request) {
        return resolveWithProvenance(request).address();
    }

    /**
     * Resolves the client address together with the provenance needed by per-IP throttles.
     *
     * @param request the inbound request
     * @return the resolved address and whether it came from a trusted sanitizing proxy
     */
    public ResolvedClientIp resolveWithProvenance(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        if ((staticTrustedProxies.isEmpty() && trustedProxyHostnames.isEmpty())
                || !isTrusted(remoteAddr)) {
            return new ResolvedClientIp(remoteAddr, false);
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.isBlank()) {
            return new ResolvedClientIp(remoteAddr, false);
        }
        List<String> chain = Arrays.stream(forwarded.split(",", -1))
            .map(String::trim)
            .toList();
        try {
            for (String address : chain) {
                if (address.isEmpty()) {
                    return new ResolvedClientIp(remoteAddr, false);
                }
                InetAddress.ofLiteral(address);
            }
        } catch (IllegalArgumentException ignored) {
            return new ResolvedClientIp(remoteAddr, false);
        }
        for (int index = chain.size() - 1; index >= 0; index--) {
            String address = chain.get(index);
            if (!isTrusted(address)) {
                return new ResolvedClientIp(address, true);
            }
        }
        return new ResolvedClientIp(chain.getFirst(), true);
    }

    private boolean isTrusted(String ip) {
        if (ip == null || ip.isBlank()) {
            return false;
        }
        if (matches(staticTrustedProxies, ip)) {
            return true;
        }
        ResolvedHostnames current = resolvedHostnames;
        long now = nanoTime.getAsLong();
        if (now >= current.periodicRefreshAtNanos()) {
            current = refreshHostnames(now, false);
        }
        if (matches(current.matchers(), ip)) {
            return true;
        }
        if (now >= current.missRefreshAtNanos()) {
            current = refreshHostnames(now, true);
        }
        return matches(current.matchers(), ip);
    }

    private static boolean matches(List<IpAddressMatcher> matchers, String ip) {
        for (IpAddressMatcher matcher : matchers) {
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

    private synchronized ResolvedHostnames refreshHostnames(long now, boolean afterMiss) {
        ResolvedHostnames current = resolvedHostnames;
        if (afterMiss && now < current.missRefreshAtNanos()) {
            return current;
        }
        if (!afterMiss && now < current.periodicRefreshAtNanos()) {
            return current;
        }
        ResolvedHostnames refreshed = resolveHostnames(now);
        resolvedHostnames = refreshed;
        return refreshed;
    }

    private ResolvedHostnames resolveHostnames(long now) {
        List<IpAddressMatcher> matchers = new ArrayList<>();
        for (String hostname : trustedProxyHostnames) {
            try {
                for (InetAddress address : hostnameResolver.resolve(hostname)) {
                    matchers.add(new IpAddressMatcher(address.getHostAddress()));
                }
            } catch (UnknownHostException ignored) {
                continue;
            }
        }
        return new ResolvedHostnames(
            List.copyOf(matchers),
            now + PERIODIC_REFRESH_NANOS,
            now + MISS_REFRESH_NANOS);
    }

    private static void addTrustedProxy(
            String entry,
            List<IpAddressMatcher> staticMatchers,
            Set<String> hostnames) {
        if (entry.contains("/")) {
            staticMatchers.add(new IpAddressMatcher(entry));
            return;
        }
        try {
            InetAddress.ofLiteral(entry);
            staticMatchers.add(new IpAddressMatcher(entry));
        } catch (IllegalArgumentException exception) {
            String hostname = entry.toLowerCase(Locale.ROOT);
            if (!HOSTNAME.matcher(hostname).matches()) {
                throw new IllegalArgumentException("Invalid trusted proxy entry: " + entry, exception);
            }
            hostnames.add(hostname);
        }
    }

    @FunctionalInterface
    interface HostnameResolver {
        InetAddress[] resolve(String hostname) throws UnknownHostException;
    }

    private record ResolvedHostnames(
            List<IpAddressMatcher> matchers,
            long periodicRefreshAtNanos,
            long missRefreshAtNanos) {
    }
}
