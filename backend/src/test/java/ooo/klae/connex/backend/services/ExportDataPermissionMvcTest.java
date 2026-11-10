package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.OutputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Answers;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.authorization.method.AuthorizationManagerBeforeMethodInterceptor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.controllers.AuditController;
import ooo.klae.connex.backend.controllers.ExportController;
import ooo.klae.connex.backend.controllers.OrgAuditController;
import ooo.klae.connex.backend.controllers.ReportController;
import ooo.klae.connex.backend.controllers.ScheduleController;
import ooo.klae.connex.backend.controllers.SupportBundleController;
import ooo.klae.connex.backend.controllers.TenantLifecycleController;
import ooo.klae.connex.backend.dto.WorkspaceLifecycleRef;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.GlobalExceptionHandler;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.observability.ErrorReporter;
import ooo.klae.connex.backend.observability.ClientAssertedCorrelationPseudonymizer;
import ooo.klae.connex.backend.services.TenantExportService.TenantExportDownload;
import ooo.klae.connex.backend.services.TenantLifecycleControlOperations.AcquiredWorkspace;
import ooo.klae.connex.backend.services.TenantLifecycleControlOperations.OperationLease;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;
import ooo.klae.connex.backend.tenant.RequirePermissionAuthorizationManager;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.TenantExportGrantCookie;
import ooo.klae.connex.backend.tenant.TenantWorkScope;
import tools.jackson.databind.ObjectMapper;

