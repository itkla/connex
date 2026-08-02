package ooo.klae.connex.backend.controllers;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.GlobalExceptionHandler;
import ooo.klae.connex.backend.exceptions.RecentAuthenticationRequiredException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.exceptions.TooManyRequestsException;
import ooo.klae.connex.backend.observability.ErrorReporter;
import ooo.klae.connex.backend.services.AuthService;
import ooo.klae.connex.backend.services.SupportBundleService;
import ooo.klae.connex.backend.services.SupportBundleService.SupportBundle;
import ooo.klae.connex.backend.services.SupportBundleService.SupportBundleRequest;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.TenantContext;

/**
 * Covers the support bundle endpoint's authorization gates and response contract.
 *
 * <p>The entity-filter gate in particular had no coverage while it was silently dead, so these
 * cases assert that the workspace's real organization is consulted and that organization
 * administration alone never unlocks workspace record events.
 */
class SupportBundleControllerTest {
    private static final int ORG_ID = 3;
    private static final int WORKSPACE_ID = 7;
    private static final int ACTOR_ID = 55;

    private SupportBundleService supportBundleService;
    private AuthService authService;
    private WorkspaceService workspaceService;
    private TenantContext tenantContext;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        supportBundleService = Mockito.mock(SupportBundleService.class);
        authService = Mockito.mock(AuthService.class);
        workspaceService = Mockito.mock(WorkspaceService.class);
        tenantContext = Mockito.mock(TenantContext.class);

        User actor = new User();
        actor.setId(ACTOR_ID);
        lenient().when(authService.getCurrentUser()).thenReturn(actor);
        lenient().when(tenantContext.getWorkspaceId()).thenReturn(WORKSPACE_ID);
        lenient().when(workspaceService.getOrgId(WORKSPACE_ID)).thenReturn(ORG_ID);
        lenient().when(supportBundleService.generate(any(), anyInt()))
            .thenReturn(new SupportBundle("bundle.zip", new byte[] { 1, 2, 3 }));

