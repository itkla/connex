package ooo.klae.connex.backend.ai.egress;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.InetAddress;
import java.net.UnknownHostException;

import org.junit.jupiter.api.Test;

class PinnedHostDnsResolverTest {
    @Test
    void resolvesOnlyTheExpectedHostToThePreviouslyValidatedAddress() throws Exception {
        InetAddress validated = InetAddress.getByName("8.8.8.8");
        PinnedHostDnsResolver resolver = new PinnedHostDnsResolver("provider.example", validated);

        assertArrayEquals(new InetAddress[] { validated }, resolver.resolve("PROVIDER.EXAMPLE"));
        assertEquals("provider.example", resolver.resolveCanonicalHostname("provider.example"));
        assertThrows(UnknownHostException.class, () -> resolver.resolve("rebound.example"));
    }
}
