package ooo.klae.connex.backend.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.List;
import java.util.stream.Stream;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.method.HandlerMethod;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.controllers.UserController;
import ooo.klae.connex.backend.controllers.WorkspaceController;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.ServiceUnavailableException;
import ooo.klae.connex.backend.observability.ClientAssertedCorrelationPseudonymizer;
import ooo.klae.connex.backend.services.WorkspaceService;

class TenantControlOnlyResolutionTest {
    private final WorkspaceService workspaces = mock(WorkspaceService.class);
    private final TenantCatalogResolver catalogs = mock(TenantCatalogResolver.class);
    private final WorkspaceCookie cookie = mock(WorkspaceCookie.class);
    private final TenantContext context = new TenantContext();
    private final HttpServletResponse response = mock(HttpServletResponse.class);
    private final TenantResolutionInterceptor interceptor = new TenantResolutionInterceptor(
        workspaces, context, catalogs, new WorkspaceRequestResolver(workspaces), cookie,
        mock(ClientAssertedCorrelationPseudonymizer.class),
            org.mockito.Mockito.mock(ooo.klae.connex.backend.tenant.TenantWorkScope.class));

    @BeforeEach
    void authenticateMember() {
        when(catalogs.isCatalogPerPlacement()).thenReturn(true);
        User user = new User();
        user.setId(7);
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(user, null, List.of()));
        when(workspaces.rememberedWorkspaceIdFor(7)).thenReturn(11);
        when(workspaces.getRole(11, 7)).thenReturn("member");
        when(workspaces.getOrgId(11)).thenReturn(3);
        when(catalogs.resolveCatalog(anyInt())).thenThrow(new ServiceUnavailableException("Unservable placement"));
    }

    @AfterEach
    void clearContext() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            ControlOnlyHandlerPolicy.clear(attributes.getRequest());
        }
        context.clear();
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    @ParameterizedTest
    @MethodSource("handlers")
    void aValidRememberedMembershipRunsEveryReviewedHandlerWithoutPlacement(String httpMethod, Method handler) {
        MockHttpServletRequest request = request(httpMethod);

        assertTrue(interceptor.preHandle(request, response, handler(handler)));

        assertFalse(context.isResolved());
        assertTrue(context.hasControlSelection());
        assertEquals(11, context.getWorkspaceId());
        assertEquals(3, context.getOrgId());
        assertEquals(7, context.getUserId());
        assertEquals("member", context.getRole());
        assertNull(context.getCatalog());
        assertTrue(ControlOnlyHandlerPolicy.isActive());
        assertEquals(3, request.getAttribute(TenantResolutionInterceptor.ORGANIZATION_ID_ATTRIBUTE));
        verify(catalogs).isCatalogPerPlacement();
        verifyNoMoreInteractions(catalogs);
        verifyNoInteractions(cookie);
        verify(workspaces, never()).rememberActive(anyInt(), anyInt());
    }

    @ParameterizedTest
    @MethodSource("handlers")
    void singleDatabaseStillInstallsTheOrdinarySharedScope(String httpMethod, Method handler) {
        when(catalogs.isCatalogPerPlacement()).thenReturn(false);
        org.mockito.Mockito.doReturn(null).when(catalogs).resolveCatalog(3);
        MockHttpServletRequest request = request(httpMethod);

        assertTrue(interceptor.preHandle(request, response, handler(handler)));

        assertTrue(context.isResolved());
        assertFalse(context.hasControlSelection());
        assertFalse(ControlOnlyHandlerPolicy.isActive());
        assertEquals(11, context.getWorkspaceId());
        assertNull(context.getScopeCatalog());
        verify(catalogs).resolveCatalog(3);
    }

    @ParameterizedTest
    @MethodSource("handlers")
    void singleDatabaseStillRefusesTheSameUnservablePlacement(String httpMethod, Method handler) {
        when(catalogs.isCatalogPerPlacement()).thenReturn(false);
        MockHttpServletRequest request = request(httpMethod);

        assertThrows(ServiceUnavailableException.class,
            () -> interceptor.preHandle(request, response, handler(handler)));

        assertFalse(context.isResolved());
        assertFalse(context.hasControlSelection());
        assertFalse(ControlOnlyHandlerPolicy.isActive());
        verify(catalogs).resolveCatalog(3);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aValidExplicitPinRetainsItsOwnSelectionRatherThanTheRememberedWorkspace(boolean matchingCookie)
            throws Exception {
        when(workspaces.getRole(19, 7)).thenReturn("admin");
        when(workspaces.getOrgId(19)).thenReturn(5);
        MockHttpServletRequest request = request("GET");
        request.addHeader("X-Workspace-Id", "19");
        request.setCookies(new Cookie(WorkspaceCookie.NAME, matchingCookie ? "19" : "11"));

        assertTrue(interceptor.preHandle(request, response, workspaceListing()));

        assertEquals(19, context.getWorkspaceId());
        assertEquals(5, context.getOrgId());
        verify(workspaces, never()).rememberedWorkspaceIdFor(anyInt());
        verify(catalogs).isCatalogPerPlacement();
        verifyNoMoreInteractions(catalogs);
        verifyNoInteractions(cookie);
    }

    @Test
    void revokedSafeSelectionStillHealsAndResolvesTheFallbackPlacement() throws Exception {
        when(workspaces.getRole(11, 7)).thenReturn(null);
        when(workspaces.defaultWorkspaceIdFor(7)).thenReturn(19);
        when(workspaces.getRole(19, 7)).thenReturn("member");
        when(workspaces.getOrgId(19)).thenReturn(5);
        org.mockito.Mockito.doReturn("cnx_fallback").when(catalogs).resolveCatalog(5);

        assertTrue(interceptor.preHandle(request("GET"), response, workspaceListing()));

        assertTrue(context.isResolved());
        assertFalse(context.hasControlSelection());
        assertEquals(19, context.getWorkspaceId());
        assertEquals("cnx_fallback", context.getCatalog());
        verify(workspaces).rememberActive(7, 19);
        verify(cookie).set(response, 19);
    }

    @Test
    void revokedSafeSelectionStillFailsWhenItsFallbackPlacementIsUnservable() throws Exception {
        when(workspaces.getRole(11, 7)).thenReturn(null);
        when(workspaces.defaultWorkspaceIdFor(7)).thenReturn(19);
        when(workspaces.getRole(19, 7)).thenReturn("member");
        when(workspaces.getOrgId(19)).thenReturn(5);

        assertThrows(ServiceUnavailableException.class,
            () -> interceptor.preHandle(request("GET"), response, workspaceListing()));

        verify(workspaces).rememberActive(7, 19);
        verify(cookie).set(response, 19);
        assertFalse(context.hasControlSelection());
        assertFalse(ControlOnlyHandlerPolicy.isActive());
    }

    @Test
    void revokedProfileMutationRetainsItsExactRefusalWithoutHealing() throws Exception {
        when(workspaces.getRole(11, 7)).thenReturn(null);
        when(workspaces.defaultWorkspaceIdFor(7)).thenReturn(19);
        MockHttpServletRequest request = request("PUT");
        Method method = UserController.class.getMethod("updateUser", int.class,
            ooo.klae.connex.backend.dto.UserDto.class);

        ForbiddenException failure = assertThrows(ForbiddenException.class,
            () -> interceptor.preHandle(request, response, handler(method)));

        assertEquals("Not a member of workspace 11", failure.getMessage());
        verify(workspaces, never()).rememberActive(anyInt(), anyInt());
        verify(catalogs).isCatalogPerPlacement();
        verifyNoMoreInteractions(catalogs);
        verifyNoInteractions(cookie);
    }

    @Test
    void anExplicitForeignPinIsStillRefusedBeforeTheControlHandler() throws Exception {
        MockHttpServletRequest request = request("GET");
        request.addHeader("X-Workspace-Id", "23");

        ForbiddenException failure = assertThrows(ForbiddenException.class,
            () -> interceptor.preHandle(request, response, workspaceListing()));

        assertEquals("Not a member of workspace 23", failure.getMessage());
        verify(catalogs).isCatalogPerPlacement();
        verifyNoMoreInteractions(catalogs);
        verifyNoInteractions(cookie);
        assertFalse(context.hasControlSelection());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "malformed", "2147483648"})
    void malformedExplicitPinsNeverEarnAControlSelection(String pin) throws Exception {
        MockHttpServletRequest request = request("GET");
        request.addHeader("X-Workspace-Id", pin);

        assertThrows(ForbiddenException.class,
            () -> interceptor.preHandle(request, response, workspaceListing()));

        verify(catalogs).isCatalogPerPlacement();
        verifyNoMoreInteractions(catalogs);
        verifyNoInteractions(cookie);
        assertFalse(context.hasControlSelection());
    }

    @Test
    void repeatedExplicitPinsRemainRefused() throws Exception {
        MockHttpServletRequest request = request("GET");
        request.addHeader("X-Workspace-Id", "11");
        request.addHeader("X-Workspace-Id", "11");

        assertThrows(ForbiddenException.class,
            () -> interceptor.preHandle(request, response, workspaceListing()));
        verify(catalogs).isCatalogPerPlacement();
        verifyNoMoreInteractions(catalogs);
        verifyNoInteractions(cookie);
    }

    @Test
    void theExactHandlerAndHttpMethodAreRequiredInsteadOfAPathLookalike() throws Exception {
        MockHttpServletRequest request = request("GET");
        request.setRequestURI("/api/workspaces");
        Method otherHandler = UserController.class.getMethod("getAllUsers");

        assertThrows(ServiceUnavailableException.class,
            () -> interceptor.preHandle(request, response, handler(otherHandler)));
        assertFalse(ControlOnlyHandlerPolicy.isActive());
        assertFalse(ControlOnlyHandlerPolicy.allows("OPTIONS", workspaceListing()));
        assertFalse(ControlOnlyHandlerPolicy.allows("POST", workspaceListing()));
        assertFalse(ControlOnlyHandlerPolicy.allows("GET", new Object()));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void completionReleasesTheControlSelectionAndRequestCapability(boolean async) throws Exception {
        MockHttpServletRequest request = request("GET");
        HandlerMethod handler = workspaceListing();
        assertTrue(interceptor.preHandle(request, response, handler));

        if (async) {
            interceptor.afterConcurrentHandlingStarted(request, response, handler);
        } else {
            interceptor.afterCompletion(request, response, handler, null);
        }

        assertFalse(context.hasControlSelection());
        assertFalse(ControlOnlyHandlerPolicy.isActive());
        assertNull(context.getWorkspaceId());
    }

    private MockHttpServletRequest request(String method) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/api/workspaces");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        return request;
    }

    private static HandlerMethod workspaceListing() throws NoSuchMethodException {
        return handler(WorkspaceController.class.getMethod("myWorkspaces"));
    }

    private static HandlerMethod handler(Method method) {
        return new HandlerMethod(mock(method.getDeclaringClass()), method);
    }

    static Stream<Arguments> handlers() {
        return ControlOnlyHandlerPolicy.handlers().entrySet().stream().flatMap(entry ->
            "GET".equals(entry.getValue())
                ? Stream.of(Arguments.of("GET", entry.getKey()), Arguments.of("HEAD", entry.getKey()))
                : Stream.of(Arguments.of(entry.getValue(), entry.getKey())));
    }
}
