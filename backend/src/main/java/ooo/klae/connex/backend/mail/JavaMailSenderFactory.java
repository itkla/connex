package ooo.klae.connex.backend.mail;

import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

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
        return cache.computeIfAbsent(fingerprint(config), key -> build(config));
    }

    private static JavaMailSender build(ResolvedMailConfig config) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
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
        props.put("mail.smtp.ssl.enable", String.valueOf(config.ssl()));
        props.put("mail.smtp.connectiontimeout", String.valueOf(config.connectionTimeoutMs()));
        props.put("mail.smtp.timeout", String.valueOf(config.timeoutMs()));
        props.put("mail.smtp.writetimeout", String.valueOf(config.writeTimeoutMs()));
        return sender;
    }

    private static String fingerprint(ResolvedMailConfig config) {
        return String.join("|",
                config.host(),
                String.valueOf(config.port()),
                String.valueOf(config.username()),
                String.valueOf(config.password() == null ? 0 : config.password().hashCode()),
                config.starttls() + ":" + config.ssl() + ":" + config.auth());
    }
}
