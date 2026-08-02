package ooo.klae.connex.backend.services;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.MailDiagnosticTestDto;
import ooo.klae.connex.backend.dto.MailDiagnosticTestDto.Dns;
import ooo.klae.connex.backend.dto.MailDiagnosticTestDto.Sender;
import ooo.klae.connex.backend.dto.MailDiagnosticTestDto.Transport;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.mail.EmailTemplateRenderer;
import ooo.klae.connex.backend.mail.MailConfigResolver;
import ooo.klae.connex.backend.mail.MailDnsDiagnosticsService;
import ooo.klae.connex.backend.mail.MailMessage;
import ooo.klae.connex.backend.mail.MailService;
import ooo.klae.connex.backend.mail.ResolvedMailConfig;
import ooo.klae.connex.backend.mail.SmtpDestinationGuard;
import ooo.klae.connex.backend.mappers.UserMapper;

/**
 * Sends a non-mutating effective-mail diagnostic only to the authenticated actor.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MailDiagnosticsService {
    private static final String ENGLISH_TEST_SUBJECT = "Connex email test";
    private static final String JAPANESE_TEST_SUBJECT = "Connex テストメール";

    private final SessionSecurityService sessionSecurityService;
    private final UserMapper userMapper;
    private final MailConfigResolver mailConfigResolver;
    private final SmtpDestinationGuard smtpDestinationGuard;
    private final EmailTemplateRenderer templateRenderer;
    private final MailService mailService;
    private final MailDnsDiagnosticsService mailDnsDiagnosticsService;
    private final AuditService auditService;

    /**
     * Sends through the effective managed, override, or fallback transport without mutating config.
     *
     * @param workspaceId permission-gated workspace
     * @param actorId authenticated actor
     * @return redacted transport and advisory DNS outcome
     */
    public MailDiagnosticTestDto testSend(int workspaceId, int actorId) {
        sessionSecurityService.requireRecentAuthentication(actorId);
        User actor = userMapper.getUserById(actorId);
        String correlationId = UUID.randomUUID().toString();
        if (actor == null || actor.getEmail() == null || actor.getEmail().isBlank()) {
            Dns dns = mailDnsDiagnosticsService.diagnose(null);
            return new MailDiagnosticTestDto(
                    correlationId,
                    new Sender(null, null),
                    new Transport(
                            "unconfigured", null, null, "unconfigured", "actor_email_unconfigured"),
                    dns);
        }

        if (!actor.isEmailVerified()) {
            Dns dns = mailDnsDiagnosticsService.diagnose(null);
            return new MailDiagnosticTestDto(
                    correlationId,
                    new Sender(null, null),
                    new Transport(
                            "unconfigured", null, null, "unconfigured", "actor_email_unverified"),
                    dns);
        }

        ResolvedMailConfig config;
        try {
            config = mailConfigResolver.resolveForWorkspace(workspaceId);
        } catch (RuntimeException exception) {
            Dns dns = mailDnsDiagnosticsService.diagnose(null);
            return new MailDiagnosticTestDto(
                    correlationId,
                    new Sender(null, null),
                    new Transport(
                            mailConfigResolver.effectiveMode(null),
                            null,
                            null,
                            "failed",
                            "mail_resolution_failed"),
                    dns);
        }
        String mode = mailConfigResolver.effectiveMode(config);
        if (config == null || !config.usable()) {
            Dns dns = mailDnsDiagnosticsService.diagnose(null);
            return new MailDiagnosticTestDto(
                    correlationId,
                    new Sender(null, null),
                    new Transport(mode, null, null, "unconfigured", "mail_unconfigured"),
                    dns);
        }

        Sender sender = senderIdentity(config);
        String visibleHost = tenantVisibleHost(config);
        Integer visiblePort = tenantVisiblePort(config);
        Transport transport;
        try {
            smtpDestinationGuard.resolveForSend(config);
            Locale locale = LocaleSupport.resolve(actor.getLocale());
            String body = templateRenderer.render(
                    "test", locale.getLanguage(), Map.of("recipient", actor.getEmail()));
            String subject = Locale.JAPANESE.equals(locale)
                    ? JAPANESE_TEST_SUBJECT
                    : ENGLISH_TEST_SUBJECT;
            mailService.sendNow(
                    config,
                    MailMessage.html(actor.getEmail(), subject, body));
            transport = new Transport(
                    mode, visibleHost, visiblePort, "succeeded", recordTestAudit(workspaceId, actor));
        } catch (BadRequestException exception) {
            transport = new Transport(
                    mode,
                    visibleHost,
                    visiblePort,
                    "failed",
                    "smtp_destination_rejected");
        } catch (RuntimeException exception) {
            transport = new Transport(
                    mode,
                    visibleHost,
                    visiblePort,
                    "failed",
                    "smtp_transport_failed");
        }
        Dns dns = mailDnsDiagnosticsService.diagnose(
                config.workspaceSupplied() ? config.fromAddress() : null);
        return new MailDiagnosticTestDto(correlationId, sender, transport, dns);
    }

    private String recordTestAudit(int workspaceId, User actor) {
        try {
            auditService.record(
                    "workspace.mail_config.test",
                    "workspace",
                    workspaceId,
                    actor.getEmail(),
                    "Sent a diagnostic test email",
                    null);
            return null;
        } catch (RuntimeException exception) {
            log.warn("Diagnostic test email audit could not be written for workspace {}", workspaceId);
            return "audit_not_recorded";
        }
    }

    /**
     * Returns the sender identity for the response.
     *
     * <p>The sender address is deliberately reported for managed and instance transports too,
     * unlike the SMTP host and port. The test message is delivered to the requesting
     * administrator's own mailbox, so its {@code From} address is already disclosed to exactly
     * this actor and withholding it in the response only makes the report harder to read. The
     * relay host and port are withheld because they are not otherwise disclosed and a tenant
     * cannot act on the operator's transport, and the advisory DNS lookup is skipped for a
     * sender the tenant does not control because it has no tenant value and is the
     * domain-existence oracle vector.
     *
     * @param config resolved transport
     * @return redacted sender identity
     */
    private static Sender senderIdentity(ResolvedMailConfig config) {
        return new Sender(
                safeAddress(config.fromAddress(), config.username()),
                safeDisplayName(config.fromName()));
    }

    private static String tenantVisibleHost(ResolvedMailConfig config) {
        return config.workspaceSupplied() ? safeHost(config.host()) : null;
    }

    private static Integer tenantVisiblePort(ResolvedMailConfig config) {
        return config.workspaceSupplied() ? config.port() : null;
    }

    private static String safeAddress(String address, String username) {
        if (MailDnsDiagnosticsService.senderDomain(address) == null) {
            return null;
        }
        String trimmed = address.trim();
        if (username != null && trimmed.equalsIgnoreCase(username.trim())) {
            return null;
        }
        int separator = trimmed.indexOf('@');
        String local = trimmed.substring(0, separator);
        if (trimmed.length() > 320 || local.length() > 64
                || local.chars().anyMatch(Character::isWhitespace)
                || local.matches(".*[:/\\\\?#].*")) {
            return null;
        }
        return trimmed;
    }

    private static String safeHost(String host) {
        if (host == null) {
            return null;
        }
        String trimmed = host.trim();
        if (trimmed.isEmpty() || trimmed.length() > 253
                || !trimmed.matches("[A-Za-z0-9.-]+")) {
            return null;
        }
        return trimmed;
    }

    private static String safeDisplayName(String displayName) {
        if (displayName == null) {
            return null;
        }
        String trimmed = displayName.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
