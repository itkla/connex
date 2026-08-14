package ooo.klae.connex.backend.services;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * In-memory fixed-window throttle for authentication abuse, keyed independently by client IP and
 * attempted username. Login and passkey failures share their established buckets; one-time-link
 * exchanges use a separate per-IP namespace with the same cap and window, plus a higher-capacity
 * shared-source circuit breaker when the client cannot be attributed safely, so unauthenticated
 * database amplification cannot consume or reset login failure state. Username buckets clear on a
 * successful login. Enforcement is per JVM replica.
 */
@Component
public class LoginRateLimiter {

    private final int maxPerIp;
    private final int maxPerUser;
    private final int maxLinkExchangesPerSharedSource;
    private final long windowMillis;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public LoginRateLimiter(
            @Value("${connex.login.max-failures-per-ip:50}") int maxPerIp,
            @Value("${connex.login.max-failures-per-user:10}") int maxPerUser,
            @Value("${connex.login.max-link-exchanges-per-shared-source:5000}")
                int maxLinkExchangesPerSharedSource,
            @Value("${connex.login.window-seconds:900}") long windowSeconds) {
        this.maxPerIp = maxPerIp;
        this.maxPerUser = maxPerUser;
        this.maxLinkExchangesPerSharedSource = maxLinkExchangesPerSharedSource;
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
     * Consumes one isolated one-time-link exchange allowance for the resolved network source.
     * Private proxy and unresolved sources use a separately sized instance circuit breaker, so a
     * missing trusted-proxy configuration cannot turn the ordinary per-client cap into a trivial
     * service-wide lockout.
     * @param ip resolved client IP
     * @param nowMillis current epoch time in milliseconds
     * @return true while the applicable per-client or shared-source bucket remains within its cap
     */
    public boolean tryAcquireOneTimeLinkExchange(String ip, long nowMillis) {
        boolean attributable = isThrottleableIp(ip);
        String key = attributable ? exchangeIpKey(ip) : "link-shared-source";
        int limit = attributable ? maxPerIp : maxLinkExchangesPerSharedSource;
        Window window = windows.compute(key, (ignored, existing) -> {
            if (existing == null || nowMillis - existing.start >= windowMillis) {
                return new Window(nowMillis, 1);
            }
            existing.count++;
            return existing;
        });
        return window.count <= limit;
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
        return isThrottleableIp(ip) ? "ip:" + ip : null;
    }

    private static String exchangeIpKey(String ip) {
        return "link-ip:" + ip.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Whether a per-IP failure bucket is meaningful for this address. A loopback/private
     * address means the resolver could not determine a real public client IP — typically an
     * un-configured reverse proxy or tunnel whose single address fronts every client — so
     * throttling on it would lock out the whole instance. In that case per-IP throttling is
     * skipped and only the per-username bucket applies. Configure
     * {@code connex.security.trusted-proxies} so the resolver yields the real public client IP.
     */
    private static boolean isThrottleableIp(String ip) {
        if (ip == null || ip.isBlank()) {
            return false;
        }
        try {
            InetAddress address = InetAddress.getByName(ip);
            return !(address.isLoopbackAddress() || address.isAnyLocalAddress()
                    || address.isSiteLocalAddress() || address.isLinkLocalAddress()
                    || address.isMulticastAddress());
        } catch (UnknownHostException e) {
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
