package ooo.klae.connex.backend.mail;

import java.net.InetAddress;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.SocketFactory;

import jakarta.mail.MessagingException;
import jakarta.mail.NoSuchProviderException;
import jakarta.mail.Session;
import jakarta.mail.Transport;

import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

/**
 * Builds and caches a {@link JavaMailSender} per distinct resolved SMTP config,
 * so repeated sends to the same server reuse one sender rather than rebuilding
 * the transport each time.
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
     * @param config the resolved SMTP settings
     * @param pinnedAddress the approved destination address, or null to retain hostname routing
     * @param remainingNanos the remaining absolute-deadline budget
     * @return the sender and its immediate transport abort action
     */
    public DeadlineBoundSender deadlineBoundForConfig(
            ResolvedMailConfig config, InetAddress pinnedAddress, long remainingNanos) {
        int remainingMillis = remainingMillis(remainingNanos);
        SocketFactory socketFactory;
        Runnable socketAbort;
        if (pinnedAddress == null) {
            TrackingSocketFactory trackingSocketFactory = new TrackingSocketFactory();
            socketFactory = trackingSocketFactory;
            socketAbort = trackingSocketFactory::abort;
        } else {
            PinnedSocketFactory pinnedSocketFactory =
                    new PinnedSocketFactory(pinnedAddress, config.port());
            socketFactory = pinnedSocketFactory;
            socketAbort = pinnedSocketFactory::abort;
        }
        DeadlineJavaMailSender sender = new DeadlineJavaMailSender();
        configure(sender, config, socketFactory, remainingMillis);
        return new DeadlineBoundSender(sender, () -> {
            socketAbort.run();
            sender.abortTransport();
        });
    }

    private static JavaMailSender build(
            ResolvedMailConfig config,
            PinnedSocketFactory pinnedSocketFactory,
            Integer remainingMillis) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        configure(sender, config, pinnedSocketFactory, remainingMillis);
        return sender;
    }

    private static void configure(
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
        props.put("mail.smtp.writetimeout", String.valueOf(
                boundedTimeout(config.writeTimeoutMs(), remainingMillis)));
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

    /** An SMTP sender and the hard-cancellation action for its active transport. */
    public record DeadlineBoundSender(JavaMailSender sender, Runnable abort) {
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
