package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import ooo.klae.connex.backend.beans.ReportSchedule;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.ReportConfig;
import ooo.klae.connex.backend.dto.ReportDefinitionDto;
import ooo.klae.connex.backend.dto.ReportDocumentDto;
import ooo.klae.connex.backend.dto.ReportFilters;
import ooo.klae.connex.backend.dto.ReportLayoutItem;
import ooo.klae.connex.backend.dto.ReportNarrativeClaimDto;
import ooo.klae.connex.backend.dto.ReportNarrativeDto;
import ooo.klae.connex.backend.dto.ReportNarrativeSectionDto;
import ooo.klae.connex.backend.dto.ReportScheduleRef;
import ooo.klae.connex.backend.dto.ReportSnapshotDto;
import ooo.klae.connex.backend.dto.ReportWidgetConfig;
import ooo.klae.connex.backend.dto.ReportWidgetDataDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.mail.EmailTemplateRenderer;
import ooo.klae.connex.backend.mail.MailMessage;
import ooo.klae.connex.backend.mail.MailProperties;
import ooo.klae.connex.backend.mail.MailService;
import ooo.klae.connex.backend.mappers.ScheduleMapper;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

@ExtendWith(MockitoExtension.class)
class ReportDeliverySchedulerTest {
    private static final int WORKSPACE_ID = 11;
    private static final int SCHEDULE_ID = 22;
    private static final int REPORT_ID = 33;
    private static final int SNAPSHOT_ID = 55;
    private static final int USER_ID = 44;
    private static final Instant NOW = Instant.parse("2026-07-14T09:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Mock private ScheduleMapper scheduleMapper;
    @Mock private PlacementRegistry placementRegistry;
    @Mock private TenantWorkScope tenantWorkScope;
    @Mock private ScheduleService scheduleService;
    @Mock private AutomationExecutor automationExecutor;
    @Mock private ReportService reportService;
    @Mock private WorkspaceService workspaceService;
    @Mock private AuditService auditService;
    @Mock private MailProperties mailProperties;
    @Mock private MailService mailService;

    private final EmailTemplateRenderer templateRenderer = new EmailTemplateRenderer();
    private ReportDeliveryScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new ReportDeliveryScheduler(
                scheduleMapper, placementRegistry, tenantWorkScope, scheduleService,
                automationExecutor, reportService, workspaceService, auditService,
                templateRenderer, mailProperties, mailService, CLOCK);
        ReflectionTestUtils.setField(scheduler, "schedulingEnabled", true);
    }

    @Test
    void dueDeliveryCreatesSnapshotBeforeRecipientRederivationAndSend() {
        stubAuditScope();
        ReportSnapshotDto snapshot = snapshotWithContent();
        ReportSchedule schedule = stubClaimedDelivery(List.of(user()));
        when(reportService.createDeliverySnapshot(REPORT_ID, SCHEDULE_ID)).thenReturn(snapshot);
        when(scheduleService.activeRecipientsForDocument(schedule, snapshot.computedResult()))
                .thenReturn(List.of(user()));
        when(mailProperties.getAppBaseUrl()).thenReturn("https://app.example.com");
        when(workspaceService.getOrgId(WORKSPACE_ID)).thenReturn(77);

        scheduler.deliverDue();

        ArgumentCaptor<MailMessage> message = ArgumentCaptor.forClass(MailMessage.class);
        InOrder order = inOrder(reportService, scheduleService, mailService);
        order.verify(reportService).createDeliverySnapshot(REPORT_ID, SCHEDULE_ID);
        order.verify(scheduleService).activeRecipientsForDocument(schedule, snapshot.computedResult());
        order.verify(mailService).sendForWorkspace(eq(WORKSPACE_ID), message.capture());
        assertEquals("recipient@example.com", message.getValue().to());
        assertEquals("Scheduled report: Quota-safe report", message.getValue().subject());
        String body = message.getValue().htmlBody();
        assertTrue(body.contains("Quota-safe report"));
        assertTrue(body.contains("Jul 7, 2026 - Jul 13, 2026"));
        assertTrue(body.contains("Revenue held above target."));
        assertTrue(body.contains(">Won revenue</p>"));
        assertTrue(body.contains(">$125,000</p>"));
        assertTrue(body.contains("href=\"https://app.example.com/overview/reports/33/snapshots/55\""));
        assertTrue(body.contains(">View report</a>"));
        assertFalse(body.contains("{{"));
        verify(auditService).recordScoped(
                "report.schedule.delivery", "report_schedule", SCHEDULE_ID,
                WORKSPACE_ID, 77, "Quota-safe report",
                "Queued scheduled report delivery", Map.of("recipientCount", 1, "snapshotId", SNAPSHOT_ID));
    }

