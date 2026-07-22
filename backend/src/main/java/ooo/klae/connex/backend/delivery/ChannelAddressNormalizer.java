package ooo.klae.connex.backend.delivery;

import java.util.Locale;

import ooo.klae.connex.backend.beans.Person;

/**
 * The single normalization of a contact address for a delivery channel. Every write and every read of
 * a channel address goes through this class so a stored suppression and a materialized delivery are
 * compared in exactly the same form: if the two normalizations were allowed to drift, a person who
 * opted out of a channel would still be contacted on it.
 *
 * <p>The rules are per channel and deliberately conservative:
 * <ul>
 *   <li>{@link DeliveryChannel#EMAIL} — trimmed and lower-cased, unchanged from the original
 *       email-only behavior, so existing suppression rows keep matching byte for byte.</li>
 *   <li>{@link DeliveryChannel#SMS} — every character except digits and a single leading {@code +} is
 *       stripped, so {@code "+81 90-1234-5678"} and {@code "+819012345678"} collapse to one value.
 *       A number left with fewer than {@link #SMS_MIN_DIGITS} digits is not addressable and
 *       normalizes to null.</li>
 *   <li>{@link DeliveryChannel#LINE} and {@link DeliveryChannel#WHATSAPP} — trimmed and lower-cased;
 *       these channels carry no send path yet and keep the historical handle behavior.</li>
 * </ul>
 */
public final class ChannelAddressNormalizer {

    private static final int SMS_MIN_DIGITS = 7;

    private ChannelAddressNormalizer() {
    }

    /**
     * Normalizes a raw contact address into the canonical form stored and compared for a channel.
     * @param channel the delivery channel the address belongs to
     * @param raw the raw address as authored or imported
     * @return the canonical address, or null when the channel or address cannot yield one
     */
    public static String normalize(DeliveryChannel channel, String raw) {
        if (channel == null || raw == null || raw.isBlank()) {
            return null;
        }
        return switch (channel) {
            case SMS -> normalizeSms(raw);
            case EMAIL, LINE, WHATSAPP -> raw.trim().toLowerCase(Locale.ROOT);
        };
    }

    /**
     * Resolves the canonical address a channel reaches a person at, selecting the person field that
     * channel addresses and normalizing it. Suppression matching and delivery materialization both
     * resolve through here so a snapshot cannot classify a person against a different field, or a
     * different form of the same field, than the one dispatch will actually contact.
     * @param channel the delivery channel the person would be contacted on
     * @param person the person to resolve an address for
     * @return the canonical address, or null when the person has no address the channel can reach
     */
    public static String addressFor(DeliveryChannel channel, Person person) {
        if (channel == null || person == null) {
            return null;
        }
        return normalize(channel, contactAddress(channel, person));
    }

    private static String contactAddress(DeliveryChannel channel, Person person) {
        return switch (channel) {
            case EMAIL -> person.getEmail();
            case SMS -> person.getPhone();
            case LINE, WHATSAPP -> null;
        };
    }

    private static String normalizeSms(String raw) {
        String trimmed = raw.trim();
        StringBuilder digits = new StringBuilder(trimmed.length());
        for (int index = 0; index < trimmed.length(); index++) {
            char character = trimmed.charAt(index);
            if (character >= '0' && character <= '9') {
                digits.append(character);
            }
        }
        if (digits.length() < SMS_MIN_DIGITS) {
            return null;
        }
        return trimmed.charAt(0) == '+' ? "+" + digits : digits.toString();
    }
}
