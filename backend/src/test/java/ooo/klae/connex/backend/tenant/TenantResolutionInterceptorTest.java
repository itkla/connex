package ooo.klae.connex.backend.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.AsyncHandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.services.WorkspaceService;

class TenantResolutionInterceptorTest {
    private final WorkspaceService workspaceService = mock(WorkspaceService.class);
    private final TenantContext tenantContext = mock(TenantContext.class);
    private final TenantCatalogResolver catalogResolver = mock(TenantCatalogResolver.class);
    private final WorkspaceRequestResolver requestResolver = mock(WorkspaceRequestResolver.class);
    private final HttpServletResponse response = mock(HttpServletResponse.class);
    private final Object handler = new Object();
    private final TenantContext liveContext = new TenantContext();

    private TenantResolutionInterceptor interceptor;
    private TenantResolutionInterceptor liveInterceptor;

    @BeforeEach
    void setUp() {
        interceptor = new TenantResolutionInterceptor(
            workspaceService,
            tenantContext,
            catalogResolver,
            requestResolver);
        liveInterceptor = new TenantResolutionInterceptor(
            workspaceService,
            liveContext,
            catalogResolver,
            requestResolver);
        User member = new User();
        member.setId(7);
        authenticateAs(member);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
        liveContext.clear();
    }

    @Test
    void lifecycleEndpointsBypassOrdinaryWorkspaceResolution() {
        assertTrue(preHandle("GET", "/api/orgs/3/workspaces/5/export"));
        assertTrue(preHandle("DELETE", "/api/orgs/3/workspaces/5"));
        assertTrue(preHandle("DELETE", "/api/orgs/3"));

        verifyNoInteractions(requestResolver, workspaceService, catalogResolver);
        verify(tenantContext, times(3)).clear();
        verifyNoMoreInteractions(tenantContext);
    }

    @Test
    void otherOrganizationRequestsStillUseOrdinaryResolution() {
        when(requestResolver.resolve(any(), eq(7)))
            .thenReturn(null);

        assertTrue(preHandle("GET", "/api/orgs/3"));

        verify(requestResolver).resolve(any(), eq(7));
    }

    @Test
    void resolvedRequestsStillInstallTheScopeAfterTheDefensiveClear() {
        stubResolutionFor(7, 11);

        assertTrue(preHandle(liveInterceptor, "GET", "/api/companies"));

        assertTrue(liveContext.isResolved());
        assertEquals(11, liveContext.getWorkspaceId());
        assertEquals(7, liveContext.getUserId());
        assertEquals("owner", liveContext.getRole());
    }

    /**
     * Spring's {@code HandlerExecutionChain.applyAfterConcurrentHandlingStarted}
     * dispatches only to {@link AsyncHandlerInterceptor} implementations, and
     * {@code DispatcherServlet.doDispatch} calls it instead of the
     * {@code afterCompletion} chain. A plain {@code HandlerInterceptor} would
     * silently receive no teardown callback on the streaming endpoints.
     */
    @Test
    void theInterceptorTakesTheAsyncCallbackSpringDispatchesInsteadOfAfterCompletion() {
        assertInstanceOf(
            AsyncHandlerInterceptor.class,
            interceptor,
            "TenantResolutionInterceptor must be an AsyncHandlerInterceptor or Spring skips its "
                + "teardown entirely once a handler starts async processing");
    }

    @Test
    void asyncHandlingStartedReleasesTheScopeInsteadOfLeavingItOnThePooledThread() {
        stubResolutionFor(7, 11);
        assertTrue(preHandle(liveInterceptor, "GET", "/api/attachments/content/token"));
        assertTrue(liveContext.isResolved());

        liveInterceptor.afterConcurrentHandlingStarted(
            mock(HttpServletRequest.class), response, handler);

        assertFalse(
            liveContext.isResolved(),
            "A streaming handler must not hand the container thread back to the pool with the "
                + "tenant scope still installed");
    }

    @Test
    void aWorkspacelessUserInheritsNoScopeLeftOnThePooledThread() {
        liveContext.set(11, 3, 7, "owner", null);
        User workspaceless = new User();
        workspaceless.setId(9);
        authenticateAs(workspaceless);
        when(requestResolver.resolve(any(), eq(9))).thenReturn(null);

        assertTrue(preHandle(liveInterceptor, "GET", "/api/companies"));

        assertFalse(
            liveContext.isResolved(),
            "A user with no active membership must never inherit the tenant scope a previous "
                + "request left on this thread");
        assertNull(liveContext.getWorkspaceId());
        assertNull(liveContext.getUserId());
    }

    @Test
    void lifecycleRequestsInheritNoScopeLeftOnThePooledThread() {
        liveContext.set(11, 3, 7, "owner", null);

        assertTrue(preHandle(liveInterceptor, "DELETE", "/api/orgs/3"));

        assertFalse(liveContext.isResolved());
    }

    @Test
    void unauthenticatedRequestsInheritNoScopeLeftOnThePooledThread() {
        liveContext.set(11, 3, 7, "owner", null);
        SecurityContextHolder.clearContext();

        assertTrue(preHandle(liveInterceptor, "GET", "/api/companies"));

        assertFalse(liveContext.isResolved());
    }

    private void stubResolutionFor(int userId, int workspaceId) {
        when(requestResolver.resolve(any(), eq(userId))).thenReturn(workspaceId);
        when(workspaceService.getRole(workspaceId, userId)).thenReturn("owner");
        when(workspaceService.getOrgId(workspaceId)).thenReturn(3);
        when(catalogResolver.resolveCatalog(3)).thenReturn(null);
    }

    private void authenticateAs(User principal) {
        Authentication authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn(principal);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private boolean preHandle(String method, String path) {
        return preHandle(interceptor, method, path);
    }

    private boolean preHandle(TenantResolutionInterceptor target, String method, String path) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn(method);
        when(request.getRequestURI()).thenReturn(path);
        return target.preHandle(request, response, handler);
    }
}
