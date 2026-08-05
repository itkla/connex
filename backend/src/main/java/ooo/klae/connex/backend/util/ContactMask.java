package ooo.klae.connex.backend.util;

/**
 * Masks contact addresses for logs and public DTOs so local-part PII is not retained in
 * operator-visible surfaces. The domain is kept so delivery and routing triage stay useful.
 */
public final class ContactMask {

    private ContactMask() {
    }

    /**
     * Masks an email or similar {@code local@domain} address for logging and unsubscribe previews.
     *
     * @param address the raw address, or null/blank
     * @return a masked form such as {@code j***@example.com}, empty when blank, or {@code ***} when
     *         the value is not address-shaped
     */
    public static String maskEmail(String address) {
        if (address == null || address.isBlank()) {
            return "";
        }
        int at = address.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        char first = address.charAt(0);
        return first + "***" + address.substring(at);
    }
}