    @Test
    void dueDeliveryRendersFallbackWithoutNarrativeOrTotals() {
        stubAuditScope();
        ReportSnapshotDto snapshot = snapshot();
        ReportSchedule schedule = stubClaimedDelivery(List.of(user()));
        when(reportService.createDeliverySnapshot(REPORT_ID, SCHEDULE_ID)).thenReturn(snapshot);
        when(scheduleService.activeRecipientsForDocument(schedule, snapshot.computedResult()))
                .thenReturn(List.of(user()));
        when(mailProperties.getAppBaseUrl()).thenReturn("https://app.example.com");
        when(workspaceService.getOrgId(WORKSPACE_ID)).thenReturn(77);

        scheduler.deliverDue();

        ArgumentCaptor<MailMessage> message = ArgumentCaptor.forClass(MailMessage.class);
        verify(mailService).sendForWorkspace(eq(WORKSPACE_ID), message.capture());
        String body = message.getValue().htmlBody();
        assertTrue(body.contains("Your scheduled report is ready to review in Connex."));
        assertTrue(body.contains(">Report</p>"));
        assertTrue(body.contains(">Ready</p>"));
    }

    @Test
    void dueDeliveryRendersJapaneseFallbackForJapaneseRecipient() {
        stubAuditScope();
        ReportSnapshotDto snapshot = snapshot();
        User recipient = user(USER_ID, "recipient@example.com", "ja");
        ReportSchedule schedule = stubClaimedDelivery(List.of(recipient));
        when(reportService.createDeliverySnapshot(REPORT_ID, SCHEDULE_ID)).thenReturn(snapshot);
        when(scheduleService.activeRecipientsForDocument(schedule, snapshot.computedResult()))
                .thenReturn(List.of(recipient));
        when(mailProperties.getAppBaseUrl()).thenReturn("https://app.example.com");
        when(workspaceService.getOrgId(WORKSPACE_ID)).thenReturn(77);

        scheduler.deliverDue();

        ArgumentCaptor<MailMessage> message = ArgumentCaptor.forClass(MailMessage.class);
        verify(mailService).sendForWorkspace(eq(WORKSPACE_ID), message.capture());
        assertEquals("定期レポート: Quota-safe report", message.getValue().subject());
        String body = message.getValue().htmlBody();
        assertTrue(body.contains("<html lang=\"ja\">"));
        assertTrue(body.contains("2026/07/07 - 2026/07/13"));
        assertTrue(body.contains("定期レポートを Connex で確認できます。"));
        assertTrue(body.contains(">レポート</p>"));
        assertTrue(body.contains(">準備完了</p>"));
        assertTrue(body.contains(">Connex で表示</a>"));
        assertTrue(body.contains("このメールは、ワークスペースのレポート配信先に指定されているため送信されました。"));
        assertFalse(body.contains("{{"));
    }

