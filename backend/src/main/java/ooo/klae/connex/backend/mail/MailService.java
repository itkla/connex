package ooo.klae.connex.backend.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.util.ContactMask;

/**
 * The single choke point for outbound email. Resolves the effective SMTP config
 * (instance default for account mail, managed instance transport or workspace override
 * for workspace mail),
 * builds a MIME message and delivers it. The {@code send*} entry points run
 * asynchronously and never propagate delivery failures, so a mail outage cannot
 * break the request that triggered it; {@link #sendNow(ResolvedMailConfig, MailMessage)}
 * is the synchronous path used by the "send test email" action, which surfaces failures.
 */
@Service
@RequiredArgsConstructor
public class MailService {

    private static final Logger log = LoggerFactory.getLogger(MailService.class);

    private final MailConfigResolver resolver;
    private final JavaMailSenderFactory senderFactory;
    private final SmtpDestinationGuard smtpDestinationGuard;

    /**
     * Sends account-level mail through the instance default sender, off-thread.
     * @param message the message to deliver
     */
    @Async
    public void sendInstance(MailMessage message) {
        ResolvedMailConfig config = resolver.resolveInstance();
        deliverQuietly(config, message, "instance");
    }

    /**
     * Sends workspace-scoped mail through the managed instance transport or the
     * workspace's sender, falling back to the instance default, off-thread.
     * @param workspaceId the workspace whose sender to use
     * @param message the message to deliver
     */
    @Async
    public void sendForWorkspace(int workspaceId, MailMessage message) {
        ResolvedMailConfig config = resolver.resolveForWorkspace(workspaceId);
        deliverQuietly(config, message, "workspace " + workspaceId);
    }

    /**
     * Delivers synchronously, throwing on failure. Used by the test-email action so
     * the admin sees the real transport error.
     * @param config the resolved SMTP settings to use
     * @param message the message to deliver
     */
    public void sendNow(ResolvedMailConfig config, MailMessage message) {
        if (config == null || !config.usable()) {
            throw new IllegalStateException("No usable SMTP configuration");
        }
        deliver(config, message);
    }

    private void deliverQuietly(ResolvedMailConfig config, MailMessage message, String source) {
        if (config == null || !config.usable()) {
            log.warn("Email to {} not sent: no usable SMTP configuration ({})",
                    ContactMask.maskEmail(message.to()), source);
            return;
        }
        try {
            deliver(config, message);
        } catch (Exception e) {
            log.error("Failed to send email to {} ({}): {}",
                    ContactMask.maskEmail(message.to()), source, e.getMessage());
        }
    }

    private void deliver(ResolvedMailConfig config, MailMessage message) {
        JavaMailSender sender = senderFactory.forConfig(config, smtpDestinationGuard.resolveForSend(config));
        try {
            MimeMessage mime = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mime, message.textBody() != null, "UTF-8");
            helper.setTo(message.to());
            helper.setSubject(message.subject());
            if (message.textBody() != null) {
                helper.setText(message.textBody(), message.htmlBody());
            } else {
                helper.setText(message.htmlBody(), true);
            }
            if (config.fromName() != null && !config.fromName().isBlank()) {
                helper.setFrom(new InternetAddress(config.fromAddress(), config.fromName(), "UTF-8"));
            } else {
                helper.setFrom(config.fromAddress());
            }
            sender.send(mime);
        } catch (Exception e) {
            throw new IllegalStateException("SMTP delivery failed: " + e.getMessage(), e);
        }
    }
}
