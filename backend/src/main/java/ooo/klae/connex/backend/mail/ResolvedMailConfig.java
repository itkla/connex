package ooo.klae.connex.backend.mail;

/**
 * The effective SMTP settings for a single send, resolved from either a
 * workspace override or the instance default. {@code password} is plaintext,
 * held only transiently while a sender is built.
 */
public record ResolvedMailConfig(
        String host,
        int port,
        String username,
        String password,
        String fromAddress,
        String fromName,
        boolean starttls,
        boolean ssl,
        boolean auth,
        int connectionTimeoutMs,
        int timeoutMs,
        int writeTimeoutMs) {

    /**
     * Whether this config has the minimum needed to send (a host and a from address).
     * @return true when a transport can be built
     */
    public boolean usable() {
        return host != null && !host.isBlank()
                && fromAddress != null && !fromAddress.isBlank();
    }
}
