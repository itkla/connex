package ooo.klae.connex.backend.mail;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Set;

import org.springframework.stereotype.Component;

import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.util.OutboundAddressSafety;

/**
 * Resolves and validates workspace-managed SMTP destinations immediately before use.
 */
@Component
public class SmtpDestinationGuard {

    private static final Set<Integer> ALLOWED_SMTP_PORTS = Set.of(25, 465, 587, 2525);

    private final MailProperties mailProperties;

    public SmtpDestinationGuard(MailProperties mailProperties) {
        this.mailProperties = mailProperties;
    }

    /**
     * Resolves a workspace-managed destination for a send and returns the address to pin.
     *
     * @param config the resolved transport configuration
     * @return the validated address, or null for trusted instance and explicitly internal transports
     */
    public InetAddress resolveForSend(ResolvedMailConfig config) {
        if (!config.workspaceSupplied()) {
            return null;
        }
        return requirePublicDestination(config.host(), config.port());
    }

    /** Returns whether this deployment explicitly permits internal SMTP destinations. */
    public boolean allowsInternalHosts() {
        return mailProperties.isAllowInternalHosts();
    }

    /**
     * Validates a workspace-managed host and port and returns the resolved address to pin.
     *
     * @param host the SMTP host
     * @param port the SMTP port
     * @return the validated address, or null when internal hosts are explicitly allowed
     */
    public InetAddress requirePublicDestination(String host, int port) {
        if (mailProperties.isAllowInternalHosts()) {
            return null;
        }
        if (!ALLOWED_SMTP_PORTS.contains(port)) {
            throw new BadRequestException("SMTP port must be one of 25, 465, 587, or 2525");
        }
        if (host == null || host.isBlank()) {
            throw new BadRequestException("The SMTP host could not be resolved");
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host.trim());
        } catch (UnknownHostException exception) {
            throw new BadRequestException("The SMTP host could not be resolved");
        }
        if (addresses.length == 0) {
            throw new BadRequestException("The SMTP host could not be resolved");
        }
        for (InetAddress address : addresses) {
            if (isInternalAddress(address)) {
                throw new BadRequestException(
                    "The SMTP host must be a public server; private and loopback addresses are not allowed");
            }
        }
        return addresses[0];
    }

    /** Validates a destination using the instance SMTP port when the workspace omits one. */
    public InetAddress requirePublicDestination(String host, Integer port) {
        return requirePublicDestination(host, port == null ? mailProperties.getPort() : port);
    }

    static boolean isInternalAddress(InetAddress address) {
        return OutboundAddressSafety.isInternalAddress(address);
    }
}
