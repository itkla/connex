package ooo.klae.connex.backend.mail;

/**
 * A single outbound email: one recipient, a subject, and an HTML body. A plain
 * text alternative may be supplied for clients that cannot render HTML.
 */
public record MailMessage(String to, String subject, String htmlBody, String textBody) {

    /**
     * Builds a message with only an HTML body.
     * @param to recipient address
     * @param subject subject line
     * @param htmlBody rendered HTML body
     * @return the message
     */
    public static MailMessage html(String to, String subject, String htmlBody) {
        return new MailMessage(to, subject, htmlBody, null);
    }
}