    @Test
    void dueDeliveryLocalizesMixedRecipientsFromOneGeneratedDocument() {
        stubAuditScope();
        ReportSnapshotDto snapshot = snapshotWithContent();
        User english = user(45, "english@example.com", "en");
        User japanese = user(46, "japanese@example.com", "ja");
        ReportSchedule schedule = stubClaimedDelivery(List.of(english, japanese));
        when(reportService.createDeliverySnapshot(REPORT_ID, SCHEDULE_ID)).thenReturn(snapshot);
        when(scheduleService.activeRecipientsForDocument(schedule, snapshot.computedResult()))
                .thenReturn(List.of(english, japanese));
        when(mailProperties.getAppBaseUrl()).thenReturn("https://app.example.com");
        when(workspaceService.getOrgId(WORKSPACE_ID)).thenReturn(77);

        scheduler.deliverDue();

        ArgumentCaptor<MailMessage> messages = ArgumentCaptor.forClass(MailMessage.class);
        verify(mailService, times(2)).sendForWorkspace(eq(WORKSPACE_ID), messages.capture());
        Map<String, MailMessage> byRecipient = messages.getAllValues().stream()
                .collect(java.util.stream.Collectors.toMap(MailMessage::to, message -> message));
        MailMessage englishMessage = byRecipient.get("english@example.com");
        MailMessage japaneseMessage = byRecipient.get("japanese@example.com");
        assertEquals("Scheduled report: Quota-safe report", englishMessage.subject());
        assertEquals("定期レポート: Quota-safe report", japaneseMessage.subject());
        assertTrue(englishMessage.htmlBody().contains("Jul 7, 2026 - Jul 13, 2026"));
        assertTrue(japaneseMessage.htmlBody().contains("2026/07/07 - 2026/07/13"));
        assertTrue(englishMessage.htmlBody().contains("$125,000"));
        assertTrue(japaneseMessage.htmlBody().contains("$125,000"));
        assertTrue(englishMessage.htmlBody().contains("Revenue held above target."));
        assertTrue(japaneseMessage.htmlBody().contains("Revenue held above target."));
        verify(reportService).createDeliverySnapshot(REPORT_ID, SCHEDULE_ID);
        verify(auditService).recordScoped(
                "report.schedule.delivery", "report_schedule", SCHEDULE_ID,
                WORKSPACE_ID, 77, "Quota-safe report",
                "Queued scheduled report delivery", Map.of("recipientCount", 2, "snapshotId", SNAPSHOT_ID));
    }

    @Test
    void dueDeliveryFallsBackToEnglishForUnsupportedStoredLocale() {
        stubAuditScope();
        ReportSnapshotDto snapshot = snapshot();
        User recipient = user(USER_ID, "recipient@example.com", "../../ja");
        ReportSchedule schedule = stubClaimedDelivery(List.of(recipient));
        when(reportService.createDeliverySnapshot(REPORT_ID, SCHEDULE_ID)).thenReturn(snapshot);
        when(scheduleService.activeRecipientsForDocument(schedule, snapshot.computedResult()))
                .thenReturn(List.of(recipient));
        when(mailProperties.getAppBaseUrl()).thenReturn("https://app.example.com");
        when(workspaceService.getOrgId(WORKSPACE_ID)).thenReturn(77);

        scheduler.deliverDue();

        ArgumentCaptor<MailMessage> message = ArgumentCaptor.forClass(MailMessage.class);
        verify(mailService).sendForWorkspace(eq(WORKSPACE_ID), message.capture());
        assertEquals("Scheduled report: Quota-safe report", message.getValue().subject());
        assertTrue(message.getValue().htmlBody().contains("<html lang=\"en\">"));
    }

    @Test
    void dueDeliveryWithoutEligibleRecipientsRecordsFailureBeforeGeneration() {
        stubAuditScope();
        ReportSchedule schedule = stubClaimedDelivery(List.of());
        when(scheduleService.isCurrentDeliverySchedule(schedule)).thenReturn(true);
        when(workspaceService.getOrgId(WORKSPACE_ID)).thenReturn(77);

        scheduler.deliverDue();

        verify(reportService, never()).createDeliverySnapshot(anyInt(), anyInt());
        verify(mailService, never()).sendForWorkspace(anyInt(), any());
        verify(auditService).recordFailureScoped(
                "report.schedule.delivery", "report_schedule", SCHEDULE_ID,
                WORKSPACE_ID, 77, null,
                "Scheduled report delivery failed", "no eligible report recipients");
    }

    @Test
    void dueDeliveryAuthorizesRecipientsAgainstGeneratedDocument() {
        stubAuditScope();
        ReportSnapshotDto snapshot = attainmentSnapshot();
        ReportSchedule schedule = stubClaimedDelivery(List.of(user()));
        when(reportService.createDeliverySnapshot(REPORT_ID, SCHEDULE_ID)).thenReturn(snapshot);
        when(scheduleService.activeRecipientsForDocument(schedule, snapshot.computedResult())).thenReturn(List.of());
        when(scheduleService.isCurrentDeliverySchedule(schedule)).thenReturn(true);
        when(workspaceService.getOrgId(WORKSPACE_ID)).thenReturn(77);

        scheduler.deliverDue();

        verify(reportService).createDeliverySnapshot(REPORT_ID, SCHEDULE_ID);
        verify(scheduleService).activeRecipientsForDocument(schedule, snapshot.computedResult());
        verify(mailService, never()).sendForWorkspace(anyInt(), any());
        verify(auditService).recordFailureScoped(
                "report.schedule.delivery", "report_schedule", SCHEDULE_ID,
                WORKSPACE_ID, 77, null,
                "Scheduled report delivery failed", "no eligible report recipients");
    }

