package ooo.klae.connex.backend.delivery.provider.smtp;

import java.net.InetAddress;
import java.util.Set;

import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.delivery.DeliveryCapabilities;
import ooo.klae.connex.backend.delivery.DeliveryChannel;
import ooo.klae.connex.backend.delivery.DeliveryRequest;
import ooo.klae.connex.backend.delivery.DispatchReceipt;
import ooo.klae.connex.backend.delivery.MessageDispatcher;
import ooo.klae.connex.backend.delivery.ResolvedDeliveryProvider;
import ooo.klae.connex.backend.mail.JavaMailSenderFactory;
import ooo.klae.connex.backend.mail.MailConfigResolver;
import ooo.klae.connex.backend.mail.ResolvedMailConfig;
import ooo.klae.connex.backend.mail.SmtpDestinationGuard;

import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

/**
 * The built-in email dispatcher. It re-resolves the workspace mail transport, pins the destination
 * through {@link SmtpDestinationGuard}, and sends synchronously — never {@code @Async} — so a send
 * outcome is known at dispatch time and recorded against the delivery row.
 */
@Service
@RequiredArgsConstructor
public class SmtpDeliveryProvider implements MessageDispatcher {

    /** The stable id this provider registers under. */
    public static final String PROVIDER_ID = "smtp";

    private static final DeliveryCapabilities CAPABILITIES =
            new DeliveryCapabilities(true, false, false, 1);
    private static final int DETAIL_LIMIT = 512;

    private final MailConfigResolver mailConfigResolver;
    private final JavaMailSenderFactory javaMailSenderFactory;
    private final SmtpDestinationGuard smtpDestinationGuard;

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public Set<DeliveryChannel> channels() {
        return Set.of(DeliveryChannel.EMAIL);
    }

    @Override
    public DeliveryCapabilities capabilities() {
        return CAPABILITIES;
    }

    @Override
    public DispatchReceipt dispatch(ResolvedDeliveryProvider target, DeliveryRequest request) {
        ResolvedMailConfig config = mailConfigResolver.resolveForWorkspace(target.workspaceId());
        if (config == null || !config.usable()) {
            return DispatchReceipt.rejected("No usable mail transport is configured");
        }
        try {
            InetAddress pinned = smtpDestinationGuard.resolveForSend(config);
            JavaMailSender sender = javaMailSenderFactory.forConfig(config, pinned);
            MimeMessage mime = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(
                    mime, request.content().bodyText() != null, "UTF-8");
            helper.setTo(request.address());
            helper.setSubject(request.content().subject());
            if (request.content().bodyText() != null) {
                helper.setText(request.content().bodyText(), request.content().bodyHtml());
            } else {
                helper.setText(request.content().bodyHtml(), true);
            }
            if (config.fromName() != null && !config.fromName().isBlank()) {
                helper.setFrom(new InternetAddress(config.fromAddress(), config.fromName(), "UTF-8"));
            } else {
                helper.setFrom(config.fromAddress());
            }
            sender.send(mime);
            return DispatchReceipt.sent(null, "smtp accepted");
        } catch (MailException exception) {
            return DispatchReceipt.rejected(bounded(exception.getMessage()));
        } catch (Exception exception) {
            return DispatchReceipt.rejected(bounded(exception.getMessage()));
        }
    }

    private static String bounded(String message) {
        if (message == null || message.isBlank()) {
            return "smtp rejected";
        }
        String trimmed = message.trim();
        return trimmed.length() > DETAIL_LIMIT ? trimmed.substring(0, DETAIL_LIMIT) : trimmed;
    }
}
