package ooo.klae.connex.backend.mail;

/**
 * Password-free, non-sendable SMTP identity selected for a workspace.
 *
 * @param host the SMTP host
 * @param port the SMTP port
 * @param username the SMTP username, or null
 * @param fromAddress the envelope sender address
 * @param starttls whether STARTTLS is required
 * @param ssl whether implicit TLS is used
 * @param auth whether the transport logs in to the SMTP server
 * @param configurationVersion the configuration generation the transport was selected from
 * @param credentialReference the opaque credential reference, never the password
 * @param usable whether the transport has everything a send needs apart from the password
 */
public record MailConfigDescription(
        String host,
        int port,
        String username,
        String fromAddress,
        boolean starttls,
        boolean ssl,
        boolean auth,
        String configurationVersion,
        String credentialReference,
        boolean usable) {
}