        mockMvc = MockMvcBuilders
            .standaloneSetup(new SupportBundleController(
                supportBundleService, authService, workspaceService, tenantContext))
            .setControllerAdvice(new GlobalExceptionHandler(
                Mockito.mock(ErrorReporter.class), tenantContext))
            .build();
    }

    @Test
    void returnsTheBundleWithHardenedDownloadHeaders() throws Exception {
        mockMvc.perform(get("/api/orgs/{orgId}/support-bundle", ORG_ID))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "application/zip"))
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andExpect(header().string("Cross-Origin-Resource-Policy", "same-origin"))
            .andExpect(header().string("Content-Security-Policy",
                "default-src 'none'; sandbox; frame-ancestors 'none'; base-uri 'none'"))
            .andExpect(header().string("Content-Disposition",
                org.hamcrest.Matchers.containsString("attachment")));
    }

    @Test
    void aNonOrgAdminIsRefused() throws Exception {
        doThrow(new ForbiddenException("not an org admin"))
            .when(supportBundleService).generate(any(), anyInt());

        mockMvc.perform(get("/api/orgs/{orgId}/support-bundle", ORG_ID))
            .andExpect(status().isForbidden());
    }

    @Test
    void aStaleStepUpIsRefused() throws Exception {
        doThrow(new RecentAuthenticationRequiredException())
            .when(supportBundleService).generate(any(), anyInt());

        mockMvc.perform(get("/api/orgs/{orgId}/support-bundle", ORG_ID))
            .andExpect(status().is4xxClientError());
    }

    @Test
    void saturationIsReportedAsTooManyRequests() throws Exception {
        doThrow(new TooManyRequestsException("busy"))
            .when(supportBundleService).generate(any(), anyInt());

        mockMvc.perform(get("/api/orgs/{orgId}/support-bundle", ORG_ID))
            .andExpect(status().isTooManyRequests());
    }

    @Test
    void anEntityFilterInTheCallersOwnOrganizationResolvesItsWorkspace() throws Exception {
        mockMvc.perform(get("/api/orgs/{orgId}/support-bundle", ORG_ID)
                .param("entityType", "person")
                .param("entityId", "412"))
            .andExpect(status().isOk());

        verify(workspaceService).requirePermission(Permission.AUDIT_READ);
        ArgumentCaptor<SupportBundleRequest> captor =
            ArgumentCaptor.forClass(SupportBundleRequest.class);
        verify(supportBundleService).generate(captor.capture(), eq(ACTOR_ID));
        org.junit.jupiter.api.Assertions.assertEquals(WORKSPACE_ID, captor.getValue().workspaceId());
        org.junit.jupiter.api.Assertions.assertEquals("person", captor.getValue().entityType());
    }

    /**
     * The regression that motivated this class: the organization comparison read a bean field the
     * workspace query never populates, so it compared against zero and this branch could never be
     * reached for any real organization.
     */
    @Test
    void anEntityFilterAgainstAForeignOrganizationsWorkspaceIsNotFound() throws Exception {
        when(workspaceService.getOrgId(WORKSPACE_ID)).thenReturn(ORG_ID + 1);

        mockMvc.perform(get("/api/orgs/{orgId}/support-bundle", ORG_ID)
                .param("entityType", "person")
                .param("entityId", "412"))
            .andExpect(status().isNotFound());

        verify(workspaceService, never()).requirePermission(any());
        verify(supportBundleService, never()).generate(any(), anyInt());
    }

    @Test
    void anEntityFilterWithoutAuditReadIsRefused() throws Exception {
        doThrow(new ForbiddenException("no AUDIT_READ"))
            .when(workspaceService).requirePermission(Permission.AUDIT_READ);

        mockMvc.perform(get("/api/orgs/{orgId}/support-bundle", ORG_ID)
                .param("entityType", "person")
                .param("entityId", "412"))
            .andExpect(status().isForbidden());

        verify(supportBundleService, never()).generate(any(), anyInt());
    }

    /**
     * The organization must be confirmed before the workspace permission is consulted, so a
     * foreign workspace is rejected without the finer check ever revealing whether the caller
     * happens to hold a permission in it.
     */
    @Test
    void theOrganizationIsConfirmedBeforeTheWorkspacePermission() throws Exception {
        mockMvc.perform(get("/api/orgs/{orgId}/support-bundle", ORG_ID)
                .param("entityType", "person")
                .param("entityId", "412"))
            .andExpect(status().isOk());

        InOrder order = inOrder(workspaceService);
        order.verify(workspaceService).getOrgId(WORKSPACE_ID);
        order.verify(workspaceService).requirePermission(Permission.AUDIT_READ);
    }

    @Test
    void anEntityFilterWithoutAnActiveWorkspaceIsRejected() throws Exception {
        when(tenantContext.getWorkspaceId()).thenReturn(null);

        mockMvc.perform(get("/api/orgs/{orgId}/support-bundle", ORG_ID)
                .param("entityType", "person")
                .param("entityId", "412"))
            .andExpect(status().isBadRequest());

        verify(supportBundleService, never()).generate(any(), anyInt());
    }

    @Test
    void aHalfSuppliedEntityFilterIsRejected() throws Exception {
        mockMvc.perform(get("/api/orgs/{orgId}/support-bundle", ORG_ID)
                .param("entityType", "person"))
            .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/orgs/{orgId}/support-bundle", ORG_ID)
                .param("entityId", "412"))
            .andExpect(status().isBadRequest());

        verify(supportBundleService, never()).generate(any(), anyInt());
    }

    @Test
    void malformedFiltersAreRejectedBeforeAnyWorkIsDone() throws Exception {
        mockMvc.perform(get("/api/orgs/{orgId}/support-bundle", ORG_ID)
                .param("correlationId", "has space"))
            .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/orgs/{orgId}/support-bundle", ORG_ID)
                .param("since", "not-an-instant"))
            .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/orgs/{orgId}/support-bundle", ORG_ID)
                .param("entityType", "Person!")
                .param("entityId", "412"))
            .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/orgs/{orgId}/support-bundle", ORG_ID)
                .param("entityType", "person")
                .param("entityId", "-1"))
            .andExpect(status().isBadRequest());

        verify(supportBundleService, never()).generate(any(), anyInt());
    }

    @Test
    void aResourceNotFoundFromTheServiceSurfacesAsNotFound() throws Exception {
        doThrow(new ResourceNotFoundException("missing"))
            .when(supportBundleService).generate(any(), anyInt());

        mockMvc.perform(get("/api/orgs/{orgId}/support-bundle", ORG_ID))
            .andExpect(status().isNotFound());
    }

    @Test
    void aCeilingOrBudgetBreachIsReportedWithGuidanceRatherThanAsAServerError() throws Exception {
        doThrow(new SupportBundleService.SupportBundleTooLargeException(
                "Support bundle exceeded its uncompressed ceiling; narrow the window with since"))
            .when(supportBundleService).generate(any(), anyInt());

        mockMvc.perform(get("/api/orgs/{orgId}/support-bundle", ORG_ID))
            .andExpect(status().isPayloadTooLarge())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                .string(org.hamcrest.Matchers.containsString("narrow the window")));
    }

    @Test
    void theWorkspaceIsNotConsultedWithoutAnEntityFilter() throws Exception {
        mockMvc.perform(get("/api/orgs/{orgId}/support-bundle", ORG_ID))
            .andExpect(status().isOk());

        verify(workspaceService, never()).getOrgId(anyInt());
        verify(workspaceService, never()).requirePermission(any());
    }
}
