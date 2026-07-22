package ooo.klae.connex.backend.delivery;

/**
 * The normalized class of a provider-reported delivery event. Providers translate their own event
 * vocabulary into exactly these; {@link #DELIVERED} confirms acceptance at the recipient,
 * {@link #BOUNCED} is a hard bounce, {@link #COMPLAINED} is a spam/abuse report, and {@link #FAILED}
 * is any other terminal failure (including soft bounces) that carries no suppression obligation.
 */
public enum DeliveryEventType {
    DELIVERED("delivered"),
    BOUNCED("bounced"),
    COMPLAINED("complained"),
    FAILED("failed");

    private final String token;

    DeliveryEventType(String token) {
        this.token = token;
    }

    /**
     * Returns the lower-case wire token persisted in {@code event_type} and {@code status} columns.
     * @return the persisted token
     */
    public String token() {
        return token;
    }
}
