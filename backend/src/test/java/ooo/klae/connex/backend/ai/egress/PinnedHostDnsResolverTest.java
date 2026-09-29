package ooo.klae.connex.backend.ai.egress;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.InetAddress;
import java.net.UnknownHostException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class PinnedHostDnsResolverTest {
    @Test
    void resolvesOnlyTheExpectedHostToThePreviouslyValidatedAddress() throws Exception {
        InetAddress validated = InetAddress.getByName("8.8.8.8");
        PinnedHostDnsResolver resolver = new PinnedHostDnsResolver("provider.example", validated);

        assertArrayEquals(new InetAddress[] { validated }, resolver.resolve("PROVIDER.EXAMPLE"));
        assertEquals("provider.example", resolver.resolveCanonicalHostname("provider.example"));
        assertThrows(UnknownHostException.class, () -> resolver.resolve("rebound.example"));
    }

    @ParameterizedTest
    @CsvSource({
            "XN--BCHER-KVA.EXAMPLE., bücher.example, xn--bcher-kva.example",
            "BÜCHER.example., XN--BCHER-KVA.EXAMPLE, xn--bcher-kva.example",
            "provider.example., PROVIDER.EXAMPLE, provider.example",
            "PROVIDER.EXAMPLE, provider.example., provider.example",
            "[2606:4700:4700::ABCD], 2606:4700:4700::abcd, 2606:4700:4700::abcd",
            "2606:4700:4700::ABCD, [2606:4700:4700::abcd], 2606:4700:4700::abcd"
    })
    void canonicalizesBothPinAndLookupIncludingUnicodeHostnames(
            String pinnedHost, String requestedHost, String canonicalHost) throws Exception {
        InetAddress first = InetAddress.getByName("8.8.8.8");
        InetAddress second = InetAddress.getByName("8.8.4.4");
        InetAddress[] addresses = { first, second };
        PinnedHostDnsResolver resolver = new PinnedHostDnsResolver(pinnedHost, addresses);
        addresses[0] = second;

        assertArrayEquals(new InetAddress[] { first, second }, resolver.resolve(requestedHost));
        assertEquals(canonicalHost, resolver.resolveCanonicalHostname(requestedHost));
        assertEquals(canonicalHost, resolver.resolveCanonicalHostname(pinnedHost));
        resolver.resolve(requestedHost)[0] = second;
        assertArrayEquals(new InetAddress[] { first, second }, resolver.resolve(requestedHost));
        assertThrows(UnknownHostException.class, () -> resolver.resolve("unvalidated.example"));
        assertThrows(UnknownHostException.class,
                () -> resolver.resolveCanonicalHostname("unvalidated.example"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            ".", "bücher.example.evil", "xn--bcher-kva.example..", "[xn--bcher-kva.example]",
            "xn--bcher-kva.example:443", "xn--bcher-kva.example%2e", "[::1]"
    })
    void rejectsUnvalidatedAndMalformedLookupKeys(String requestedHost) throws Exception {
        PinnedHostDnsResolver resolver = new PinnedHostDnsResolver(
                "xn--bcher-kva.example", InetAddress.getByName("8.8.8.8"));

        assertThrows(UnknownHostException.class, () -> resolver.resolve(requestedHost));
        assertThrows(UnknownHostException.class, () -> resolver.resolveCanonicalHostname(requestedHost));
    }
}
