package ooo.klae.connex.backend.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.web.MockHttpServletRequest;

import ooo.klae.connex.backend.util.ClientIpResolver.ResolvedClientIp;

class ClientIpResolverTest {

    private static final String PRIVATE_PROXY_RANGES =
        "10.0.0.0/8,172.16.0.0/12,192.168.0.0/16";
    private static final String PUBLISHED_FORWARDERS = "172.30.0.2,172.31.0.2";

    @Test
    void springConstructsResolverWithDefaultTrustedProxyConfiguration() {
        try (AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext(ClientIpResolver.class)) {
            ClientIpResolver resolver = context.getBean(ClientIpResolver.class);

            ResolvedClientIp resolved = resolver.resolveWithProvenance(
                request("203.0.113.8", "198.51.100.19"));

            assertEquals("203.0.113.8", resolved.address());
            assertFalse(resolved.forwardedByTrustedProxy());
        }
    }

    @Test
    void preservesPrivateClientAddressFromTrustedSanitizingProxy() {
        ClientIpResolver resolver = new ClientIpResolver(PRIVATE_PROXY_RANGES);
        MockHttpServletRequest request = request("172.18.0.4", "172.20.5.10");

        ResolvedClientIp resolved = resolver.resolveWithProvenance(request);

        assertEquals("172.20.5.10", resolved.address());
        assertTrue(resolved.forwardedByTrustedProxy());
    }

    @Test
    void ignoresForwardedAddressFromUntrustedPeer() {
        ClientIpResolver resolver = new ClientIpResolver(PRIVATE_PROXY_RANGES);
        MockHttpServletRequest request = request("203.0.113.8", "198.51.100.19");

        ResolvedClientIp resolved = resolver.resolveWithProvenance(request);

        assertEquals("203.0.113.8", resolved.address());
        assertFalse(resolved.forwardedByTrustedProxy());
    }

    @Test
    void acceptsTheFrontendChainThroughTheTrustedCaddyHop() {
        ClientIpResolver resolver = new ClientIpResolver(PUBLISHED_FORWARDERS);
        MockHttpServletRequest request =
            request("172.31.0.2", "198.51.100.19, 172.30.0.2");

        ResolvedClientIp resolved = resolver.resolveWithProvenance(request);

        assertEquals("198.51.100.19", resolved.address());
        assertTrue(resolved.forwardedByTrustedProxy());
    }

    @Test
    void rejectsSpoofedFrontendChainFromAnUntrustedPeer() {
        ClientIpResolver resolver = new ClientIpResolver(PUBLISHED_FORWARDERS);
        MockHttpServletRequest request =
            request("203.0.113.8", "198.51.100.19, 172.30.0.2");

        ResolvedClientIp resolved = resolver.resolveWithProvenance(request);

        assertEquals("203.0.113.8", resolved.address());
        assertFalse(resolved.forwardedByTrustedProxy());
    }

    @Test
    void rejectsMalformedAddressAnywhereInAForwardedChain() {
        ClientIpResolver resolver = new ClientIpResolver(PUBLISHED_FORWARDERS);
        MockHttpServletRequest request =
            request("172.31.0.2", "spoofed, 172.30.0.2");

        ResolvedClientIp resolved = resolver.resolveWithProvenance(request);

        assertEquals("172.31.0.2", resolved.address());
        assertFalse(resolved.forwardedByTrustedProxy());
    }

    @Test
    void resolvesConfiguredDockerServiceNames() throws Exception {
        ClientIpResolver resolver = new ClientIpResolver(
            "caddy,frontend",
            hostnameResolver(Map.of(
                "caddy", "172.18.0.2",
                "frontend", "172.19.0.3")),
            () -> 0L);

        ResolvedClientIp caddy = resolver.resolveWithProvenance(
            request("172.18.0.2", "198.51.100.19"));
        ResolvedClientIp frontend = resolver.resolveWithProvenance(
            request("172.19.0.3", "198.51.100.20"));

        assertEquals("198.51.100.19", caddy.address());
        assertTrue(caddy.forwardedByTrustedProxy());
        assertEquals("198.51.100.20", frontend.address());
        assertTrue(frontend.forwardedByTrustedProxy());
    }

    @Test
    void trustsARecreatedDockerPeerWithinTheAddressMissRefreshWindow() throws Exception {
        AtomicLong now = new AtomicLong();
        AtomicLong generation = new AtomicLong();
        ClientIpResolver resolver = new ClientIpResolver(
            "frontend",
            hostname -> new InetAddress[] { InetAddress.getByName(
                generation.get() == 0 ? "172.19.0.3" : "172.19.0.4") },
            now::get);
        generation.incrementAndGet();
        now.set(999_999_999L);

        ResolvedClientIp beforeRefresh = resolver.resolveWithProvenance(
            request("172.19.0.4", "198.51.100.21"));
        now.set(1_000_000_000L);

        ResolvedClientIp resolved = resolver.resolveWithProvenance(
            request("172.19.0.4", "198.51.100.21"));

        assertEquals("172.19.0.4", beforeRefresh.address());
        assertFalse(beforeRefresh.forwardedByTrustedProxy());
        assertEquals("198.51.100.21", resolved.address());
        assertTrue(resolved.forwardedByTrustedProxy());
    }

    @Test
    void refreshesDockerServiceNamesPeriodically() throws Exception {
        AtomicLong now = new AtomicLong();
        AtomicLong generation = new AtomicLong();
        ClientIpResolver resolver = new ClientIpResolver(
            "caddy",
            hostname -> new InetAddress[] { InetAddress.getByName(
                generation.get() == 0 ? "172.18.0.2" : "172.18.0.5") },
            now::get);
        generation.incrementAndGet();
        now.set(60_000_000_000L);

        ResolvedClientIp resolved = resolver.resolveWithProvenance(
            request("172.18.0.5", "198.51.100.22"));

        assertEquals("198.51.100.22", resolved.address());
        assertTrue(resolved.forwardedByTrustedProxy());
    }

    @Test
    void unresolvedDockerServiceNameFailsClosedUntilItCanBeResolved() throws Exception {
        AtomicLong now = new AtomicLong();
        AtomicLong attempts = new AtomicLong();
        ClientIpResolver resolver = new ClientIpResolver(
            "frontend",
            hostname -> {
                if (attempts.incrementAndGet() == 1) {
                    throw new UnknownHostException(hostname);
                }
                return new InetAddress[] { InetAddress.getByName("172.19.0.4") };
            },
            now::get);
        MockHttpServletRequest request = request("172.19.0.4", "198.51.100.21");

        ResolvedClientIp unresolved = resolver.resolveWithProvenance(request);
        now.set(1_000_000_000L);
        ResolvedClientIp recovered = resolver.resolveWithProvenance(request);

        assertEquals("172.19.0.4", unresolved.address());
        assertFalse(unresolved.forwardedByTrustedProxy());
        assertEquals("198.51.100.21", recovered.address());
        assertTrue(recovered.forwardedByTrustedProxy());
    }

    private static ClientIpResolver.HostnameResolver hostnameResolver(
            Map<String, String> addresses) {
        return hostname -> {
            String address = addresses.get(hostname);
            if (address == null) {
                throw new UnknownHostException(hostname);
            }
            return new InetAddress[] { InetAddress.getByName(address) };
        };
    }

    private static MockHttpServletRequest request(String remoteAddress, String forwardedAddress) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddress);
        request.addHeader("X-Forwarded-For", forwardedAddress);
        return request;
    }
}
