package ooo.klae.connex.backend.ai.egress;

import static ooo.klae.connex.backend.ai.egress.Nat64TestAddresses.prefix;
import static ooo.klae.connex.backend.ai.egress.Nat64TestAddresses.translated;
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
}
