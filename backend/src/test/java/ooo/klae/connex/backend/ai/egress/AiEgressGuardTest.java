package ooo.klae.connex.backend.ai.egress;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.util.Set;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.ai.provider.AiProviderException;

class AiEgressGuardTest {

    @Test
    void requireAllowlistedHost_allowsCaseInsensitiveMatchWithPublicResolution() throws Exception {
        InetAddress[] publicAddresses = { InetAddress.getByName("8.8.8.8") };

        assertDoesNotThrow(() -> AiEgressGuard.requireAllowlistedHost(
                "CLOUD.EXAMPLE.COM", Set.of("cloud.example.com"), publicAddresses));
    }

    @Test
    void requireAllowlistedHost_rejectsNonAllowlistedHostBeforeAddressChecks() throws Exception {
        InetAddress[] publicAddresses = { InetAddress.getByName("8.8.8.8") };

        assertThrows(AiProviderException.class, () -> AiEgressGuard.requireAllowlistedHost(
                "cloud.example.com.evil.test", Set.of("cloud.example.com"), publicAddresses));
        assertThrows(AiProviderException.class, () -> AiEgressGuard.requireAllowlistedHost(
                "evil.test", Set.of("cloud.example.com"), publicAddresses));
        assertThrows(AiProviderException.class, () -> AiEgressGuard.requireAllowlistedHost(
                null, Set.of("cloud.example.com"), publicAddresses));
    }

    @Test
    void requireFetchableHost_allowsPublicAndRejectsPrivateWhenPrivateIsDisabled() throws Exception {
        InetAddress[] publicAddresses = { InetAddress.getByName("8.8.8.8") };
        InetAddress[] privateAddresses = { InetAddress.getByName("10.0.0.1") };

        assertDoesNotThrow(() -> AiEgressGuard.requireFetchableHost("public.example", false, publicAddresses));
        assertThrows(AiProviderException.class,
                () -> AiEgressGuard.requireFetchableHost("private.example", false, privateAddresses));
    }

    @Test
    void requireFetchableHost_allowsPrivateOnlyWhenExplicitlyEnabled() throws Exception {
        InetAddress[] privateAddresses = { InetAddress.getByName("192.168.1.20") };

        assertDoesNotThrow(() -> AiEgressGuard.requireFetchableHost("private.example", true, privateAddresses));
        assertThrows(AiProviderException.class,
                () -> AiEgressGuard.requireFetchableHost("private.example", false, privateAddresses));
    }

    @Test
    void requireFetchableHost_internalPathRejectsPublicAddresses() throws Exception {
        InetAddress[] publicAddresses = { InetAddress.getByName("8.8.8.8") };
        InetAddress[] mixed = { InetAddress.getByName("10.0.0.1"), InetAddress.getByName("8.8.8.8") };

        assertThrows(AiProviderException.class,
                () -> AiEgressGuard.requireFetchableHost("public.example", true, publicAddresses));
        assertThrows(AiProviderException.class,
                () -> AiEgressGuard.requireFetchableHost("mixed.example", true, mixed));
    }

    @Test
    void requirePublicAddresses_rejectsBlockedAddressFamilies() throws Exception {
        assertBlocked("127.0.0.1");
        assertBlocked("10.0.0.1");
        assertBlocked("172.16.0.1");
        assertBlocked("192.168.0.1");
        assertBlocked("100.64.0.1");
        assertBlocked("100.127.255.255");
        assertBlocked("169.254.169.254");
        assertBlocked("224.0.0.1");
        assertBlocked("0.0.0.0");
        assertBlocked("::1");
        assertBlocked("fc00::1");
        assertBlocked("fd00::1");
    }

    @Test
    void isBlocked_rejectsSpecialUseIpv4Ranges() throws Exception {
        assertBlocked("0.1.2.3");
        assertBlocked("192.0.0.8");
        assertBlocked("198.18.0.1");
        assertBlocked("198.19.255.255");
        assertBlocked("240.0.0.1");
    }

    @Test
    void requireFetchableHost_rejectsEmptyOrNullResolution() {
        assertThrows(AiProviderException.class,
                () -> AiEgressGuard.requireFetchableHost("empty.example", true, new InetAddress[0]));
        assertThrows(AiProviderException.class,
                () -> AiEgressGuard.requireFetchableHost("empty.example", true, null));
        assertThrows(AiProviderException.class,
                () -> AiEgressGuard.requireFetchableHost("empty.example", true, new InetAddress[] { null }));
    }

    private static void assertBlocked(String address) throws Exception {
        InetAddress resolved = InetAddress.getByName(address);
        assertTrue(AiEgressGuard.isBlocked(resolved), address);
        assertThrows(AiProviderException.class,
                () -> AiEgressGuard.requirePublicAddresses("provider.example", new InetAddress[] { resolved }));
    }
}