/** Exercises each download boundary with the real permission advice and ZIP-grant authorization. */
class ExportDataPermissionMvcTest {
    private static final String CSV = "id\r\n1\r\n";
    private static final String TOKEN = "a".repeat(64);
    private final EnumSet<Permission> permissions = Permission.grantableSet();
    private boolean orgExportAllowed;
    private WorkspaceService workspaceService;
    private ExportService exportService;
    private ReportService reportService;
    private ScheduleService scheduleService;
    private SupportBundleReadinessService bundleReadiness;
    private AuditService auditService;
    private OrgMemberService orgMemberService;
    private TenantLifecycleControlOperations controlOperations;
    private SessionSecurityService sessionSecurityService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws IOException {
        orgExportAllowed = true;
        workspaceService = mock(WorkspaceService.class);
        exportService = csvService(ExportService.class);
        reportService = csvService(ReportService.class);
        scheduleService = mock(ScheduleService.class);
        auditService = csvService(AuditService.class);
        orgMemberService = mock(OrgMemberService.class);
        controlOperations = mock(TenantLifecycleControlOperations.class);
        sessionSecurityService = mock(SessionSecurityService.class);
        doAnswer(invocation -> {
            Permission permission = invocation.getArgument(0);
            if (!permissions.contains(permission)) {
                throw new ForbiddenException("Requires " + permission);
            }
            return null;
        }).when(workspaceService).requirePermission(any(Permission.class));
        doAnswer(invocation -> {
            if (!orgExportAllowed) {
                throw new ForbiddenException("Requires EXPORT_DATA");
            }
            return null;
        }).when(orgMemberService).requireExportData(3, 7);
        when(workspaceService.getCurrentUserId()).thenReturn(7);
        AuthService authService = mock(AuthService.class);
        User user = new User();
        user.setId(7);
        when(authService.getCurrentUser()).thenReturn(user);
        TenantContext tenantContext = new TenantContext();
        tenantContext.set(5, 3, 7, "admin", null);
        when(workspaceService.getOrgId(5)).thenReturn(3);
        bundleReadiness = mock(SupportBundleReadinessService.class);
        when(bundleReadiness.readiness(3)).thenReturn(Map.of());
        SupportBundleConfigService bundleConfig = mock(SupportBundleConfigService.class);
        when(bundleConfig.safeConfiguration())
            .thenReturn(new SupportBundleConfigService.SafeConfiguration(Map.of(), Map.of()));
        ProductVersionService version = mock(ProductVersionService.class);
        when(version.version()).thenReturn("test");
        ClientErrorService clientErrors = mock(ClientErrorService.class);
        when(clientErrors.supportSliceForOrg(eq(3), any(), any(), any(), anyInt()))
            .thenReturn(new ClientErrorService.ClientErrorSlice(List.of(), 0, false));
        when(auditService.supportSliceForOrg(eq(3), any(), any(), any(), anyInt()))
            .thenReturn(new AuditService.AuditSlice(CSV, 1, false));
        when(auditService.supportSliceForEntity(eq(5), eq(3), eq("person"), eq(1), any(), any(), any(), anyInt()))
            .thenReturn(new AuditService.AuditSlice(CSV, 1, false));
        SupportBundleService supportBundles = new SupportBundleService(
            orgMemberService, sessionSecurityService, bundleReadiness, bundleConfig,
            mock(MigrationHistoryService.class), version, auditService, clientErrors,
            mock(ClientAssertedCorrelationPseudonymizer.class), new ObjectMapper(),
            Clock.fixed(Instant.parse("2026-11-01T00:00:00Z"), ZoneOffset.UTC));
        TenantWorkScope scope = mock(TenantWorkScope.class);
        doAnswer(invocation -> invocation.<Supplier<?>>getArgument(0).get())
            .when(scope).unrouted(any());
        TenantExportService tenantExporter = mock(TenantExportService.class);
        TenantExportDownload download = mock(TenantExportDownload.class);
        when(download.filename()).thenReturn("workspace.zip");
        when(download.remainingTimeoutMillis()).thenReturn(1_000L);
        doAnswer(invocation -> {
            invocation.<OutputStream>getArgument(0).write(new byte[] {0x50, 0x4b});
            return null;
        }).when(download).writeTo(any(OutputStream.class));
        AcquiredWorkspace acquired = new AcquiredWorkspace(
            new WorkspaceLifecycleRef(5, 3, "Workspace", "workspace", "active"),
            new OperationLease(3, 5, "export", "lease"));
        when(controlOperations.redeemExportGrant(eq(3), eq(5), eq(7), any(), any(), any()))
            .thenReturn(acquired);
        when(tenantExporter.prepareAcquired(3, 7, acquired)).thenReturn(download);
        TenantExportGrantService grants = new TenantExportGrantService(
            orgMemberService, sessionSecurityService, scope, controlOperations, tenantExporter,
            Clock.fixed(Instant.parse("2026-11-01T00:00:00Z"), ZoneOffset.UTC));
        ExportController crm = new ExportController(
            exportService, mock(MemberScopeResolver.class), mock(WarmthFilterResolver.class),
            workspaceService, new CurrencyFilterService(mock(DealMapper.class), workspaceService));
        ReportController reports = new ReportController(
            permissionProxy(ReportService.class, reportService), mock(ReportComposerService.class));
        mvc = MockMvcBuilders.standaloneSetup(
                permissionProxy(ExportController.class, crm),
                permissionProxy(ReportController.class, reports),
                permissionProxy(ScheduleController.class, new ScheduleController(
                    permissionProxy(ScheduleService.class, scheduleService))),
                new SupportBundleController(supportBundles, authService, workspaceService, tenantContext),
                new AuditController(auditService, workspaceService),
                new OrgAuditController(auditService, orgMemberService, authService),
                new TenantLifecycleController(grants, mock(TenantExportGrantCookie.class), authService))
            .setControllerAdvice(new GlobalExceptionHandler(mock(ErrorReporter.class), new TenantContext()))
            .build();
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("member", "unused"));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest(name = "{0} export permission={1}")
    @MethodSource("matrix")
    void everyExportRequiresItsScopedPermission(Endpoint endpoint, boolean allowed) throws Exception {
        if (!allowed) {
            permissions.remove(Permission.EXPORT_DATA);
            orgExportAllowed = false;
        }
        var result = mvc.perform(request(endpoint))
            .andExpect(status().is(allowed ? endpoint.successStatus() : 403)).andReturn();
        if (allowed && result.getRequest().isAsyncStarted()) {
            result.getAsyncResult(5_000L);
            mvc.perform(asyncDispatch(result)).andExpect(status().isOk());
        }
        if (!allowed) {
            verifyNoInteractions(exportService, reportService, scheduleService, auditService, controlOperations, bundleReadiness);
        }
        if (allowed && endpoint.path().contains("/workspaces/5/export")) {
            verify(orgMemberService).requireExportData(3, 7);
            if (endpoint.post()) {
                verify(sessionSecurityService).requireRecentAuthentication(7);
            } else {
                verifyNoInteractions(sessionSecurityService);
                assertEquals("application/zip", result.getResponse().getContentType());
            }
        }
    }

    @ParameterizedTest
    @MethodSource("reportEndpoints")
    void reportReadRemainsRequiredWhenExportIsGranted(Endpoint endpoint) throws Exception {
        permissions.remove(Permission.REPORT_READ);
        mvc.perform(request(endpoint)).andExpect(status().isForbidden());
        verifyNoInteractions(reportService);
    }

    @Test
    void auditReadRemainsRequiredWhenExportIsGranted() throws Exception {
        permissions.remove(Permission.AUDIT_READ);
        mvc.perform(get("/api/audit/export")).andExpect(status().isForbidden());
        verifyNoInteractions(auditService);
    }

    @ParameterizedTest
    @MethodSource("scheduleEndpoints")
    void scheduleUpdateRemainsRequiredWhenExportIsGranted(Endpoint endpoint) throws Exception {
        permissions.remove(Permission.REPORT_UPDATE);
        mvc.perform(request(endpoint)).andExpect(status().isForbidden());
        verifyNoInteractions(scheduleService);
    }

