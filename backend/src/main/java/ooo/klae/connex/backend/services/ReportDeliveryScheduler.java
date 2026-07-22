package ooo.klae.connex.backend.services;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.ReportSchedule;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.ReportDocumentDto;
import ooo.klae.connex.backend.dto.ReportNarrativeSectionDto;
import ooo.klae.connex.backend.dto.ReportScheduleRef;
import ooo.klae.connex.backend.dto.ReportWidgetDataDto;
import ooo.klae.connex.backend.mail.EmailTemplateRenderer;
import ooo.klae.connex.backend.mail.MailMessage;
import ooo.klae.connex.backend.mail.MailProperties;
import ooo.klae.connex.backend.mail.MailService;
import ooo.klae.connex.backend.mappers.ScheduleMapper;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/**
 * Claims, generates, and queues due saved-report deliveries under each schedule's
 * real member identity. Claims commit before generation and sending, providing
 * at-most-once enqueue semantics. Multi-instance leadership or distributed
 * deduplication is out of scope, matching the application's sibling schedulers.
 */
@Component
@RequiredArgsConstructor
public class ReportDeliveryScheduler {
    private static final Logger log = LoggerFactory.getLogger(ReportDeliveryScheduler.class);
    private static final DeliveryCopy ENGLISH_COPY = new DeliveryCopy(
            "Scheduled report: ",
            "Your scheduled report is ready to review in Connex.",
            "Report",
            "Ready");
    private static final DeliveryCopy JAPANESE_COPY = new DeliveryCopy(
            "定期レポート: ",
            "定期レポートを Connex で確認できます。",
            "レポート",
            "準備完了");

    private final ScheduleMapper scheduleMapper;
    private final PlacementRegistry placementRegistry;
    private final TenantWorkScope tenantWorkScope;
    private final ScheduleService scheduleService;
    private final AutomationExecutor automationExecutor;
    private final ReportService reportService;
    private final WorkspaceService workspaceService;
    private final AuditService auditService;
    private final EmailTemplateRenderer templateRenderer;
    private final MailProperties mailProperties;
    private final MailService mailService;
    private final Clock clock;

    @Value("${connex.reports.scheduling-enabled:true}")
    private boolean schedulingEnabled;

    @Scheduled(
        fixedDelayString = "${connex.reports.delivery-delay-ms:300000}",
        initialDelayString = "${connex.reports.initial-delay-ms:300000}")
    public void deliverDue() {
        if (!schedulingEnabled) {
            return;
        }
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        for (String catalog : placementRegistry.activeCatalogs()) {
            try {
                List<ReportScheduleRef> refs = tenantWorkScope.withCatalog(
                        catalog, () -> scheduleMapper.dueScheduleRefs(now));
                for (ReportScheduleRef ref : refs) {
                    try {
                        tenantWorkScope.inWorkspace(ref.workspaceId(), () -> process(ref, now));
                    } catch (Exception exception) {
                        log.warn("Report schedule {} failed in workspace {}: {}",
                                ref.scheduleId(), ref.workspaceId(), exception.getMessage());
                        auditFailure(ref, "scheduled report delivery failed");
                    }
                }
            } catch (Exception exception) {
                log.warn("Report delivery sweep failed for catalog {}: {}",
                        catalog == null ? "(default)" : catalog, exception.getMessage());
            }
        }
    }

    private void process(ReportScheduleRef ref, LocalDateTime now) {
        ReportSchedule schedule = scheduleService.loadForDelivery(ref.workspaceId(), ref.scheduleId());
        if (schedule == null) {
            return;
        }
        ScheduleService.DeliveryAccess access = scheduleService.deliveryAccess(schedule);
        if (!access.allowed()) {
            skip(ref, schedule.getRunAsUserId(), now, access.denialReason());
            return;
        }
        automationExecutor.runAs(ref.workspaceId(), access.user(), access.role(), () -> {
            deliver(ref, schedule.getRunAsUserId(), now);
            return null;
        });
    }

    private void deliver(ReportScheduleRef ref, int expectedRunAsUserId, LocalDateTime now) {
        ReportSchedule current = scheduleService.loadForDelivery(ref.workspaceId(), ref.scheduleId());
        if (current == null || current.getRunAsUserId() != expectedRunAsUserId) {
            return;
        }
        ScheduleService.DeliveryAccess access = scheduleService.deliveryAccess(current);
        if (!access.allowed() || access.user().getId() != expectedRunAsUserId) {
            skip(ref, expectedRunAsUserId, now, access.denialReason());
            return;
        }
        ReportSchedule claimed = scheduleService.claimDue(ref.scheduleId(), expectedRunAsUserId, now);
        if (claimed == null) {
            return;
        }
        List<User> recipients = scheduleService.activeReportReaders(claimed);
        if (recipients.isEmpty()) {
            if (scheduleService.isCurrentDeliverySchedule(claimed)) {
                auditFailure(ref, "no eligible report recipients");
            }
            return;
        }
        ReportDocumentDto document = reportService.generate(
                claimed.getReportDefinitionId(), null, ReportService.NarrativeMode.FULL);
        recipients = scheduleService.activeRecipientsForDocument(claimed, document);
        if (recipients.isEmpty()) {
            if (scheduleService.isCurrentDeliverySchedule(claimed)) {
                auditFailure(ref, "no eligible report recipients");
            }
            return;
        }
        for (User recipient : recipients) {
            send(recipient, claimed, document);
        }
        auditQueued(ref, document, recipients.size());
    }

