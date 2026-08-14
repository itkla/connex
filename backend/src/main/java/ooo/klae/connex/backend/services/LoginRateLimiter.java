package ooo.klae.connex.backend.services;

import java.net.InetAddress;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import ooo.klae.connex.backend.util.ClientIpResolver.ResolvedClientIp;

/**
 * In-memory fixed-window throttle for failed login attempts, keyed independently by
 * client IP and by attempted username. Once either bucket exceeds its cap within the
 * window, further attempts are refused until the window elapses — bounding online
 * brute force from a single source (per-IP) and distributed credential stuffing against
 * one account (per-username). Username buckets clear on a successful login. Single-JVM
 * only, matching the in-memory session model.
 */
@Component
public class LoginRateLimiter {

    private final int maxPerIp;
    private final int maxPerUser;
    private final long windowMillis;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public LoginRateLimiter(
            @Value("${connex.login.max-failures-per-ip:50}") int maxPerIp,
            @Value("${connex.login.max-failures-per-user:10}") int maxPerUser,
            @Value("${connex.login.window-seconds:900}") long windowSeconds) {
        this.maxPerIp = maxPerIp;
        this.maxPerUser = maxPerUser;
        this.windowMillis = windowSeconds * 1000L;
    }

    /**
     * @param ip the requesting client IP
     * @param username the attempted username
     * @param nowMillis the current epoch time in milliseconds
     * @return true when the IP or username has too many recent failed attempts
     */
    public boolean isBlocked(String ip, String username, long nowMillis) {
        return countWithin(ipKey(ip), nowMillis) >= maxPerIp
                || countWithin(userKey(username), nowMillis) >= maxPerUser;
    }

    /**
     * Applies per-IP throttling using resolver provenance so a sanitized private client remains
     * distinct while a direct private proxy address cannot lock out the whole deployment.
     *
     * @param clientIp the resolved client address and proxy provenance
     * @param username the attempted username
     * @param nowMillis the current epoch time in milliseconds
     * @return true when the IP or username has too many recent failed attempts
     */
    public boolean isBlockedForClient(ResolvedClientIp clientIp, String username, long nowMillis) {
        return countWithin(ipKey(clientIp), nowMillis) >= maxPerIp
                || countWithin(userKey(username), nowMillis) >= maxPerUser;
    }

    /**
     * Records one failed attempt against both the IP and username buckets.
     * @param ip the requesting client IP
     * @param username the attempted username
     * @param nowMillis the current epoch time in milliseconds
     */
    public void recordFailure(String ip, String username, long nowMillis) {
        increment(ipKey(ip), nowMillis);
        increment(userKey(username), nowMillis);
    }

    /**
     * Records one failed attempt using resolver provenance for the IP bucket.
     *
     * @param clientIp the resolved client address and proxy provenance
     * @param username the attempted username
     * @param nowMillis the current epoch time in milliseconds
     */
    public void recordFailureForClient(
            ResolvedClientIp clientIp, String username, long nowMillis) {
        increment(ipKey(clientIp), nowMillis);
        increment(userKey(username), nowMillis);
    }

    /**
     * Clears the username bucket after a successful login. The IP bucket is retained so a
     * single valid credential cannot reset a source that is stuffing many accounts.
     * @param username the username that logged in
     */
    public void recordSuccess(String username) {
        String key = userKey(username);
        if (key != null) {
            windows.remove(key);
        }
    }

    /**
     * Drops windows whose period has elapsed, bounding memory growth.
     */
    @Scheduled(fixedDelayString = "${connex.login.eviction-delay-ms:600000}")
    public void evictStale() {
        long now = System.currentTimeMillis();
        windows.entrySet().removeIf(entry -> now - entry.getValue().start >= windowMillis);
    }

    private int countWithin(String key, long nowMillis) {
        if (key == null) {
            return 0;
        }
        Window window = windows.get(key);
        if (window == null || nowMillis - window.start >= windowMillis) {
            return 0;
        }
        return window.count;
    }

    private void increment(String key, long nowMillis) {
        if (key == null) {
            return;
        }
        windows.compute(key, (k, existing) -> {
            if (existing == null || nowMillis - existing.start >= windowMillis) {
                return new Window(nowMillis, 1);
            }
            existing.count++;
            return existing;
        });
    }

    private static String ipKey(String ip) {
        return isThrottleableIp(ip, false) ? "ip:" + ip : null;
    }

    private static String ipKey(ResolvedClientIp clientIp) {
        if (clientIp == null) {
            return null;
        }
        String address = clientIp.address();
        return isThrottleableIp(address, clientIp.forwardedByTrustedProxy())
                ? "ip:" + address
                : null;
    }

    /**
     * Whether a per-IP failure bucket is meaningful for this address. A direct loopback/private
     * address can be a proxy peer fronting every client, so it is skipped. A private address from
     * a trusted sanitizing proxy is a real on-prem client and remains eligible. Loopback,
     * unspecified, and multicast values are never eligible regardless of provenance.
     */
    private static boolean isThrottleableIp(String ip, boolean forwardedByTrustedProxy) {
        if (ip == null || ip.isBlank()) {
            return false;
        }
        try {
            InetAddress address = InetAddress.ofLiteral(ip);
            if (address.isLoopbackAddress() || address.isAnyLocalAddress()
                    || address.isMulticastAddress()) {
                return false;
            }
            return forwardedByTrustedProxy
                    || !(address.isSiteLocalAddress() || address.isLinkLocalAddress());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String userKey(String username) {
        return username == null || username.isBlank()
                ? null
                : "user:" + username.trim().toLowerCase(Locale.ROOT);
    }

    private static final class Window {
        private final long start;
        private volatile int count;

        private Window(long start, int count) {
            this.start = start;
            this.count = count;
        }
    }
}