    @Test
    void deletingAScheduleRemainsAvailableWithoutExportPermission() throws Exception {
        permissions.remove(Permission.EXPORT_DATA);
        mvc.perform(delete("/api/reports/11/schedule")).andExpect(status().isNoContent());
        verify(scheduleService).delete(11);
    }

    @Test
    void entitySupportBundleStillRequiresOrganizationExportWhenWorkspaceExportIsGranted() throws Exception {
        orgExportAllowed = false;
        mvc.perform(post("/api/orgs/3/support-bundle?entityType=person&entityId=1"))
            .andExpect(status().isForbidden());
        verifyNoInteractions(auditService, bundleReadiness);
    }

    @Test
    void entitySupportBundleStillRequiresAuditReadWhenExportIsGranted() throws Exception {
        permissions.remove(Permission.AUDIT_READ);
        mvc.perform(post("/api/orgs/3/support-bundle?entityType=person&entityId=1"))
            .andExpect(status().isForbidden());
        verifyNoInteractions(auditService, bundleReadiness);
    }

    private static Stream<Arguments> matrix() {
        return Stream.concat(Stream.of(
                new Endpoint(false, "/api/exports/persons", null, 200),
                new Endpoint(false, "/api/exports/companies", null, 200),
                new Endpoint(false, "/api/exports/products", null, 200),
                new Endpoint(false, "/api/exports/deals", null, 200),
                new Endpoint(true, "/api/exports/deals/segment",
                    "{\"definition\":{\"match\":\"all\",\"conditions\":[]}}", 200),
                new Endpoint(false, "/api/audit/export", null, 200),
                new Endpoint(false, "/api/audit/export?entityType=person&entityId=1", null, 200),
                new Endpoint(false, "/api/audit/export?ids=1", null, 200),
                new Endpoint(false, "/api/orgs/3/audit/export", null, 200),
                new Endpoint(false, "/api/orgs/3/audit/export?ids=1", null, 200),
                new Endpoint(true, "/api/orgs/3/support-bundle", null, 200),
                new Endpoint(true, "/api/orgs/3/support-bundle?entityType=person&entityId=1", null, 200),
                new Endpoint(true, "/api/orgs/3/workspaces/5/export", null, 200),
                new Endpoint(false, "/api/orgs/3/workspaces/5/export", null, 200)),
                Stream.concat(reportEndpoints(), scheduleEndpoints()))
            .flatMap(endpoint -> Stream.of(Arguments.of(endpoint, false), Arguments.of(endpoint, true)));
    }

    private static Stream<Endpoint> reportEndpoints() {
        return Stream.of(
            new Endpoint(true, "/api/reports/11/export.csv", null, 200),
            new Endpoint(true, "/api/reports/11/snapshots/3/export.csv", null, 200),
            new Endpoint(true, "/api/reports/11/export-completion", "{\"format\":\"png\"}", 204));
    }

    private static Stream<Endpoint> scheduleEndpoints() {
        String body = "{\"cadence\":\"monthly\",\"recipientUserIds\":[7],\"timezone\":\"UTC\",\"hourOfDay\":9,\"enabled\":true}";
        return Stream.of(new Endpoint(true, "/api/reports/11/schedule", body, 201),
            new Endpoint("PUT", "/api/reports/11/schedule", body, 200));
    }

    private static MockHttpServletRequestBuilder request(Endpoint endpoint) {
        MockHttpServletRequestBuilder request = switch (endpoint.method()) {
            case "POST" -> post(endpoint.path());
            case "PUT" -> put(endpoint.path());
            default -> get(endpoint.path());
        };
        if (endpoint.body() != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(endpoint.body());
        }
        return request.session(new MockHttpSession()).cookie(new Cookie(TenantExportGrantCookie.NAME, TOKEN));
    }

    private <T> T permissionProxy(Class<T> type, T target) {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("workspaceService", workspaceService);
        RequirePermissionAuthorizationManager manager = new RequirePermissionAuthorizationManager(
            beans.getBeanProvider(WorkspaceService.class));
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvisor(new AuthorizationManagerBeforeMethodInterceptor(
            new AnnotationMatchingPointcut(null, RequirePermission.class, true), manager));
        return type.cast(factory.getProxy());
    }

    private static <T> T csvService(Class<T> type) {
        return mock(type, invocation -> invocation.getMethod().getReturnType() == String.class
            ? CSV : Answers.RETURNS_DEFAULTS.answer(invocation));
    }

    private record Endpoint(String method, String path, String body, int successStatus) {
        private Endpoint(boolean post, String path, String body, int successStatus) {
            this(post ? "POST" : "GET", path, body, successStatus);
        }

        private boolean post() {
            return "POST".equals(method);
        }

        @Override
        public String toString() {
            return method + " " + path;
        }
    }
}