    @Test
    void dueDeliveryCancellationAfterSnapshotDoesNotRecordRecipientFailure() {
        ReportSnapshotDto snapshot = snapshot();
        ReportSchedule schedule = stubClaimedDelivery(List.of(user()));
        when(reportService.createDeliverySnapshot(REPORT_ID, SCHEDULE_ID)).thenReturn(snapshot);
        when(scheduleService.activeRecipientsForDocument(schedule, snapshot.computedResult())).thenReturn(List.of());
        when(scheduleService.isCurrentDeliverySchedule(schedule)).thenReturn(false);

        scheduler.deliverDue();

        verify(mailService, never()).sendForWorkspace(anyInt(), any());
        verifyNoInteractions(auditService);
    }

    @Test
    void snapshotPersistenceFailureAuditsAndSendsNoMail() {
        stubAuditScope();
        stubClaimedDelivery(List.of(user()));
        doThrow(new BadRequestException("The workspace report snapshot quota has been reached"))
                .when(reportService).createDeliverySnapshot(REPORT_ID, SCHEDULE_ID);
        when(workspaceService.getOrgId(WORKSPACE_ID)).thenReturn(77);

        scheduler.deliverDue();

        verify(auditService).recordFailureScoped(
                "report.schedule.delivery", "report_schedule", SCHEDULE_ID,
                WORKSPACE_ID, 77, null,
                "Scheduled report delivery failed", "report snapshot persistence failed");
        verify(scheduleService, never()).activeRecipientsForDocument(any(), any());
        verifyNoInteractions(mailService);
    }

    private ReportSchedule stubClaimedDelivery(List<User> beforeGeneration) {
        LocalDateTime now = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);
        ReportScheduleRef ref = new ReportScheduleRef(WORKSPACE_ID, SCHEDULE_ID);
        User runAs = user();
        ReportSchedule schedule = schedule();

