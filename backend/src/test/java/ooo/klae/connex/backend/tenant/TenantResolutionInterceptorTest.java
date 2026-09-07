package ooo.klae.connex.backend.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.AsyncHandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.observability.ClientAssertedCorrelationPseudonymizer;
import ooo.klae.connex.backend.publicapi.ApiCredentialAuthenticationFilter;
import ooo.klae.connex.backend.publicapi.ApiCredentialAuthenticationFilter.TenantBinding;
import ooo.klae.connex.backend.publicapi.ApiCredentialPrincipal;
import ooo.klae.connex.backend.publicapi.ApiScope;
import ooo.klae.connex.backend.services.WorkspaceService;

class TenantResolutionInterceptorTest {
    private final WorkspaceService workspaceService = mock(WorkspaceService.class);
    private final TenantContext tenantContext = mock(TenantContext.class);
    private final TenantCatalogResolver catalogResolver = mock(TenantCatalogResolver.class);
    private final WorkspaceRequestResolver requestResolver = mock(WorkspaceRequestResolver.class);
    private final WorkspaceCookie workspaceCookie = mock(WorkspaceCookie.class);
    private final ClientAssertedCorrelationPseudonymizer correlationPseudonymizer =
        mock(ClientAssertedCorrelationPseudonymizer.class);
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
            requestResolver,
            workspaceCookie,
            correlationPseudonymizer);
        liveInterceptor = new TenantResolutionInterceptor(
            workspaceService,
            liveContext,
            catalogResolver,
            requestResolver,
            workspaceCookie,
            correlationPseudonymizer);
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

        verifyNoInteractions(requestResolver, workspaceService, catalogResolver, workspaceCookie);
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

    @Test
    void publicCredentialRequestRetainsTheFilterBindingWithoutResolvingPlacementAgain() {
        MockHttpServletRequest request =
            new MockHttpServletRequest("GET", "/api/v1/me");
        User user = new User();
        user.setId(7);
        ApiCredentialPrincipal credential = new ApiCredentialPrincipal(
            5,
            7,
            11,
            3,
            "Tenant binding",
            Set.of(ApiScope.CRM_READ),
            Set.of(ApiScope.CRM_READ),
            LocalDateTime.now().plusDays(1));
        Authentication authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn(user);
        SecurityContextHolder.getContext().setAuthentication(authentication);
        when(requestResolver.isPublicApiRequest(request)).thenReturn(true);
        when(requestResolver.resolvePublicApiCredential(request, authentication, 7))
            .thenReturn(credential);
        when(catalogResolver.resolveCatalog(3)).thenReturn("catalog-b");
        liveContext.set(11, 3, 7, "public_api", "catalog-a");
        request.setAttribute(
            ApiCredentialAuthenticationFilter.TENANT_BINDING_ATTRIBUTE,
            new TenantBinding(5, 11, 3, 7, "catalog-a"));

        assertTrue(liveInterceptor.preHandle(request, response, handler));

        assertEquals(11, liveContext.getWorkspaceId());
        assertEquals(3, liveContext.getOrgId());
        assertEquals("catalog-a", liveContext.getScopeCatalog());
        verify(catalogResolver, never()).resolveCatalog(anyInt());
        verifyNoInteractions(workspaceService);
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

    @Test
    void revokedMembershipFallsBackToNextWorkspaceAndHealsTheCookie() {
        when(requestResolver.resolve(any(), eq(7))).thenReturn(11);
        when(requestResolver.isStaleWorkspacePin(any(), eq(11))).thenReturn(true);
        when(workspaceService.getRole(11, 7)).thenReturn(null);
        when(workspaceService.defaultWorkspaceIdFor(7)).thenReturn(19);
        when(workspaceService.getRole(19, 7)).thenReturn("member");
        when(workspaceService.getOrgId(19)).thenReturn(3);
        when(catalogResolver.resolveCatalog(3)).thenReturn(null);

        assertTrue(preHandle(liveInterceptor, "GET", "/api/workspaces"));

        assertTrue(liveContext.isResolved());
        assertEquals(19, liveContext.getWorkspaceId());
        assertEquals("member", liveContext.getRole());
        verify(workspaceService).rememberActive(7, 19);
        verify(workspaceCookie).set(response, 19);
        verify(workspaceCookie, never()).clear(response);
    }

    @Test
    void revokedMembershipWithNoRemainingWorkspaceClearsTheCookieAndStaysUnresolved() {
        when(requestResolver.resolve(any(), eq(7))).thenReturn(11);
        when(requestResolver.isStaleWorkspacePin(any(), eq(11))).thenReturn(true);
        when(workspaceService.getRole(11, 7)).thenReturn(null);
        when(workspaceService.defaultWorkspaceIdFor(7)).thenReturn(null);

        assertTrue(preHandle(liveInterceptor, "GET", "/api/workspaces"));

        assertFalse(liveContext.isResolved());
        verify(workspaceCookie).clear(response);
        verify(workspaceCookie, never()).set(any(), anyInt());
        verify(workspaceService, never()).rememberActive(anyInt(), anyInt());
    }

    @Test
    void explicitForeignWorkspacePinStillReturnsForbidden() {
        when(requestResolver.resolve(any(), eq(7))).thenReturn(99);
        when(requestResolver.isStaleWorkspacePin(any(), eq(99))).thenReturn(false);
        when(workspaceService.getRole(99, 7)).thenReturn(null);

        try {
            preHandle(liveInterceptor, "GET", "/api/companies");
            throw new AssertionError("expected ForbiddenException");
        } catch (ooo.klae.connex.backend.exceptions.ForbiddenException exception) {
            assertEquals("Not a member of workspace 99", exception.getMessage());
        }

        assertFalse(liveContext.isResolved());
        verify(workspaceCookie, never()).set(any(), anyInt());
        verify(workspaceCookie, never()).clear(response);
        verify(workspaceService, never()).defaultWorkspaceIdFor(anyInt());
    }

    @Test
    void forgedNonMemberCandidateNeverInstallsThatWorkspace() {
        when(requestResolver.resolve(any(), eq(7))).thenReturn(99);
        when(requestResolver.isStaleWorkspacePin(any(), eq(99))).thenReturn(true);
        when(workspaceService.getRole(99, 7)).thenReturn(null);
        when(workspaceService.defaultWorkspaceIdFor(7)).thenReturn(19);
        when(workspaceService.getRole(19, 7)).thenReturn("owner");
        when(workspaceService.getOrgId(19)).thenReturn(3);
        when(catalogResolver.resolveCatalog(3)).thenReturn(null);

        assertTrue(preHandle(liveInterceptor, "GET", "/api/companies"));

        assertEquals(19, liveContext.getWorkspaceId());
        verify(workspaceService, never()).getOrgId(99);
        verify(workspaceCookie).set(response, 19);
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
