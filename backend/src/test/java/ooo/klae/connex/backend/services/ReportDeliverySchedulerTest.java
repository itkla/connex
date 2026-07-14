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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
import ooo.klae.connex.backend.dto.ReportWidgetConfig;
import ooo.klae.connex.backend.dto.ReportWidgetDataDto;
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
        when(tenantWorkScope.unrouted(ArgumentMatchers.<Supplier<Object>>any()))
                .thenAnswer(invocation -> {
                    Supplier<?> work = invocation.getArgument(0);
                    return work.get();
                });
    }

    @Test
    void dueDeliveryGeneratesRenderedEmailWithoutCreatingSnapshot() {
        stubClaimedDelivery(List.of(user()), List.of(user()));
        when(reportService.generate(REPORT_ID, null)).thenReturn(documentWithContent());
        when(mailProperties.getAppBaseUrl()).thenReturn("https://app.example.com");

        scheduler.deliverDue();

        verify(reportService).generate(REPORT_ID, null);
        verify(reportService, never()).createSnapshot(anyInt(), any());
        ArgumentCaptor<MailMessage> message = ArgumentCaptor.forClass(MailMessage.class);
        verify(mailService).sendForWorkspace(eq(WORKSPACE_ID), message.capture());
        assertEquals("recipient@example.com", message.getValue().to());
        assertEquals("Scheduled report: Quota-safe report", message.getValue().subject());
        String body = message.getValue().htmlBody();
        assertTrue(body.contains("Quota-safe report"));
        assertTrue(body.contains("Jul 7, 2026 - Jul 13, 2026"));
        assertTrue(body.contains("Revenue held above target."));
        assertTrue(body.contains(">Won revenue</p>"));
        assertTrue(body.contains(">$125,000</p>"));
        assertTrue(body.contains("href=\"https://app.example.com/overview/reports/33\""));
        assertTrue(body.contains(">View report</a>"));
        assertFalse(body.contains("{{"));
        verify(auditService).recordScoped(
                "report.schedule.delivery", "report_schedule", SCHEDULE_ID,
                WORKSPACE_ID, 77, "Quota-safe report",
                "Queued scheduled report delivery", Map.of("recipientCount", 1));
    }

    @Test
    void dueDeliveryRendersFallbackWithoutNarrativeOrTotals() {
        stubClaimedDelivery(List.of(user()), List.of(user()));
        when(reportService.generate(REPORT_ID, null)).thenReturn(document());
        when(mailProperties.getAppBaseUrl()).thenReturn("https://app.example.com");

        scheduler.deliverDue();

        ArgumentCaptor<MailMessage> message = ArgumentCaptor.forClass(MailMessage.class);
        verify(mailService).sendForWorkspace(eq(WORKSPACE_ID), message.capture());
        String body = message.getValue().htmlBody();
        assertTrue(body.contains("Your scheduled report is ready to review in Connex."));
        assertTrue(body.contains(">Report</p>"));
        assertTrue(body.contains(">Ready</p>"));
    }

    @Test
    void dueDeliveryWithoutEligibleRecipientsRecordsFailureBeforeGeneration() {
        stubClaimedDelivery(List.of(), List.of());

        scheduler.deliverDue();

        verify(reportService, never()).generate(anyInt(), any());
        verify(reportService, never()).createSnapshot(anyInt(), any());
        verify(mailService, never()).sendForWorkspace(anyInt(), any());
        verify(auditService).recordFailureScoped(
                "report.schedule.delivery", "report_schedule", SCHEDULE_ID,
                WORKSPACE_ID, 77, null,
                "Scheduled report delivery failed", "no eligible report recipients");
    }

    @Test
    void dueDeliveryRevalidatesRecipientsAfterGeneration() {
        stubClaimedDelivery(List.of(user()), List.of());
        when(reportService.generate(REPORT_ID, null)).thenReturn(document());

        scheduler.deliverDue();

        verify(reportService).generate(REPORT_ID, null);
        verify(mailService, never()).sendForWorkspace(anyInt(), any());
        verify(auditService).recordFailureScoped(
                "report.schedule.delivery", "report_schedule", SCHEDULE_ID,
                WORKSPACE_ID, 77, null,
                "Scheduled report delivery failed", "no eligible report recipients");
    }

    private void stubClaimedDelivery(List<User> beforeGeneration, List<User> afterGeneration) {
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
        when(scheduleService.activeRecipients(schedule)).thenReturn(beforeGeneration).thenReturn(afterGeneration);
        when(workspaceService.getOrgId(WORKSPACE_ID)).thenReturn(77);
    }

    private static User user() {
        User user = new User();
        user.setId(USER_ID);
        user.setDisplayName("Recipient");
        user.setEmail("recipient@example.com");
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
}
