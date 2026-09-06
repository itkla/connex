package ooo.klae.connex.backend.mail;

import java.net.InetAddress;
import java.util.Date;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.SocketFactory;

import jakarta.mail.Address;
import jakarta.mail.MessagingException;
import jakarta.mail.NoSuchProviderException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.MimeMessage;

import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

/**
 * Builds and caches a {@link JavaMailSender} per distinct resolved SMTP config,
 * so repeated sends to the same server reuse one sender rather than rebuilding
 * the transport each time.
 *
 * <p>Every socket factory installed here produces plain TCP sockets, for implicit TLS as much as
 * for STARTTLS. The mail library resolves {@code mail.smtp.ssl.socketFactory} before
 * {@code mail.smtp.socketFactory}, so publishing an SSL socket factory would make it bypass this
 * factory entirely and open its own socket to the configured hostname, losing both the approved
 * destination pin and the socket the deadline abort has to close. Leaving only the plain factory
 * makes the library layer TLS over the socket this factory opened, using the JVM's default trust
 * material.
 */
@Component
public class JavaMailSenderFactory {

    private final ConcurrentHashMap<String, JavaMailSender> cache = new ConcurrentHashMap<>();

    /**
     * Returns a sender for the given resolved config, building and caching one on first use.
     * @param config the resolved SMTP settings
     * @return a configured mail sender
     */
    public JavaMailSender forConfig(ResolvedMailConfig config) {
        return forConfig(config, null);
    }

    /**
     * Returns a sender that connects to the supplied prevalidated address while retaining the
     * configured hostname for SMTP TLS identity verification.
     *
     * @param config the resolved SMTP settings
     * @param pinnedAddress the approved destination address, or null for an unpinned trusted transport
     * @return a configured mail sender
     */
    public JavaMailSender forConfig(ResolvedMailConfig config, InetAddress pinnedAddress) {
        if (pinnedAddress != null) {
            return build(config, new PinnedSocketFactory(pinnedAddress, config.port()), null);
        }
        if (config.password() != null) {
            return build(config, null, null);
        }
        return cache.computeIfAbsent(fingerprint(config), key -> build(config, null, null));
    }

    /**
     * Returns an uncached sender whose transport can be closed at the absolute provider deadline.
     *
     * <p>The deadline path deliberately omits {@code mail.smtp.writetimeout}. Any positive value
     * makes the mail library wrap the socket in its own write-timeout socket, which allocates a
     * private scheduled executor per send, and the absolute-deadline abort already bounds a body
     * write by closing the raw socket the TLS layer sits on. Senders without a deadline keep the
     * configured write timeout, which is their only write bound.
     *
     * @param config the resolved SMTP settings
     * @param resolvedAddress the pre-resolved destination address
     * @param pinnedDestination whether the address was approved by the workspace SMTP destination guard
     * @param remainingNanos the remaining absolute-deadline budget
     * @return the sender and its immediate transport abort action
     */
    public DeadlineBoundSender deadlineBoundForConfig(
            ResolvedMailConfig config, InetAddress resolvedAddress, boolean pinnedDestination,
            long remainingNanos) {
        int remainingMillis = remainingMillis(remainingNanos);
        SocketFactory socketFactory;
        Runnable socketAbort;
        if (!pinnedDestination) {
            TrackingSocketFactory trackingSocketFactory =
                    new TrackingSocketFactory(resolvedAddress, config.port());
            socketFactory = trackingSocketFactory;
            socketAbort = trackingSocketFactory::abort;
        } else {
            PinnedSocketFactory pinnedSocketFactory =
                    new PinnedSocketFactory(resolvedAddress, config.port());
            socketFactory = pinnedSocketFactory;
            socketAbort = pinnedSocketFactory::abort;
        }
        DeadlineJavaMailSender sender = new DeadlineJavaMailSender();
        configure(sender, config, socketFactory, remainingMillis);
        return new DeadlineBoundSender(sender, () -> {
            socketAbort.run();
            sender.abortTransport();
        }, sender::connectAndSend);
    }

