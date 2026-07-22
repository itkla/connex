package ooo.klae.connex.backend.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Locale;

import org.junit.jupiter.api.Test;

class ChannelAddressNormalizerTest {

    @Test
    void emailNormalizationIsUnchangedFromTheEmailOnlyTrimAndLowerCase() {
        for (String raw : new String[] {
                "  Person@Example.COM ", "person@example.com", "PERSON+tag@Example.Com", "\tA@B.c\n"}) {
            assertEquals(raw.trim().toLowerCase(Locale.ROOT),
                    ChannelAddressNormalizer.normalize(DeliveryChannel.EMAIL, raw));
        }
    }

    @Test
    void smsCollapsesFormattingToDigitsKeepingASingleLeadingPlus() {
        assertEquals("+819012345678",
                ChannelAddressNormalizer.normalize(DeliveryChannel.SMS, "+81 90-1234-5678"));
        assertEquals("5550101234",
                ChannelAddressNormalizer.normalize(DeliveryChannel.SMS, "(555) 010-1234"));
    }

    @Test
    void smsNormalizesEveryFormattingOfOneNumberToTheSameValue() {
        String canonical = ChannelAddressNormalizer.normalize(DeliveryChannel.SMS, "+819012345678");
        for (String raw : new String[] {
                "+81 90-1234-5678", "+81 (90) 1234 5678", " +81.90.1234.5678 ", "+81 9012345678"}) {
            assertEquals(canonical, ChannelAddressNormalizer.normalize(DeliveryChannel.SMS, raw));
        }
    }

    @Test
    void smsDropsANonLeadingPlusAndDistinguishesInternationalPrefix() {
        assertEquals("819012345678",
                ChannelAddressNormalizer.normalize(DeliveryChannel.SMS, "81-90+1234-5678"));
        assertEquals("+819012345678",
                ChannelAddressNormalizer.normalize(DeliveryChannel.SMS, "+819012345678"));
    }

    @Test
    void smsRejectsAnAddressWithTooFewDigitsToBeReachable() {
        assertNull(ChannelAddressNormalizer.normalize(DeliveryChannel.SMS, "12345"));
        assertNull(ChannelAddressNormalizer.normalize(DeliveryChannel.SMS, "ext. 401"));
        assertNull(ChannelAddressNormalizer.normalize(DeliveryChannel.SMS, "+"));
    }

    @Test
    void nullAndBlankInputsNormalizeToNullOnEveryChannel() {
        for (DeliveryChannel channel : DeliveryChannel.values()) {
            assertNull(ChannelAddressNormalizer.normalize(channel, null));
            assertNull(ChannelAddressNormalizer.normalize(channel, "   "));
        }
        assertNull(ChannelAddressNormalizer.normalize(null, "person@example.com"));
    }
}
