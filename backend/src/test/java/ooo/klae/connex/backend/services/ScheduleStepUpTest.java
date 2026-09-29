package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.config.PrivilegedMfaProperties;
import ooo.klae.connex.backend.config.SessionSecurityProperties;
import ooo.klae.connex.backend.dto.ReportScheduleRequest;
import ooo.klae.connex.backend.exceptions.RecentAuthenticationRequiredException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.ReportMapper;
import ooo.klae.connex.backend.mappers.ScheduleMapper;
import ooo.klae.connex.backend.mappers.SpringSessionMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.tenant.TenantWorkScope;
import tools.jackson.databind.ObjectMapper;

/**
 * Pins the privileged step-up gate on report delivery schedule mutation (#1763).
 *
 * <p>A schedule is a standing export channel that later runs with no session to step up, so the
 * control belongs on the mutation. The gate is independent of {@code privileged-mfa.enforced},
 * matching the other high-risk service boundaries, so every case runs under both values. The gate
 * must also run before the mutation reads or locks anything, because its refusal audit commits
 * independently and re-takes the actor's {@code app_user} row {@code FOR SHARE}.
 */
class ScheduleStepUpTest {
    private static final int USER_ID = 7;
    private static final int REPORT_ID = 31;
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-28T09:00:00Z"), ZoneOffset.UTC);

    private final ScheduleMapper scheduleMapper = mock(ScheduleMapper.class);
    private final ReportMapper reportMapper = mock(ReportMapper.class);
    private final WorkspaceService workspaceService = mock(WorkspaceService.class);
    private final AuthService authService = mock(AuthService.class);
    private final AuditService auditService = mock(AuditService.class);
    private final ReportPermissionPolicy reportPermissionPolicy = mock(ReportPermissionPolicy.class);
    private final PrivilegedAccountService privilegedAccountService = mock(PrivilegedAccountService.class);
    private final MockHttpServletRequest request = new MockHttpServletRequest();

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    @ParameterizedTest
    @CsvSource({"true,create", "true,update", "false,create", "false,update"})
    void aPrivilegedAccountWithoutAFreshStepUpCannotOpenOrRedirectADeliverySchedule(
            String enforced, String operation) {
        ScheduleService service = service(enforced);
        when(privilegedAccountService.isPrivileged(USER_ID)).thenReturn(true);

        assertThrows(RecentAuthenticationRequiredException.class, () -> invoke(service, operation));

        verify(auditService).recordExportStepUpRefused();
        verifyNoInteractions(scheduleMapper, reportMapper, workspaceService, reportPermissionPolicy);
    }

    @ParameterizedTest
    @CsvSource({"true,create", "true,update", "false,create", "false,update"})
    void anExpiredStepUpDoesNotSatisfyTheGate(String enforced, String operation) {
        ScheduleService service = service(enforced);
        when(privilegedAccountService.isPrivileged(USER_ID)).thenReturn(true);
        request.getSession().setAttribute(SessionSecurityService.WEBAUTHN_STEP_UP_AT_ATTR,
                CLOCK.millis() - Duration.ofMinutes(11).toMillis());
        request.getSession().setAttribute(SessionSecurityService.WEBAUTHN_STEP_UP_USER_ATTR, USER_ID);

        assertThrows(RecentAuthenticationRequiredException.class, () -> invoke(service, operation));

        verify(auditService).recordExportStepUpRefused();
        verifyNoInteractions(scheduleMapper, reportMapper, workspaceService, reportPermissionPolicy);
    }

    @ParameterizedTest
    @CsvSource({"true,create", "true,update", "false,create", "false,update"})
    void anotherAccountsStepUpDoesNotSatisfyTheGate(String enforced, String operation) {
        ScheduleService service = service(enforced);
        when(privilegedAccountService.isPrivileged(USER_ID)).thenReturn(true);
        request.getSession().setAttribute(
                SessionSecurityService.WEBAUTHN_STEP_UP_AT_ATTR, CLOCK.millis());
        request.getSession().setAttribute(
                SessionSecurityService.WEBAUTHN_STEP_UP_USER_ATTR, USER_ID + 1);

        assertThrows(RecentAuthenticationRequiredException.class, () -> invoke(service, operation));

        verify(auditService).recordExportStepUpRefused();
        verifyNoInteractions(scheduleMapper, reportMapper, workspaceService, reportPermissionPolicy);
    }