    private JavaMailSender build(
            ResolvedMailConfig config,
            PinnedSocketFactory pinnedSocketFactory,
            Integer remainingMillis) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        configure(sender, config, pinnedSocketFactory, remainingMillis);
        return sender;
    }

    private void configure(
            JavaMailSenderImpl sender,
            ResolvedMailConfig config,
            SocketFactory socketFactory,
            Integer remainingMillis) {
        sender.setHost(config.host());
        sender.setPort(config.port());
        sender.setDefaultEncoding("UTF-8");
        if (config.username() != null && !config.username().isBlank()) {
            sender.setUsername(config.username());
        }
        if (config.password() != null) {
            sender.setPassword(config.password());
        }

        Properties props = sender.getJavaMailProperties();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.auth", String.valueOf(config.auth()));
        props.put("mail.smtp.starttls.enable", String.valueOf(config.starttls()));
        props.put("mail.smtp.starttls.required", String.valueOf(config.starttls()));
        props.put("mail.smtp.ssl.enable", String.valueOf(config.ssl()));
        props.put("mail.smtp.ssl.checkserveridentity", String.valueOf(config.starttls() || config.ssl()));
        props.put("mail.smtp.connectiontimeout", String.valueOf(
                boundedTimeout(config.connectionTimeoutMs(), remainingMillis)));
        props.put("mail.smtp.timeout", String.valueOf(
                boundedTimeout(config.timeoutMs(), remainingMillis)));
        if (remainingMillis == null) {
            props.put("mail.smtp.writetimeout", String.valueOf(config.writeTimeoutMs()));
        }
        if (socketFactory != null) {
            props.put("mail.smtp.socketFactory", socketFactory);
            props.put("mail.smtp.socketFactory.fallback", "false");
        }
    }

    private static int boundedTimeout(int configuredMillis, Integer remainingMillis) {
        return remainingMillis == null
                ? configuredMillis
                : Math.max(1, Math.min(configuredMillis, remainingMillis));
    }

    private static int remainingMillis(long remainingNanos) {
        if (remainingNanos <= 0) {
            throw new IllegalArgumentException("SMTP provider deadline is exhausted");
        }
        long roundedUp = 1L + (remainingNanos - 1L) / 1_000_000L;
        return (int) Math.min(roundedUp, Integer.MAX_VALUE);
    }

    private static String fingerprint(ResolvedMailConfig config) {
        return String.join("|",
                config.host(),
                String.valueOf(config.port()),
                String.valueOf(config.username()),
                config.starttls() + ":" + config.ssl() + ":" + config.auth());
    }

    /** An SMTP sender with an explicit connection boundary and hard-cancellation action. */
    public record DeadlineBoundSender(
            JavaMailSender sender, Runnable abort, ConnectedSend connectedSend) {

        /** Connects and authenticates before announcing that SMTP submission is about to begin. */
        public void connectAndSend(MimeMessage message, Runnable afterConnect)
                throws MessagingException {
            connectedSend.send(message, afterConnect);
        }
    }

    /** Sends one message after exposing the completed transport-connection boundary. */
    @FunctionalInterface
    public interface ConnectedSend {

        /** Connects, invokes the callback, then submits the message. */
        void send(MimeMessage message, Runnable afterConnect) throws MessagingException;
    }

    /**
     * Reports whether a mail failure was caused by the absolute deadline before a transport existed.
     * @param failure the sender failure
     * @return true when no provider transport could have submitted message data
     */
    public static boolean isDeadlineBeforeTransport(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof DeadlineBeforeTransportException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static final class DeadlineJavaMailSender extends JavaMailSenderImpl {

        private final AtomicBoolean aborted = new AtomicBoolean();
        private final AtomicReference<Transport> transport = new AtomicReference<>();

        @Override
        protected Transport getTransport(Session session) throws NoSuchProviderException {
            requireActive();
            Transport current = super.getTransport(session);
            transport.set(current);
            if (aborted.get()) {
                close(current);
                throw deadlineExpired();
            }
            return current;
        }

        private void abortTransport() {
            aborted.set(true);
            Transport current = transport.get();
            if (current != null) {
                close(current);
            }
        }

        private void connectAndSend(MimeMessage message, Runnable afterConnect)
                throws MessagingException {
            Transport current = null;
            try {
                current = connectTransport();
                if (message.getSentDate() == null) {
                    message.setSentDate(new Date());
                }
                String messageId = message.getMessageID();
                message.saveChanges();
                if (messageId != null) {
                    message.setHeader("Message-ID", messageId);
                }
                Address[] recipients = message.getAllRecipients();
                afterConnect.run();
                current.sendMessage(message, recipients == null ? new Address[0] : recipients);
            } finally {
                Transport active = transport.getAndSet(null);
                if (active != null) {
                    close(active);
                } else if (current != null) {
                    close(current);
                }
            }
        }

        private static void close(Transport transport) {
            try {
                transport.close();
            } catch (MessagingException exception) {
                return;
            }
        }

        private void requireActive() throws NoSuchProviderException {
            if (aborted.get()) {
                throw deadlineExpired();
            }
        }

        private static NoSuchProviderException deadlineExpired() {
            return new DeadlineBeforeTransportException();
        }
    }

    private static final class DeadlineBeforeTransportException extends NoSuchProviderException {

        private DeadlineBeforeTransportException() {
            super("SMTP provider deadline expired before transport creation");
        }
    }
}