        when(placementRegistry.activeCatalogs()).thenReturn(Collections.singletonList(null));
        when(tenantWorkScope.withCatalog(
                isNull(), ArgumentMatchers.<Supplier<List<ReportScheduleRef>>>any()))
                .thenAnswer(invocation -> {
                    Supplier<List<ReportScheduleRef>> work = invocation.getArgument(1);
                    return work.get();
                });
        when(scheduleMapper.dueScheduleRefs(now)).thenReturn(List.of(ref));
        doAnswer(invocation -> {
            Runnable work = invocation.getArgument(1);
            work.run();
            return null;
        }).when(tenantWorkScope).inWorkspace(eq(WORKSPACE_ID), any(Runnable.class));
        when(scheduleService.loadForDelivery(WORKSPACE_ID, SCHEDULE_ID)).thenReturn(schedule);
        when(scheduleService.deliveryAccess(schedule))
                .thenReturn(ScheduleService.DeliveryAccess.allowed(runAs, "owner"));
        doAnswer(invocation -> {
            Supplier<?> work = invocation.getArgument(3);
            return work.get();
        }).when(automationExecutor).runAs(eq(WORKSPACE_ID), same(runAs), eq("owner"), any());
        when(scheduleService.claimDue(SCHEDULE_ID, USER_ID, now)).thenReturn(schedule);
        when(scheduleService.activeReportReaders(schedule)).thenReturn(beforeGeneration);
        return schedule;
    }

    private void stubAuditScope() {
        when(tenantWorkScope.unrouted(ArgumentMatchers.<Supplier<Object>>any()))
                .thenAnswer(invocation -> {
                    Supplier<?> work = invocation.getArgument(0);
                    return work.get();
                });
    }

    private static User user() {
        return user(USER_ID, "recipient@example.com", "en");
    }

    private static User user(int id, String email, String locale) {
        User user = new User();
        user.setId(id);
        user.setDisplayName("Recipient");
        user.setEmail(email);
        user.setLocale(locale);
        return user;
    }

    private static ReportSchedule schedule() {
        ReportSchedule schedule = new ReportSchedule();
        schedule.setId(SCHEDULE_ID);
        schedule.setWorkspaceId(WORKSPACE_ID);
        schedule.setReportDefinitionId(REPORT_ID);
        schedule.setRunAsUserId(USER_ID);
        return schedule;
    }

    private static ReportDocumentDto document() {
        ReportWidgetConfig configuredWidget = new ReportWidgetConfig(
                "headline", "Won revenue", "deals", "won_revenue", "none", "kpi");
        ReportConfig config = new ReportConfig(
                List.of(configuredWidget),
                new ReportFilters(null, null, null, null, null),
                null,
                "day",
                List.of(new ReportLayoutItem("headline", 0, 0, 6, 4)));
        ReportDefinitionDto definition = new ReportDefinitionDto(
                REPORT_ID, "Quota-safe report", "Delivery test", "weekly", null,
                config, USER_ID, "2026-07-01 00:00:00", "2026-07-01 00:00:00");
        return new ReportDocumentDto(
                definition,
                LocalDate.of(2026, 7, 7),
                LocalDate.of(2026, 7, 13),
                LocalDate.of(2026, 6, 30),
                LocalDate.of(2026, 7, 6),
                null,
                List.of(),
                List.of(),
                List.of(),
                "2026-07-14T09:00:00Z");
    }

    private static ReportDocumentDto documentWithContent() {
        ReportDocumentDto base = document();
        ReportNarrativeClaimDto claim = new ReportNarrativeClaimDto(
                "Revenue held above target.", List.of("widget:headline"));
        ReportNarrativeDto narrative = new ReportNarrativeDto(
                true,
                List.of(new ReportNarrativeSectionDto("Summary", List.of(claim))),
                List.of(),
                null,
                "2026-07-14T09:00:00Z",
                0);
        ReportWidgetDataDto widget = new ReportWidgetDataDto(
                "headline", "Won revenue", "kpi", "deals", "won_revenue", "none", "USD",
                new BigDecimal("125000"), null, null, List.of());
        return new ReportDocumentDto(
                base.definition(),
                base.periodStart(),
                base.periodEnd(),
                base.priorPeriodStart(),
                base.priorPeriodEnd(),
                narrative,
                List.of(widget),
                base.appendix(),
                base.citations(),
                base.generatedAt());
    }

    private static ReportDocumentDto attainmentDocument() {
        ReportDocumentDto base = document();
        ReportWidgetConfig configuredWidget = new ReportWidgetConfig(
                "headline", "Goal attainment", "deals", "attainment", "none", "kpi");
        ReportConfig config = new ReportConfig(
                List.of(configuredWidget),
                new ReportFilters(null, null, null, null, null),
                null,
                "day",
                List.of(new ReportLayoutItem("headline", 0, 0, 6, 4)));
        ReportDefinitionDto definition = new ReportDefinitionDto(
                REPORT_ID, "Quota-safe report", "Delivery test", "monthly", null,
                config, USER_ID, "2026-07-01 00:00:00", "2026-07-01 00:00:00");
        return new ReportDocumentDto(
                definition,
                base.periodStart(),
                base.periodEnd(),
                base.priorPeriodStart(),
                base.priorPeriodEnd(),
                base.narrative(),
                List.of(),
                base.appendix(),
                base.citations(),
                base.generatedAt());
    }

    private static ReportSnapshotDto snapshot() {
        return snapshot(document());
    }

    private static ReportSnapshotDto snapshotWithContent() {
        return snapshot(documentWithContent());
    }

    private static ReportSnapshotDto attainmentSnapshot() {
        return snapshot(attainmentDocument());
    }

    private static ReportSnapshotDto snapshot(ReportDocumentDto document) {
        return new ReportSnapshotDto(
                SNAPSHOT_ID,
                REPORT_ID,
                document.periodStart(),
                document.periodEnd(),
                document,
                "scheduled",
                USER_ID,
                "2026-07-14 09:00:00");
    }
}
