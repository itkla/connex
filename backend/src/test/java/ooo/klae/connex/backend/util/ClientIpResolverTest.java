package ooo.klae.connex.backend.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import ooo.klae.connex.backend.util.ClientIpResolver.ResolvedClientIp;

class ClientIpResolverTest {

    private static final String PRIVATE_PROXY_RANGES =
        "10.0.0.0/8,172.16.0.0/12,192.168.0.0/16";

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
    void rejectsUnsanitizedForwardedChain() {
        ClientIpResolver resolver = new ClientIpResolver(PRIVATE_PROXY_RANGES);
        MockHttpServletRequest request =
            request("172.18.0.4", "198.51.100.19, 172.20.5.10");

        ResolvedClientIp resolved = resolver.resolveWithProvenance(request);

        assertEquals("172.18.0.4", resolved.address());
        assertFalse(resolved.forwardedByTrustedProxy());
    }

    private static MockHttpServletRequest request(String remoteAddress, String forwardedAddress) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddress);
        request.addHeader("X-Forwarded-For", forwardedAddress);
        return request;
    }
}