    @ParameterizedTest
    @CsvSource({"true,create", "true,update", "false,create", "false,update"})
    void aPrivilegedAccountWithAFreshStepUpProceedsToTheMutation(String enforced, String operation) {
        ScheduleService service = service(enforced);
        when(privilegedAccountService.isPrivileged(USER_ID)).thenReturn(true);
        request.getSession().setAttribute(
                SessionSecurityService.WEBAUTHN_STEP_UP_AT_ATTR, CLOCK.millis());
        request.getSession().setAttribute(
                SessionSecurityService.WEBAUTHN_STEP_UP_USER_ATTR, USER_ID);

        assertThrows(ResourceNotFoundException.class, () -> invoke(service, operation));

        verify(reportMapper).getDefinition(anyInt(), anyInt());
        verify(auditService, never()).recordExportStepUpRefused();
    }

    @ParameterizedTest
    @CsvSource({"true,create", "true,update", "false,create", "false,update"})
    void anUnprivilegedAccountNeedsNoStepUpToScheduleAReport(String enforced, String operation) {
        ScheduleService service = service(enforced);
        when(privilegedAccountService.isPrivileged(USER_ID)).thenReturn(false);

        assertThrows(ResourceNotFoundException.class, () -> invoke(service, operation));

        verify(reportMapper).getDefinition(anyInt(), anyInt());
        verify(auditService, never()).recordExportStepUpRefused();
    }

    /**
     * Deletion closes a delivery channel instead of opening one, so it stays ungated by decision
     * rather than by omission. This fails if a future change gates it without revisiting that.
     */
    @ParameterizedTest
    @CsvSource({"true", "false"})
    void deletingAScheduleStaysUngated(String enforced) {
        ScheduleService service = service(enforced);
        when(privilegedAccountService.isPrivileged(USER_ID)).thenReturn(true);

        assertThrows(ResourceNotFoundException.class, () -> service.delete(REPORT_ID));

        verify(reportMapper).getDefinition(anyInt(), anyInt());
        verify(auditService, never()).recordExportStepUpRefused();
    }

    private static void invoke(ScheduleService service, String operation) {
        ReportScheduleRequest payload =
                new ReportScheduleRequest("weekly", List.of(USER_ID), "UTC", 9, true);
        if ("create".equals(operation)) {
            service.create(REPORT_ID, payload);
        } else {
            service.update(REPORT_ID, payload);
        }
    }

    private ScheduleService service(String enforced) {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        User user = new User();
        user.setId(USER_ID);
        user.setDisplayName("Scheduling Admin");
        when(authService.getCurrentUser()).thenReturn(user);
        return new ScheduleService(
                scheduleMapper,
                reportMapper,
                workspaceService,
                authService,
                auditService,
                mock(TenantWorkScope.class),
                reportPermissionPolicy,
                privilegedAccountService,
                new SessionSecurityService(sessionProperties(), properties(enforced), CLOCK,
                        mock(UserMapper.class), mock(SpringSessionMapper.class)),
                new ObjectMapper(),
                CLOCK);
    }

    private static PrivilegedMfaProperties properties(String enforced) {
        PrivilegedMfaProperties properties = new PrivilegedMfaProperties();
        properties.setEnforced(enforced);
        properties.setChangeActor("security-change-1763");
        return properties;
    }

    private static SessionSecurityProperties sessionProperties() {
        SessionSecurityProperties properties = new SessionSecurityProperties();
        properties.setRecentAuthenticationWindow(Duration.ofMinutes(10));
        return properties;
    }
}
