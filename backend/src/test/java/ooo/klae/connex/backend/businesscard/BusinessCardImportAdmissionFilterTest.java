package ooo.klae.connex.backend.businesscard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.Collection;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.Part;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.capability.Capability;
import ooo.klae.connex.backend.capability.CapabilityEntitlement;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.TooManyRequestsException;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.WorkspaceRequestResolver;

@ExtendWith(MockitoExtension.class)
class BusinessCardImportAdmissionFilterTest {
    private static final String IDEMPOTENCY_KEY =
        "02a25a23-70af-4f8e-a64a-6cfc5f8c69be";

    @Mock BusinessCardRateLimiter rateLimiter;
    @Mock CapabilityEntitlement capabilityEntitlement;
    @Mock WorkspaceRequestResolver workspaceRequestResolver;
    @Mock WorkspaceService workspaceService;

    private BusinessCardImportAdmissionFilter filter;

    @BeforeEach
    void setUp() {
        filter = new BusinessCardImportAdmissionFilter(
            rateLimiter,
            capabilityEntitlement,
            workspaceRequestResolver,
            workspaceService);
        org.mockito.Mockito.lenient()
            .when(capabilityEntitlement.isEntitled(Capability.BUSINESS_CARD_IMPORT))
            .thenReturn(true);
        org.mockito.Mockito.lenient()
            .when(capabilityEntitlement.isEntitled(Capability.BUSINESS_CARD_SCANNING))
            .thenReturn(true);
        User user = new User();
        user.setId(9);
        org.mockito.Mockito.lenient()
            .when(workspaceRequestResolver.resolve(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(9)))
            .thenReturn(7);
        SecurityContextHolder.getContext().setAuthentication(
            new TestingAuthenticationToken(user, null, "ROLE_USER"));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void rejectsMalformedKeyBeforeMultipartOrChainAccess() throws Exception {
        TrackingMultipartRequest request = request("not-a-uuid");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(400, response.getStatus());
        assertJsonRejection(
            response,
            "BUSINESS_CARD_INVALID_IDEMPOTENCY_KEY",
            "The idempotency key is invalid");
        assertNull(chain.getRequest());
        assertFalse(request.bodyAccessed());
    }

    @Test
    void rejectsPrincipalThrottleBeforeMultipartOrChainAccess() throws Exception {
        TrackingMultipartRequest request = request(IDEMPOTENCY_KEY);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        doThrow(new TooManyRequestsException("limited"))
            .when(rateLimiter).requireImportAdmissionAllowed(9);

        filter.doFilter(request, response, chain);

        assertEquals(429, response.getStatus());
        assertJsonRejection(
            response,
            "BUSINESS_CARD_RATE_LIMITED",
            "Too many business-card requests");
        assertNull(chain.getRequest());
        assertFalse(request.bodyAccessed());
    }

    @Test
    void validAuthenticatedAdmissionContinuesWithoutParsingMultipart() throws Exception {
        TrackingMultipartRequest request = request(IDEMPOTENCY_KEY);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertNotNull(chain.getRequest());
        assertFalse(request.bodyAccessed());
        verify(rateLimiter).requireImportAdmissionAllowed(9);
    }

    @Test
    void entitlementDenialReturns403BeforeMultipartAndThrottleAccess() throws Exception {
        when(capabilityEntitlement.isEntitled(Capability.BUSINESS_CARD_IMPORT))
            .thenReturn(false);
        TrackingMultipartRequest request = request(IDEMPOTENCY_KEY);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(403, response.getStatus());
        assertJsonRejection(
            response,
            "BUSINESS_CARD_CAPABILITY_UNAVAILABLE",
            "Business-card operations are unavailable");
        assertNull(chain.getRequest());
        assertFalse(request.bodyAccessed());
        verify(rateLimiter, never()).requireImportAdmissionAllowed(9);
    }

    @Test
    void scanThrottleRunsBeforeMultipartAccess() throws Exception {
        TrackingMultipartRequest request = request(
            "POST", "/api/business-cards/scan", null);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        doThrow(new TooManyRequestsException("limited"))
            .when(rateLimiter).requireScanAdmissionAllowed(9);

        filter.doFilter(request, response, chain);

        assertEquals(429, response.getStatus());
        assertJsonRejection(
            response,
            "BUSINESS_CARD_RATE_LIMITED",
            "Too many business-card requests");
        assertNull(chain.getRequest());
        assertFalse(request.bodyAccessed());
    }

    @Test
    void scanEntitlementDenialReturns403BeforeMultipartAndThrottleAccess() throws Exception {
        when(capabilityEntitlement.isEntitled(Capability.BUSINESS_CARD_SCANNING))
            .thenReturn(false);
        TrackingMultipartRequest request = request(
            "POST", "/api/business-cards/scan", null);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(403, response.getStatus());
        assertJsonRejection(
            response,
            "BUSINESS_CARD_CAPABILITY_UNAVAILABLE",
            "Business-card operations are unavailable");
        assertNull(chain.getRequest());
        assertFalse(request.bodyAccessed());
        verify(rateLimiter, never()).requireScanAdmissionAllowed(9);
    }

    @Test
    void scanPermissionDenialReturns403BeforeMultipartAndThrottleAccess() throws Exception {
        TrackingMultipartRequest request = request(
            "POST", "/api/business-cards/scan", null);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        doThrow(new ForbiddenException("denied"))
            .when(workspaceService).requirePermission(7, 9, Permission.PERSON_CREATE);

        filter.doFilter(request, response, chain);

        assertEquals(403, response.getStatus());
        assertJsonRejection(
            response,
            "BUSINESS_CARD_PERMISSION_DENIED",
            "Business-card permission is required");
        assertNull(chain.getRequest());
        assertFalse(request.bodyAccessed());
        verify(rateLimiter, never()).requireScanAdmissionAllowed(9);
    }

    @Test
    void attachmentPermissionDenialReturns403BeforeMultipartAndThrottleAccess() throws Exception {
        TrackingMultipartRequest request = request(
            "POST", "/api/business-cards/scan", null);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        doNothing()
            .when(workspaceService).requirePermission(7, 9, Permission.PERSON_CREATE);
        doThrow(new ForbiddenException("denied"))
            .when(workspaceService).requirePermission(7, 9, Permission.ATTACHMENT_CREATE);

        filter.doFilter(request, response, chain);

        assertEquals(403, response.getStatus());
        assertJsonRejection(
            response,
            "BUSINESS_CARD_PERMISSION_DENIED",
            "Business-card permission is required");
        assertNull(chain.getRequest());
        assertFalse(request.bodyAccessed());
        verify(rateLimiter, never()).requireScanAdmissionAllowed(9);
    }

    @Test
    void missingWorkspaceReturns403BeforeMultipartAndThrottleAccess() throws Exception {
        when(workspaceRequestResolver.resolve(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(9)))
            .thenReturn(null);
        TrackingMultipartRequest request = request(
            "POST", "/api/business-cards/scan", null);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(403, response.getStatus());
        assertJsonRejection(
            response,
            "BUSINESS_CARD_WORKSPACE_REQUIRED",
            "A workspace is required for business-card operations");
        assertNull(chain.getRequest());
        assertFalse(request.bodyAccessed());
        verify(rateLimiter, never()).requireScanAdmissionAllowed(9);
    }

    @Test
    void reservationThrottleRunsBeforeTheControllerAndDatabasePath() throws Exception {
        TrackingMultipartRequest request = request(
            "POST", "/api/business-cards/import/reservation", IDEMPOTENCY_KEY);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        doThrow(new TooManyRequestsException("limited"))
            .when(rateLimiter).requireReservationAllowed(9);

        filter.doFilter(request, response, chain);

        assertEquals(429, response.getStatus());
        assertJsonRejection(
            response,
            "BUSINESS_CARD_RATE_LIMITED",
            "Too many business-card requests");
        assertNull(chain.getRequest());
        assertFalse(request.bodyAccessed());
    }

    @Test
    void statusThrottleRunsBeforeTheControllerAndDatabasePath() throws Exception {
        TrackingMultipartRequest request = request(
            "GET", "/api/business-cards/import", IDEMPOTENCY_KEY);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        doThrow(new TooManyRequestsException("limited"))
            .when(rateLimiter).requireStatusAllowed(9);

        filter.doFilter(request, response, chain);

        assertEquals(429, response.getStatus());
        assertJsonRejection(
            response,
            "BUSINESS_CARD_RATE_LIMITED",
            "Too many business-card requests");
        assertNull(chain.getRequest());
        assertFalse(request.bodyAccessed());
    }

    private static TrackingMultipartRequest request(String idempotencyKey) {
        return request("POST", "/api/business-cards/import", idempotencyKey);
    }

    private static TrackingMultipartRequest request(
            String method,
            String path,
            String idempotencyKey) {
        TrackingMultipartRequest request = new TrackingMultipartRequest();
        request.setMethod(method);
        request.setRequestURI(path);
        request.setContentType("multipart/form-data; boundary=x");
        if (idempotencyKey != null) {
            request.addHeader("Idempotency-Key", idempotencyKey);
        }
        return request;
    }

    private static void assertJsonRejection(
            MockHttpServletResponse response,
            String code,
            String error) throws Exception {
        assertNotNull(response.getContentType());
        assertTrue(MediaType.parseMediaType(response.getContentType())
            .isCompatibleWith(MediaType.APPLICATION_JSON));
        assertEquals(
            "{\"error\":\"" + error + "\",\"code\":\"" + code + "\"}",
            response.getContentAsString());
    }

    private static final class TrackingMultipartRequest extends MockHttpServletRequest {
        private boolean bodyAccessed;

        @Override
        public ServletInputStream getInputStream() {
            bodyAccessed = true;
            return super.getInputStream();
        }

        @Override
        public Collection<Part> getParts() throws IOException, ServletException {
            bodyAccessed = true;
            return super.getParts();
        }

        private boolean bodyAccessed() {
            return bodyAccessed;
        }
    }
}
