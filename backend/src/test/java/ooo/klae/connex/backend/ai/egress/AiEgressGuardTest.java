package ooo.klae.connex.backend.ai.egress;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
        assertBlocked("192.0.2.1");
        assertBlocked("198.18.0.1");
        assertBlocked("198.19.255.255");
        assertBlocked("198.51.100.1");
        assertBlocked("203.0.113.1");
        assertBlocked("240.0.0.1");
        assertBlocked("100::1");
        assertBlocked("100:0:0:1::1");
        assertBlocked("2001:2::1");
        assertBlocked("2001:db8::1");
        assertBlocked("2001:20::1");
    }

    @Test
    void isBlocked_decodesIpv4EmbeddedIpv6AddressFamilies() throws Exception {
        assertBlocked("64:ff9b::a9fe:a9fe");
        assertBlocked("::10.0.0.1");
        assertBlocked("::ffff:169.254.169.254");
        assertBlocked("2002:a9fe:a9fe::");
        assertBlocked("2001:db8::5efe:a9fe:a9fe");
        assertBlocked("2001:0:808:808::5601:5601");
        assertBlocked("64:ff9b:1:a9fe:a9:fe00::");
    }

    @Test
    void isBlocked_allowsPublicIpv4EmbeddedInNat64WellKnownPrefix() throws Exception {
        InetAddress wellKnown = InetAddress.getByName("64:ff9b::808:808");
        InetAddress localUse = InetAddress.getByName("64:ff9b:1:808:8:800::");

        assertFalse(AiEgressGuard.isBlocked(wellKnown));
        assertFalse(AiEgressGuard.isBlocked(localUse));
        assertDoesNotThrow(() -> AiEgressGuard.requirePublicAddresses(
                "provider.example", new InetAddress[] { wellKnown, localUse }));
        assertDoesNotThrow(() -> AiEgressGuard.requireOrgConfiguredHost(
                "provider.example", false, new InetAddress[] { wellKnown, localUse }, new Nat64PrefixPolicy("")));
    }

    @ParameterizedTest
    @ValueSource(ints = { 32, 40, 48, 56, 64, 96 })
    void orgConfiguredPublicHostRejectsBlockedIpv4ThroughUnclassifiedRfc6052Prefix(int prefixLength)
            throws Exception {
        byte[] prefix = nat64Prefix(prefixLength);
        InetAddress translated = InetAddress.getByAddress(
                translated(prefix, prefixLength, 169, 254, 169, 254));

        assertThrows(AiProviderException.class, () -> AiEgressGuard.requireOrgConfiguredHost(
                "provider.example", false, new InetAddress[] { translated }, new Nat64PrefixPolicy("")));
    }

    @ParameterizedTest
    @ValueSource(ints = { 32, 40, 48, 56, 64, 96 })
    void configuredRfc6052PrefixAllowsOnlyTheRequestedAddressClass(int prefixLength) throws Exception {
        byte[] prefix = nat64Prefix(prefixLength);
        String configuration = InetAddress.getByAddress(prefix).getHostAddress() + "/" + prefixLength;
        Nat64PrefixPolicy policy = new Nat64PrefixPolicy(configuration);
        InetAddress publicTranslation = InetAddress.getByAddress(translated(prefix, prefixLength, 8, 8, 8, 8));
        InetAddress privateTranslation = InetAddress.getByAddress(
                translated(prefix, prefixLength, 169, 254, 169, 254));

        assertDoesNotThrow(() -> AiEgressGuard.requireOrgConfiguredHost(
                "provider.example", false, new InetAddress[] { publicTranslation }, policy));
        assertDoesNotThrow(() -> AiEgressGuard.requireOrgConfiguredHost(
                "provider.example", true, new InetAddress[] { privateTranslation }, policy));
        assertThrows(AiProviderException.class, () -> AiEgressGuard.requireOrgConfiguredHost(
                "provider.example", false, new InetAddress[] { privateTranslation }, policy));
        assertThrows(AiProviderException.class, () -> AiEgressGuard.requireOrgConfiguredHost(
                "provider.example", true, new InetAddress[] { publicTranslation }, policy));
    }

    @Test
    void unclassifiedRfc6052TranslationCannotHideBehindTunnelLikeSuffixBits() throws Exception {
        byte[] address = translated(nat64Prefix(32), 32, 169, 254, 169, 254);
        address[9] = 0;
        address[10] = 0x5e;
        address[11] = (byte) 0xfe;
        address[12] = 8;
        address[13] = 8;
        address[14] = 8;
        address[15] = 8;

        assertThrows(AiProviderException.class, () -> AiEgressGuard.requireOrgConfiguredHost(
                "provider.example",
                false,
                new InetAddress[] { InetAddress.getByAddress(address) },
                new Nat64PrefixPolicy("")));
    }

    @Test
    void orgConfiguredPublicHostAllowsIpv6WhenEveryRfc6052InterpretationIsPublic() throws Exception {
        InetAddress publicIpv6 = InetAddress.getByName("2001:db9:809:b0c:d:e0f:1011:1213");

        assertDoesNotThrow(() -> AiEgressGuard.requireOrgConfiguredHost(
                "provider.example",
                false,
                new InetAddress[] { publicIpv6 },
                new Nat64PrefixPolicy("")));
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

    private static byte[] nat64Prefix(int prefixLength) {
        byte[] prefix = new byte[16];
        prefix[0] = 0x20;
        prefix[1] = 0x01;
        prefix[2] = 0x0d;
        prefix[3] = (byte) 0xb9;
        if (prefixLength >= 40) {
            prefix[4] = 0x11;
        }
        if (prefixLength >= 48) {
            prefix[5] = 0x22;
        }
        if (prefixLength >= 56) {
            prefix[6] = 0x33;
        }
        if (prefixLength >= 64) {
            prefix[7] = 0x44;
        }
        if (prefixLength == 96) {
            prefix[9] = 0x55;
            prefix[10] = 0x66;
            prefix[11] = 0x77;
        }
        return prefix;
    }

    private static byte[] translated(
            byte[] prefix, int prefixLength, int first, int second, int third, int fourth) {
        byte[] address = prefix.clone();
        byte[] ipv4 = { (byte) first, (byte) second, (byte) third, (byte) fourth };
        switch (prefixLength) {
            case 32 -> System.arraycopy(ipv4, 0, address, 4, 4);
            case 40 -> {
                System.arraycopy(ipv4, 0, address, 5, 3);
                address[9] = ipv4[3];
            }
            case 48 -> {
                System.arraycopy(ipv4, 0, address, 6, 2);
                System.arraycopy(ipv4, 2, address, 9, 2);
            }
            case 56 -> {
                address[7] = ipv4[0];
                System.arraycopy(ipv4, 1, address, 9, 3);
            }
            case 64 -> System.arraycopy(ipv4, 0, address, 9, 4);
            case 96 -> System.arraycopy(ipv4, 0, address, 12, 4);
            default -> throw new IllegalArgumentException("Unsupported prefix length");
        }
        return address;
    }
}