    private void send(User recipient, ReportSchedule schedule, ReportDocumentDto document) {
        Locale locale = LocaleSupport.resolve(recipient.getLocale());
        DeliveryCopy copy = deliveryCopy(locale);
        DateTimeFormatter periodDate =
                DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale);
        String reportName = document.definition().name();
        String period = periodDate.format(document.periodStart()) + " - " + periodDate.format(document.periodEnd());
        List<Headline> headlines = document.widgets().stream()
                .filter(widget -> widget.total() != null)
                .limit(2)
                .map(widget -> headline(widget, locale))
                .toList();
        Headline first = headlines.isEmpty()
                ? new Headline(copy.fallbackHeadlineLabel(), copy.fallbackHeadlineValue())
                : headlines.getFirst();
        Headline second = headlines.size() < 2 ? new Headline("", "") : headlines.get(1);
        String actionUrl = UriComponentsBuilder.fromUriString(mailProperties.getAppBaseUrl())
                .path("/overview/reports/")
                .path(String.valueOf(schedule.getReportDefinitionId()))
                .build()
                .toUriString();
        String body = templateRenderer.render("report-delivery", locale.getLanguage(), Map.of(
                "reportName", reportName,
                "period", period,
                "summary", summary(document, copy.fallbackSummary()),
                "headlineOneLabel", first.label(),
                "headlineOneValue", first.value(),
                "headlineTwoLabel", second.label(),
                "headlineTwoValue", second.value(),
                "actionUrl", actionUrl));
        mailService.sendForWorkspace(schedule.getWorkspaceId(),
                MailMessage.html(recipient.getEmail(), copy.subjectPrefix() + reportName, body));
    }

    private Headline headline(ReportWidgetDataDto widget, Locale locale) {
        return new Headline(widget.title(), formatValue(widget.total(), widget.unit(), locale));
    }

    private String summary(ReportDocumentDto document, String fallback) {
        if (document.narrative() == null || document.narrative().sections() == null) {
            return fallback;
        }
        for (ReportNarrativeSectionDto section : document.narrative().sections()) {
            if (section.claims() != null && !section.claims().isEmpty()) {
                String text = section.claims().getFirst().text();
                if (text != null && !text.isBlank()) {
                    return text.length() > 240 ? text.substring(0, 240) : text;
                }
            }
        }
        return fallback;
    }

    private static java.util.Currency safeCurrency(String unit) {
        try {
            return java.util.Currency.getInstance(unit);
        } catch (IllegalArgumentException unknownCurrency) {
            return null;
        }
    }

    private String formatValue(BigDecimal value, String unit, Locale locale) {
        if (value == null) {
            return "-";
        }
        if (unit != null && unit.matches("[A-Z]{3}")) {
            java.util.Currency iso = safeCurrency(unit);
            if (iso != null) {
                NumberFormat currency = NumberFormat.getCurrencyInstance(locale);
                currency.setCurrency(iso);
                currency.setMaximumFractionDigits(0);
                return currency.format(value);
            }
        }
        String formatted = value.setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
        if ("percent".equals(unit) || "%".equals(unit)) {
            return formatted + "%";
        }
        if (unit != null && unit.matches("[A-Z]{4,8}")) {
            return formatted + " " + unit;
        }
        return formatted;
    }

    private static DeliveryCopy deliveryCopy(Locale locale) {
        return Locale.JAPANESE.getLanguage().equals(locale.getLanguage())
                ? JAPANESE_COPY
                : ENGLISH_COPY;
    }

    private void skip(
            ReportScheduleRef ref,
            int expectedRunAsUserId,
            LocalDateTime now,
            String reason) {
        if (scheduleService.skipDue(
                ref.workspaceId(), ref.scheduleId(), expectedRunAsUserId, now)) {
            String stableReason = reason == null ? "run-as validation failed" : reason;
            log.warn("Report schedule {} skipped in workspace {}: {}",
                    ref.scheduleId(), ref.workspaceId(), stableReason);
            auditSkip(ref, stableReason);
        }
    }

    private void auditSkip(ReportScheduleRef ref, String reason) {
        tenantWorkScope.unrouted(() -> {
            auditService.recordFailureScoped(
                    "report.schedule.skip", "report_schedule", ref.scheduleId(),
                    ref.workspaceId(), workspaceService.getOrgId(ref.workspaceId()), null,
                    "Skipped scheduled report delivery", reason);
            return null;
        });
    }

    private void auditQueued(ReportScheduleRef ref, ReportDocumentDto document, int recipientCount) {
        tenantWorkScope.unrouted(() -> {
            auditService.recordScoped(
                    "report.schedule.delivery", "report_schedule", ref.scheduleId(),
                    ref.workspaceId(), workspaceService.getOrgId(ref.workspaceId()), document.definition().name(),
                    "Queued scheduled report delivery", Map.of("recipientCount", recipientCount));
            return null;
        });
    }

    private void auditFailure(ReportScheduleRef ref, String reason) {
        tenantWorkScope.unrouted(() -> {
            auditService.recordFailureScoped(
                    "report.schedule.delivery", "report_schedule", ref.scheduleId(),
                    ref.workspaceId(), workspaceService.getOrgId(ref.workspaceId()), null,
                    "Scheduled report delivery failed", reason);
            return null;
        });
    }

    private record Headline(String label, String value) {
    }

    private record DeliveryCopy(
            String subjectPrefix,
            String fallbackSummary,
            String fallbackHeadlineLabel,
            String fallbackHeadlineValue) {
    }
}
