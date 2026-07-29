package ooo.klae.connex.backend.tenant;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

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

    private TenantResolutionInterceptor interceptor;
    private User user;

    @BeforeEach
    void setUp() {
        interceptor = new TenantResolutionInterceptor(
            workspaceService,
            tenantContext,
            catalogResolver,
            requestResolver);
        user = new User();
        user.setId(7);
        Authentication authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn(user);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void lifecycleEndpointsBypassOrdinaryWorkspaceResolution() {
        assertTrue(preHandle("GET", "/api/orgs/3/workspaces/5/export"));
        assertTrue(preHandle("DELETE", "/api/orgs/3/workspaces/5"));
        assertTrue(preHandle("DELETE", "/api/orgs/3"));

        verifyNoInteractions(requestResolver, workspaceService, catalogResolver, tenantContext);
    }

    @Test
    void otherOrganizationRequestsStillUseOrdinaryResolution() {
        when(requestResolver.resolve(any(), eq(7)))
            .thenReturn(null);

        assertTrue(preHandle("GET", "/api/orgs/3"));

        verify(requestResolver).resolve(any(), eq(7));
    }

    private boolean preHandle(String method, String path) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn(method);
        when(request.getRequestURI()).thenReturn(path);
        return interceptor.preHandle(request, response, handler);
    }
}
