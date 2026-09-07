package ooo.klae.connex.backend.mail;

/**
 * The effective SMTP settings for a single send, resolved from either a
 * workspace override or the instance default. {@code password} is plaintext,
 * held only transiently while a sender is built.
 *
 * @param configurationVersion non-secret configuration generation/version
 * @param credentialReference opaque credential/account reference, never the password
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
        int writeTimeoutMs,
        boolean workspaceSupplied,
        String configurationVersion,
        String credentialReference) {

    public ResolvedMailConfig(
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
            int writeTimeoutMs,
            boolean workspaceSupplied) {
        this(
                host,
                port,
                username,
                password,
                fromAddress,
                fromName,
                starttls,
                ssl,
                auth,
                connectionTimeoutMs,
                timeoutMs,
                writeTimeoutMs,
                workspaceSupplied,
                "unversioned",
                "smtp-account:" + String.valueOf(username));
    }

    /**
     * Whether this config has the minimum needed to send (a host and a from address).
     * @return true when a transport can be built
     */
    public boolean usable() {
        return host != null && !host.isBlank()
                && fromAddress != null && !fromAddress.isBlank();
    }

    @Override
    public String toString() {
        return "ResolvedMailConfig[host=" + host + ", port=" + port + ", username=" + username
                + ", password=<redacted>, fromAddress=" + fromAddress + ", fromName=" + fromName
                + ", starttls=" + starttls + ", ssl=" + ssl + ", auth=" + auth
                + ", connectionTimeoutMs=" + connectionTimeoutMs + ", timeoutMs=" + timeoutMs
                + ", writeTimeoutMs=" + writeTimeoutMs + ", workspaceSupplied=" + workspaceSupplied
                + ", configurationVersion=" + configurationVersion
                + ", credentialReference=<redacted>]";
    }
}
