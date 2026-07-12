package ooo.klae.connex.backend.ai.egress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.InetAddress;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class Nat64PrefixPolicyTest {

    @ParameterizedTest
    @ValueSource(ints = { 32, 40, 48, 56, 64, 96 })
    void classifiesPrivateAndPublicTranslationsForEveryRfc6052Length(int prefixLength) throws Exception {
        byte[] prefix = prefix(prefixLength);
        String configuration = InetAddress.getByAddress(prefix).getHostAddress() + "/" + prefixLength;
        Nat64PrefixPolicy policy = new Nat64PrefixPolicy(configuration);

        assertEquals(Nat64PrefixPolicy.TranslationClass.PRIVATE,
                policy.classify(InetAddress.getByAddress(translated(prefix, prefixLength, 169, 254, 169, 254))));
        assertEquals(Nat64PrefixPolicy.TranslationClass.PUBLIC,
                policy.classify(InetAddress.getByAddress(translated(prefix, prefixLength, 8, 8, 8, 8))));
        assertNull(policy.classify(InetAddress.getByName("2606:4700:4700::1111")));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "2001:db8::/24",
        "2001:db8::1/32",
        "10.0.0.0/32",
        "2001:db8::/",
        "2001:db8::/abc",
        "2001:db8::/32,",
        "2001:db8::/32,2001:db8:1::/48"
    })
    void rejectsInvalidOrOverlappingConfiguration(String configuration) {
        assertThrows(IllegalStateException.class, () -> new Nat64PrefixPolicy(configuration));
    }

    private static byte[] prefix(int prefixLength) {
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
