package ooo.klae.connex.backend.util;

/**
 * Masks contact addresses for logs and public DTOs so local-part PII is not retained in
 * operator-visible surfaces. The domain is kept so delivery and routing triage stay useful.
 * Malformed or multi-address values fail closed to {@code ***}.
 */
public final class ContactMask {

    private ContactMask() {
    }

    /**
     * Masks a single email-shaped address for logging and unsubscribe previews.
     *
     * @param address the raw address, or null/blank
     * @return a masked form such as {@code j***@example.com}, empty when blank, or {@code ***} when
     *         the value is not a single address-shaped value
     */
    public static String maskEmail(String address) {
        if (address == null || address.isBlank()) {
            return "";
        }
        String value = address.strip();
        int at = value.indexOf('@');
        if (at <= 0 || at != value.lastIndexOf('@') || at == value.length() - 1) {
            return "***";
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isWhitespace(character)
                    || character == ','
                    || character == ';'
                    || character == '<'
                    || character == '>'
                    || character == '"') {
                return "***";
            }
        }
        return value.charAt(0) + "***" + value.substring(at);
    }
}
