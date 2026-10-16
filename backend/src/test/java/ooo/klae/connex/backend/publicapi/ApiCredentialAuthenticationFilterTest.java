package ooo.klae.connex.backend.publicapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.access.ExceptionTranslationFilter;
import org.springframework.security.web.firewall.RequestRejectedException;
import org.springframework.security.web.firewall.StrictHttpFirewall;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.config.PrivilegedMfaProperties;
import ooo.klae.connex.backend.config.PrivilegedMfaEnforcementFilter;
import ooo.klae.connex.backend.exceptions.PrivilegedMfaAttestationRequiredException;
import ooo.klae.connex.backend.exceptions.PrivilegedMfaEnrollmentRequiredException;
import jakarta.servlet.ServletException;
import ooo.klae.connex.backend.publicapi.ApiCredentialAuthenticationFilter.RouteTransactionMode;
import ooo.klae.connex.backend.publicapi.ApiCredentialService.AuthenticatedCredential;
import ooo.klae.connex.backend.publicapi.ApiCredentialService.RoutingBinding;
import ooo.klae.connex.backend.services.AuditService;
import ooo.klae.connex.backend.services.DenialAuditRateLimiter;
import ooo.klae.connex.backend.services.PrivilegedAccountService;
import ooo.klae.connex.backend.services.PrivilegedMfaStanding;
import ooo.klae.connex.backend.tenant.TenantCatalogResolver;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.util.ClientIpResolver;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

class ApiCredentialAuthenticationFilterTest {

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clear();
        MDC.clear();
    }

    @Test
    void preAuthenticationRefusalHappensBeforeTokenHashLookup() throws Exception {
        ApiCredentialService service = mock(ApiCredentialService.class);
        ApiRateLimiter limiter = mock(ApiRateLimiter.class);
        ClientIpResolver clientIpResolver = mock(ClientIpResolver.class);
        ObjectMapper objectMapper = new ObjectMapper();
        ApiCredentialAuthenticationFilter filter = new ApiCredentialAuthenticationFilter(
            service,
            limiter,
            objectMapper,
            new PrivilegedMfaProperties(),
            mock(PrivilegedAccountService.class),
            mock(AuditService.class),
            mock(DenialAuditRateLimiter.class),
            clientIpResolver,
            mock(TenantCatalogResolver.class),
            new TenantContext(),
            requestToClassify -> RouteTransactionMode.READ,
            mock(PlatformTransactionManager.class));
        String token = "cnx_pat_" + "a".repeat(39) + "last";
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/me");
        request.setRemoteAddr("192.0.2.8");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(clientIpResolver.resolve(request)).thenReturn("192.0.2.8");
        when(limiter.acquireBeforeAuthentication("192.0.2.8", token))
            .thenReturn(new ApiRateLimiter.Decision(false, 2, 0, 60, 60));

        filter.doFilter(request, response, new MockFilterChain());

        JsonNode body = objectMapper.readTree(response.getContentAsByteArray());
        assertEquals(429, response.getStatus());
        assertEquals("rate_limit_exceeded", body.path("error").path("code").textValue());
        verify(service, never()).resolveRoutingBinding(token);
    }

    @Test
    void preAuthenticationLimiterFailureUsesSanitizedEnvelopeWithoutDispatch() throws Exception {
        ApiCredentialService service = mock(ApiCredentialService.class);
        ApiRateLimiter limiter = mock(ApiRateLimiter.class);
        ClientIpResolver clientIpResolver = mock(ClientIpResolver.class);
        ObjectMapper objectMapper = new ObjectMapper();
        ApiCredentialAuthenticationFilter filter = new ApiCredentialAuthenticationFilter(
            service,
            limiter,
            objectMapper,
            new PrivilegedMfaProperties(),
            mock(PrivilegedAccountService.class),
            mock(AuditService.class),
            mock(DenialAuditRateLimiter.class),
            clientIpResolver,
            mock(TenantCatalogResolver.class),
            new TenantContext(),
            requestToClassify -> RouteTransactionMode.READ,
            mock(PlatformTransactionManager.class));
        String token = token();
        MockHttpServletRequest request = authenticatedRequest("GET");
        request.setSecure(true);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        when(clientIpResolver.resolve(request)).thenReturn("192.0.2.9");
        when(limiter.acquireBeforeAuthentication("192.0.2.9", token))
            .thenThrow(new IllegalStateException("HMAC initialization detail"));

        List<ILoggingEvent> events = captureLogs(
            ApiCredentialAuthenticationFilter.class,
            () -> filter.doFilter(request, response, chain));

        JsonNode body = objectMapper.readTree(response.getContentAsByteArray());
        assertEquals(500, response.getStatus());
        assertEquals("internal_error", body.path("error").path("code").textValue());
        assertEquals("An unexpected error occurred", body.path("error").path("message").textValue());
        assertFalse(response.getContentAsString().contains("HMAC initialization detail"));
        assertEquals("no-referrer", response.getHeader("Referrer-Policy"));
        assertEquals("no-store", response.getHeader(HttpHeaders.CACHE_CONTROL));
        assertTrue(response.getHeader("Strict-Transport-Security").contains("max-age=31536000"));
        assertNull(chain.getRequest());
        assertEquals(1, events.size());
        assertNull(events.getFirst().getThrowableProxy());
        assertFalse(events.getFirst().getFormattedMessage().contains("HMAC initialization detail"));
        assertFalse(events.getFirst().getFormattedMessage().contains(token));
        verify(service, never()).resolveRoutingBinding(token);
    }

    @Test
    void readRouteKeepsAuthenticationSnapshotThroughTheSynchronousChain() throws Exception {
        TestTransactionManager transactionManager = new TestTransactionManager();
        ApiCredentialAuthenticationFilter filter = authenticatedFilter(
            RouteTransactionMode.READ, transactionManager);
        MockHttpServletRequest request = authenticatedRequest("GET");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean dispatched = new AtomicBoolean();

        filter.doFilter(request, response, (servletRequest, servletResponse) -> {
            dispatched.set(true);
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
        });

        assertTrue(dispatched.get());
        assertEquals(200, response.getStatus());
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertEquals(1, transactionManager.beginCount());
        assertEquals(1, transactionManager.commitCount());
    }

    @Test
    void writeRouteCommitsAuthenticationBeforeControllerDispatch() throws Exception {
        TestTransactionManager transactionManager = new TestTransactionManager();
        ApiCredentialAuthenticationFilter filter = authenticatedFilter(
            RouteTransactionMode.WRITE, transactionManager);
        MockHttpServletRequest request = authenticatedRequest("POST");
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicBoolean dispatched = new AtomicBoolean();
        filter.doFilter(request, response, (servletRequest, servletResponse) -> {
            dispatched.set(true);
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(1, transactionManager.commitCount());
        });

        assertTrue(dispatched.get());

        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertEquals(1, transactionManager.beginCount());
        assertEquals(1, transactionManager.commitCount());
    }

    @Test
    void downstreamServletExceptionUsesSanitizedEnvelope() throws Exception {
        ApiCredentialAuthenticationFilter filter = authenticatedFilter(
            RouteTransactionMode.READ, new TestTransactionManager());
        MockHttpServletResponse response = new MockHttpServletResponse();
        ObjectMapper objectMapper = new ObjectMapper();

        filter.doFilter(authenticatedRequest("GET"), response, (request, servletResponse) -> {
            throw new jakarta.servlet.ServletException("downstream servlet detail");
        });

        assertInternalErrorEnvelope(objectMapper, response, "downstream servlet detail");
    }

    @Test
    void downstreamServletExceptionWrappingIOExceptionUsesSanitizedEnvelope() throws Exception {
        ApiCredentialAuthenticationFilter filter = authenticatedFilter(
            RouteTransactionMode.READ, new TestTransactionManager());
        MockHttpServletResponse response = new MockHttpServletResponse();
        ObjectMapper objectMapper = new ObjectMapper();

        filter.doFilter(authenticatedRequest("GET"), response, (request, servletResponse) -> {
            throw new jakarta.servlet.ServletException(
                "downstream wrapper detail",
                new IOException("downstream I/O detail"));
        });

        assertInternalErrorEnvelope(objectMapper, response, "downstream I/O detail");
        assertFalse(response.getContentAsString().contains("downstream wrapper detail"));
    }

    @Test
    void downstreamAccessDeniedStillReachesExceptionTranslation() throws Exception {
        ApiCredentialAuthenticationFilter filter = authenticatedFilter(
            RouteTransactionMode.READ, new TestTransactionManager());
        ObjectMapper objectMapper = new ObjectMapper();
        ExceptionTranslationFilter exceptionTranslationFilter = new ExceptionTranslationFilter(
            (request, response, exception) -> PublicApiErrorAdvice.write(
                objectMapper,
                request,
                response,
                HttpStatus.UNAUTHORIZED,
                "invalid_token",
                "A valid bearer credential is required"));
        exceptionTranslationFilter.setAccessDeniedHandler((request, response, exception) ->
            PublicApiErrorAdvice.write(
                objectMapper,
                request,
                response,
                HttpStatus.FORBIDDEN,
                "insufficient_scope",
                "The credential cannot access this resource"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(authenticatedRequest("GET"), response, (request, servletResponse) ->
            exceptionTranslationFilter.doFilter(request, servletResponse, (downstreamRequest, downstreamResponse) -> {
                throw new AccessDeniedException("scope detail");
            }));

        JsonNode body = objectMapper.readTree(response.getContentAsByteArray());
        assertEquals(403, response.getStatus());
        assertEquals("insufficient_scope", body.path("error").path("code").textValue());
        assertFalse(response.getContentAsString().contains("scope detail"));
    }

    @Test
    void publicBoundaryMapsTheBrowserMfaCodeToLowerSnakeCase() {
        assertEquals(
            "privileged_mfa_enrollment_required",
            PublicApiErrorAdvice.envelope(
                PrivilegedMfaEnforcementFilter.ENROLLMENT_REQUIRED_CODE,
                "Enrollment required").error().code());
        assertEquals(
            "PRIVILEGED_MFA_ENROLLMENT_REQUIRED",
            PrivilegedMfaEnforcementFilter.ENROLLMENT_REQUIRED_CODE);
    }

    @Test
    void privilegedAccountWithoutPasskeyGetsEnrollmentRequired401() throws Exception {
        assertConfined(
            PrivilegedMfaStanding.ENROLLMENT_REQUIRED,
            "privileged_mfa_enrollment_required",
            "A passkey must be enrolled before this privileged account can continue");
    }

    @Test
    void privilegedAccountWithoutApprovedPasskeyGetsAttestationRequired401() throws Exception {
        assertConfined(
            PrivilegedMfaStanding.ATTESTATION_REQUIRED,
            "privileged_mfa_attestation_required",
            "A passkey must be approved before this privileged account can continue");
    }

    @Test
    void firewallRejectedForwardedHeaderStillRejectsPreAuthenticationAdmission() throws Exception {
        AuditService auditService = mock(AuditService.class);
        StandingFixture fixture = standingFixture("true", PrivilegedMfaStanding.ENROLLMENT_REQUIRED,
            RouteTransactionMode.READ, auditService, new ClientIpResolver("10.0.0.1"));
        MockHttpServletRequest request = authenticatedRequest("GET");
        request.setRemoteAddr("10.0.0.1");
        request.addHeader("X-Forwarded-For", "198.51.100.19\u0085");
        var firewalled = new StrictHttpFirewall().getFirewalledRequest(request);
        MockFilterChain chain = new MockFilterChain();

        assertThrows(RequestRejectedException.class, () ->
            fixture.filter().doFilter(firewalled, new MockHttpServletResponse(), chain));

        verifyNoInteractions(fixture.limiter(), fixture.privilegedAccountService(), auditService);
        assertNull(chain.getRequest());
        assertFalse(fixture.tenantContext().isResolved());
    }

    @ParameterizedTest
    @EnumSource(RouteTransactionMode.class)
    void denialAuditRunsAfterTheReadTransactionAndTenantBindingEnd(RouteTransactionMode mode)
            throws Exception {
        AuditService auditService = mock(AuditService.class);
        StandingFixture fixture = standingFixture(
            "true", PrivilegedMfaStanding.ENROLLMENT_REQUIRED, mode, auditService);
        MockHttpServletRequest request = authenticatedRequest("GET");
        doAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertFalse(fixture.tenantContext().isResolved());
            assertNull(fixture.tenantContext().getCatalog());
            assertEquals(fixture.binding().userId(),
                ((User) SecurityContextHolder.getContext()
                    .getAuthentication().getPrincipal()).getId());
            return null;
        }).when(auditService).recordStrictFailureIndependentScoped(
            "auth.mfa.policy.denied", "user", fixture.binding().userId(), null, null, null,
            "Public API privileged account confined pending MFA enrollment", "enrollment_required", request, "192.0.2.10");
        MockHttpServletResponse response = new MockHttpServletResponse();

        fixture.filter().doFilter(request, response, new MockFilterChain());

        assertEquals(401, response.getStatus());
        verify(auditService).recordStrictFailureIndependentScoped(
            "auth.mfa.policy.denied", "user", fixture.binding().userId(), null, null, null,
            "Public API privileged account confined pending MFA enrollment", "enrollment_required", request, "192.0.2.10");
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @ParameterizedTest
    @EnumSource(value = PrivilegedMfaStanding.class, names = {"ENROLLMENT_REQUIRED", "ATTESTATION_REQUIRED"})
    void userAgentDifferentiationAuditsTwiceAndPreservesEveryPublicRefusal(PrivilegedMfaStanding standing)
            throws Exception {
        AuditService auditService = mock(AuditService.class);
        StandingFixture fixture = standingFixture("true", standing, RouteTransactionMode.READ, auditService);
        String reason = standing == PrivilegedMfaStanding.ENROLLMENT_REQUIRED
            ? "enrollment_required" : "attestation_required";
        for (String agent : List.of("Browser A", "Browser B", "Browser A", "Browser B")) {
            MockHttpServletRequest request = authenticatedRequest("GET");
            request.addHeader(HttpHeaders.USER_AGENT, agent);
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            fixture.filter().doFilter(request, response, chain);

            assertEquals(401, response.getStatus());
            assertEquals("privileged_mfa_" + reason,
                new ObjectMapper().readTree(response.getContentAsByteArray())
                    .path("error").path("code").textValue());
            assertNull(chain.getRequest());
            assertFalse(fixture.tenantContext().isResolved());
            assertNull(SecurityContextHolder.getContext().getAuthentication());
        }

        verify(auditService, times(2)).recordStrictFailureIndependentScoped(
            eq("auth.mfa.policy.denied"), eq("user"), eq(fixture.binding().userId()), isNull(), isNull(), isNull(),
            any(), eq(reason), any(), eq("192.0.2.10"));
    }

    @Test
    void rejectedUserAgentsDoNotThrowOrOpenSeparatePublicDenialAdmissions() throws Exception {
        AuditService auditService = mock(AuditService.class);
        StandingFixture fixture = standingFixture("true", PrivilegedMfaStanding.ENROLLMENT_REQUIRED,
            RouteTransactionMode.READ, auditService);
        for (String agent : List.of("Browser A\u0085", "Browser B\u0085", "Browser A")) {
            MockHttpServletRequest request = authenticatedRequest("GET");
            request.addHeader(HttpHeaders.USER_AGENT, agent);
            var firewalled = new StrictHttpFirewall().getFirewalledRequest(request);
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            fixture.filter().doFilter(firewalled, response, chain);

            assertEquals(401, response.getStatus());
            assertEquals("privileged_mfa_enrollment_required",
                new ObjectMapper().readTree(response.getContentAsByteArray())
                    .path("error").path("code").textValue());
            assertNull(chain.getRequest());
            assertFalse(fixture.tenantContext().isResolved());
        }
        MockHttpServletResponse absent = new MockHttpServletResponse();
        fixture.filter().doFilter(authenticatedRequest("GET"), absent, new MockFilterChain());
        assertEquals(401, absent.getStatus());
        verify(auditService, times(2)).recordStrictFailureIndependentScoped(
            eq("auth.mfa.policy.denied"), eq("user"), eq(fixture.binding().userId()), isNull(), isNull(), isNull(),
            any(), eq("enrollment_required"), any(), eq("192.0.2.10"));
    }

    @Test
    void unprivilegedAndSatisfiedAccountsPassTheEnforcedGate() throws Exception {
        for (PrivilegedMfaStanding standing : List.of(
                PrivilegedMfaStanding.UNPRIVILEGED, PrivilegedMfaStanding.SATISFIED)) {
            StandingFixture fixture = standingFixture("true", standing);
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            fixture.filter().doFilter(authenticatedRequest("GET"), response, chain);

            assertEquals(200, response.getStatus(), standing.name());
            assertNotNull(chain.getRequest(), standing.name());
            verify(fixture.limiter()).acquire(fixture.binding().credentialId());
        }
    }

    @Test
    void unenforcedGateNeverReadsStanding() throws Exception {
        StandingFixture fixture = standingFixture("false", PrivilegedMfaStanding.ENROLLMENT_REQUIRED);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        fixture.filter().doFilter(authenticatedRequest("GET"), response, chain);

        assertEquals(200, response.getStatus());
        assertNotNull(chain.getRequest());
        verify(fixture.privilegedAccountService(), never()).enforcedStanding(anyInt());
    }

    @Test
    void deScopedCredentialIsRefusedWithInsufficientScopeBeforeDispatch() throws Exception {
        String token = token();
        RoutingBinding binding = new RoutingBinding(7, 11, 13, 17, "a".repeat(64));
        User user = new User();
        user.setId(binding.userId());
        ApiCredentialPrincipal principal = new ApiCredentialPrincipal(
            binding.credentialId(),
            binding.userId(),
            binding.workspaceId(),
            binding.organizationId(),
            "No live scopes",
            Set.of(ApiScope.CRM_READ),
            Set.of(),
            LocalDateTime.now().plusDays(1));
        ApiCredentialService service = mock(ApiCredentialService.class);
        ApiRateLimiter limiter = mock(ApiRateLimiter.class);
        ClientIpResolver clientIpResolver = mock(ClientIpResolver.class);
        TenantCatalogResolver tenantCatalogResolver = mock(TenantCatalogResolver.class);
        PrivilegedMfaProperties mfaProperties = new PrivilegedMfaProperties();
        mfaProperties.setEnforced("false");
        when(clientIpResolver.resolve(org.mockito.ArgumentMatchers.any()))
            .thenReturn("192.0.2.9");
        when(limiter.acquireBeforeAuthentication("192.0.2.9", token))
            .thenReturn(new ApiRateLimiter.Decision(true, 10, 9, 60, 0));
        when(service.resolveRoutingBinding(token)).thenReturn(Optional.of(binding));
        when(tenantCatalogResolver.resolveCatalog(binding.organizationId()))
            .thenReturn("connexdb");
        when(service.authenticate(binding))
            .thenReturn(Optional.of(new AuthenticatedCredential(user, principal)));
        when(limiter.acquire(binding.credentialId()))
            .thenReturn(new ApiRateLimiter.Decision(true, 10, 9, 60, 0));
        ApiCredentialAuthenticationFilter filter = new ApiCredentialAuthenticationFilter(
            service,
            limiter,
            new ObjectMapper(),
            mfaProperties,
            mock(PrivilegedAccountService.class),
            mock(AuditService.class),
            mock(DenialAuditRateLimiter.class),
            clientIpResolver,
            tenantCatalogResolver,
            new TenantContext(),
            request -> RouteTransactionMode.READ,
            new TestTransactionManager());
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(authenticatedRequest("GET"), response, chain);

        JsonNode body = new ObjectMapper().readTree(response.getContentAsByteArray());
        assertEquals(403, response.getStatus());
        assertEquals("insufficient_scope", body.path("error").path("code").textValue());
        assertEquals(
            "The credential cannot access this resource",
            body.path("error").path("message").textValue());
        assertNull(chain.getRequest());
        verify(limiter).acquire(binding.credentialId());
        assertEquals("10", response.getHeader("X-RateLimit-Limit"));
        verify(service, never()).recordSuccessfulUse(
            binding.credentialId(), binding.presentedHash());
        assertFalse(response.containsHeader(HttpHeaders.SET_COOKIE));
    }

    @Test
    void privilegedLookupFailureUsesGenericPublicErrorEnvelope() throws Exception {
        String token = token();
        RoutingBinding binding = new RoutingBinding(7, 11, 13, 17, "a".repeat(64));
        User user = new User();
        user.setId(binding.userId());
        ApiCredentialPrincipal principal = new ApiCredentialPrincipal(
            binding.credentialId(),
            binding.userId(),
            binding.workspaceId(),
            binding.organizationId(),
            "Lookup failure",
            Set.of(ApiScope.CRM_READ),
            Set.of(ApiScope.CRM_READ),
            LocalDateTime.now().plusDays(1));
        ApiCredentialService service = mock(ApiCredentialService.class);
        ApiRateLimiter limiter = mock(ApiRateLimiter.class);
        ClientIpResolver clientIpResolver = mock(ClientIpResolver.class);
        TenantCatalogResolver tenantCatalogResolver = mock(TenantCatalogResolver.class);
        PrivilegedAccountService privilegedAccountService = mock(PrivilegedAccountService.class);
        PrivilegedMfaProperties mfaProperties = new PrivilegedMfaProperties();
        mfaProperties.setEnforced("true");
        when(clientIpResolver.resolve(org.mockito.ArgumentMatchers.any()))
            .thenReturn("192.0.2.10");
        when(limiter.acquireBeforeAuthentication("192.0.2.10", token))
            .thenReturn(new ApiRateLimiter.Decision(true, 10, 9, 60, 0));
        when(service.resolveRoutingBinding(token)).thenReturn(Optional.of(binding));
        when(tenantCatalogResolver.resolveCatalog(binding.organizationId()))
            .thenReturn("connexdb");
        when(service.authenticate(binding))
            .thenReturn(Optional.of(new AuthenticatedCredential(user, principal)));
        when(privilegedAccountService.enforcedStanding(binding.userId()))
            .thenThrow(new TransientDataAccessResourceException("database detail must not escape"));
        ObjectMapper objectMapper = new ObjectMapper();
        ApiCredentialAuthenticationFilter filter = new ApiCredentialAuthenticationFilter(
            service,
            limiter,
            objectMapper,
            mfaProperties,
            privilegedAccountService,
            mock(AuditService.class),
            mock(DenialAuditRateLimiter.class),
            clientIpResolver,
            tenantCatalogResolver,
            new TenantContext(),
            request -> RouteTransactionMode.READ,
            new TestTransactionManager());
        MockHttpServletRequest request = authenticatedRequest("GET");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        JsonNode body = objectMapper.readTree(response.getContentAsByteArray());
        assertEquals(500, response.getStatus());
        assertEquals("internal_error", body.path("error").path("code").textValue());
        assertEquals("An unexpected error occurred", body.path("error").path("message").textValue());
        assertFalse(response.getContentAsString().contains("database detail"));
        assertFalse(response.containsHeader(HttpHeaders.WWW_AUTHENTICATE));
        verify(limiter, never()).acquire(binding.credentialId());
        assertNull(chain.getRequest());
    }

    @Test
    void transactionInitializationFailureUsesGenericPublicErrorEnvelope() throws Exception {
        ApiCredentialAuthenticationFilter filter = authenticatedFilter(
            RouteTransactionMode.READ, new FailingTransactionTemplate());
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        ObjectMapper objectMapper = new ObjectMapper();

        filter.doFilter(authenticatedRequest("GET"), response, chain);

        JsonNode body = objectMapper.readTree(response.getContentAsByteArray());
        assertEquals(500, response.getStatus());
        assertEquals("internal_error", body.path("error").path("code").textValue());
        assertEquals("An unexpected error occurred", body.path("error").path("message").textValue());
        assertFalse(response.getContentAsString().contains("transaction initialization detail"));
        assertFalse(response.containsHeader(HttpHeaders.WWW_AUTHENTICATE));
        assertNull(chain.getRequest());
    }

    @Test
    void uncommittedOutputStreamResponseIsResetWhenTransactionCommitFails() throws Exception {
        ApiCredentialAuthenticationFilter filter = authenticatedFilter(
            RouteTransactionMode.READ, new TestTransactionManager(true));
        MockHttpServletResponse response = new MockHttpServletResponse();
        ObjectMapper objectMapper = new ObjectMapper();

        filter.doFilter(authenticatedRequest("GET"), response, (request, servletResponse) -> {
            response.setStatus(200);
            response.setHeader("Content-Security-Policy", "preserved-before-failure");
            response.setHeader(HttpHeaders.CONTENT_LENGTH, "4096");
            response.setHeader(HttpHeaders.CONTENT_ENCODING, "gzip");
            response.setHeader("Strict-Transport-Security", "max-age=123");
            response.setHeader(HttpHeaders.RETRY_AFTER, "17");
            response.getOutputStream().write("partial-success".getBytes(StandardCharsets.UTF_8));
        });

        JsonNode body = objectMapper.readTree(response.getContentAsByteArray());
        assertEquals(500, response.getStatus());
        assertEquals("internal_error", body.path("error").path("code").textValue());
        assertFalse(response.getContentAsString().contains("partial-success"));
        assertTrue(response.getHeader("Content-Security-Policy").contains("default-src 'none'"));
        assertEquals("no-referrer", response.getHeader("Referrer-Policy"));
        assertEquals("10", response.getHeader("X-RateLimit-Limit"));
        assertEquals("9", response.getHeader("X-RateLimit-Remaining"));
        assertEquals("60", response.getHeader("X-RateLimit-Reset"));
        assertEquals("max-age=123", response.getHeader("Strict-Transport-Security"));
        assertEquals("17", response.getHeader(HttpHeaders.RETRY_AFTER));
        assertNull(response.getHeader(HttpHeaders.CONTENT_LENGTH));
        assertNull(response.getHeader(HttpHeaders.CONTENT_ENCODING));
    }

    @Test
    void uncommittedWriterResponseIsResetWhenTransactionCommitFails() throws Exception {
        ApiCredentialAuthenticationFilter filter = authenticatedFilter(
            RouteTransactionMode.READ, new TestTransactionManager(true));
        MockHttpServletResponse response = new MockHttpServletResponse();
        ObjectMapper objectMapper = new ObjectMapper();

        filter.doFilter(authenticatedRequest("GET"), response, (request, servletResponse) -> {
            response.setStatus(200);
            response.setHeader(HttpHeaders.CONTENT_LENGTH, "4096");
            response.setHeader(HttpHeaders.CONTENT_ENCODING, "br");
            response.getWriter().write("partial-writer-success");
        });

        JsonNode body = objectMapper.readTree(response.getContentAsByteArray());
        assertEquals(500, response.getStatus());
        assertEquals("internal_error", body.path("error").path("code").textValue());
        assertFalse(response.getContentAsString().contains("partial-writer-success"));
        assertTrue(response.getHeader("Content-Security-Policy").contains("default-src 'none'"));
        assertEquals("no-referrer", response.getHeader("Referrer-Policy"));
        assertEquals("10", response.getHeader("X-RateLimit-Limit"));
        assertEquals("9", response.getHeader("X-RateLimit-Remaining"));
        assertEquals("60", response.getHeader("X-RateLimit-Reset"));
        assertNull(response.getHeader(HttpHeaders.CONTENT_LENGTH));
        assertNull(response.getHeader(HttpHeaders.CONTENT_ENCODING));
    }

    @Test
    void committedResponseIsUntouchedWhenTransactionCommitFails() throws Exception {
        ApiCredentialAuthenticationFilter filter = authenticatedFilter(
            RouteTransactionMode.READ, new TestTransactionManager(true));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MDC.put("correlationId", "request-test-123");

        List<ILoggingEvent> events = captureLogs(PublicApiErrorAdvice.class, () ->
            filter.doFilter(authenticatedRequest("GET"), response, (request, servletResponse) -> {
                response.setStatus(200);
                response.setContentType("application/json");
                response.getOutputStream().write("{\"ok\":true}".getBytes(StandardCharsets.UTF_8));
                response.flushBuffer();
            }));

        assertTrue(response.isCommitted());
        assertEquals(200, response.getStatus());
        assertEquals("{\"ok\":true}", response.getContentAsString());
        assertEquals("application/json", response.getContentType());
        assertEquals(1, events.size());
        assertTrue(events.getFirst().getFormattedMessage().contains("credentialId=7"));
        assertTrue(events.getFirst().getFormattedMessage().contains("requestId=request-test-123"));
        assertNull(events.getFirst().getThrowableProxy());
    }

    private ApiCredentialAuthenticationFilter authenticatedFilter(
            RouteTransactionMode transactionMode,
            TestTransactionManager transactionManager) {
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        transactionTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        transactionTemplate.setReadOnly(true);
        return authenticatedFilter(transactionMode, transactionTemplate);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void lockedConfinementPropagatingOutOfDispatchKeepsUnauthorizedEnvelope(boolean wrapped) throws Exception {
        for (boolean attestation : new boolean[] {false, true}) {
            var filter = authenticatedFilter(RouteTransactionMode.WRITE, new TestTransactionManager(false));
            MockHttpServletResponse response = new MockHttpServletResponse();
            RuntimeException refusal = attestation ? new PrivilegedMfaAttestationRequiredException()
                : new PrivilegedMfaEnrollmentRequiredException();
            filter.doFilter(authenticatedRequest("POST"), response, (request, servletResponse) -> {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                if (wrapped) {
                    throw new ServletException(refusal);
                }
                throw refusal;
            });
            JsonNode error = new ObjectMapper().readTree(response.getContentAsByteArray()).path("error");
            assertEquals(401, response.getStatus());
            assertEquals(attestation ? "privileged_mfa_attestation_required" : "privileged_mfa_enrollment_required",
                error.path("code").stringValue());
            assertEquals(refusal.getMessage(), error.path("message").stringValue());
            assertTrue(error.has("request_id"));
        }
    }

    private ApiCredentialAuthenticationFilter authenticatedFilter(
            RouteTransactionMode transactionMode,
            TransactionTemplate transactionTemplate) {
        String token = token();
        RoutingBinding binding = new RoutingBinding(7, 11, 13, 17, "a".repeat(64));
        User user = new User();
        user.setId(binding.userId());
        ApiCredentialPrincipal principal = new ApiCredentialPrincipal(
            binding.credentialId(),
            binding.userId(),
            binding.workspaceId(),
            binding.organizationId(),
            "Transaction boundary",
            Set.of(ApiScope.CRM_READ),
            Set.of(ApiScope.CRM_READ),
            LocalDateTime.now().plusDays(1));
        ApiCredentialService service = mock(ApiCredentialService.class);
        ApiRateLimiter limiter = mock(ApiRateLimiter.class);
        ClientIpResolver clientIpResolver = mock(ClientIpResolver.class);
        TenantCatalogResolver tenantCatalogResolver = mock(TenantCatalogResolver.class);
        PrivilegedMfaProperties mfaProperties = new PrivilegedMfaProperties();
        mfaProperties.setEnforced("false");
        when(clientIpResolver.resolve(org.mockito.ArgumentMatchers.any()))
            .thenReturn("192.0.2.9");
        when(limiter.acquireBeforeAuthentication("192.0.2.9", token))
            .thenReturn(new ApiRateLimiter.Decision(true, 10, 9, 60, 0));
        when(service.resolveRoutingBinding(token)).thenReturn(Optional.of(binding));
        when(tenantCatalogResolver.resolveCatalog(binding.organizationId()))
            .thenReturn("connexdb");
        when(service.authenticate(binding)).thenAnswer(invocation -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            return Optional.of(new AuthenticatedCredential(user, principal));
        });
        when(limiter.acquire(binding.credentialId()))
            .thenReturn(new ApiRateLimiter.Decision(true, 10, 9, 60, 0));

        return new ApiCredentialAuthenticationFilter(
            service,
            limiter,
            new ObjectMapper(),
            mfaProperties,
            mock(PrivilegedAccountService.class),
            mock(AuditService.class),
            mock(DenialAuditRateLimiter.class),
            clientIpResolver,
            tenantCatalogResolver,
            new TenantContext(),
            request -> transactionMode,
            transactionTemplate);
    }

    private static void assertConfined(
            PrivilegedMfaStanding standing,
            String code,
            String message) throws Exception {
        StandingFixture fixture = standingFixture("true", standing);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        fixture.filter().doFilter(authenticatedRequest("GET"), response, chain);

        JsonNode body = new ObjectMapper().readTree(response.getContentAsByteArray());
        assertEquals(401, response.getStatus());
        assertEquals(code, body.path("error").path("code").textValue());
        assertEquals(message, body.path("error").path("message").textValue());
        assertTrue(body.path("error").path("request_id").isTextual());
        assertFalse(response.containsHeader(HttpHeaders.SET_COOKIE));
        verify(fixture.limiter(), never()).acquire(fixture.binding().credentialId());
        assertNull(chain.getRequest());
    }

    private static StandingFixture standingFixture(String enforced, PrivilegedMfaStanding standing) {
        return standingFixture(enforced, standing, RouteTransactionMode.READ, mock(AuditService.class));
    }

    private static StandingFixture standingFixture(String enforced, PrivilegedMfaStanding standing,
            RouteTransactionMode transactionMode, AuditService auditService) {
        ClientIpResolver clientIpResolver = mock(ClientIpResolver.class);
        when(clientIpResolver.resolve(org.mockito.ArgumentMatchers.any()))
            .thenReturn("192.0.2.10");
        when(clientIpResolver.resolveForDenialAudit(org.mockito.ArgumentMatchers.any()))
            .thenReturn("192.0.2.10");
        return standingFixture(enforced, standing, transactionMode, auditService, clientIpResolver);
    }

    private static StandingFixture standingFixture(String enforced, PrivilegedMfaStanding standing,
            RouteTransactionMode transactionMode, AuditService auditService, ClientIpResolver clientIpResolver) {
        String token = token();
        RoutingBinding binding = new RoutingBinding(7, 11, 13, 17, "a".repeat(64));
        User user = new User();
        user.setId(binding.userId());
        ApiCredentialPrincipal principal = new ApiCredentialPrincipal(
            binding.credentialId(),
            binding.userId(),
            binding.workspaceId(),
            binding.organizationId(),
            "Privileged standing",
            Set.of(ApiScope.CRM_READ),
            Set.of(ApiScope.CRM_READ),
            LocalDateTime.now().plusDays(1));
        ApiCredentialService service = mock(ApiCredentialService.class);
        ApiRateLimiter limiter = mock(ApiRateLimiter.class);
        TenantCatalogResolver tenantCatalogResolver = mock(TenantCatalogResolver.class);
        PrivilegedAccountService privilegedAccountService = mock(PrivilegedAccountService.class);
        PrivilegedMfaProperties mfaProperties = new PrivilegedMfaProperties();
        mfaProperties.setEnforced(enforced);
        when(limiter.acquireBeforeAuthentication("192.0.2.10", token))
            .thenReturn(new ApiRateLimiter.Decision(true, 10, 9, 60, 0));
        when(service.resolveRoutingBinding(token)).thenReturn(Optional.of(binding));
        when(tenantCatalogResolver.resolveCatalog(binding.organizationId()))
            .thenReturn("connexdb");
        when(service.authenticate(binding))
            .thenReturn(Optional.of(new AuthenticatedCredential(user, principal)));
        when(privilegedAccountService.enforcedStanding(binding.userId())).thenReturn(standing);
        when(limiter.acquire(binding.credentialId()))
            .thenReturn(new ApiRateLimiter.Decision(true, 10, 9, 60, 0));
        TenantContext tenantContext = new TenantContext();
        ApiCredentialAuthenticationFilter filter = new ApiCredentialAuthenticationFilter(
            service,
            limiter,
            new ObjectMapper(),
            mfaProperties,
            privilegedAccountService,
            auditService,
            new DenialAuditRateLimiter(3600, Clock.systemUTC(), new SimpleMeterRegistry()),
            clientIpResolver,
            tenantCatalogResolver,
            tenantContext,
            request -> transactionMode,
            new TestTransactionManager());
        return new StandingFixture(filter, limiter, privilegedAccountService, binding, tenantContext);
    }

    private static MockHttpServletRequest authenticatedRequest(String method) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/api/v1/me");
        request.setRemoteAddr("192.0.2.9");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token());
        return request;
    }

    private static String token() {
        return "cnx_pat_" + "a".repeat(39) + "last";
    }

    private static void assertInternalErrorEnvelope(
            ObjectMapper objectMapper,
            MockHttpServletResponse response,
            String privateDetail) throws Exception {
        JsonNode body = objectMapper.readTree(response.getContentAsByteArray());
        assertEquals(500, response.getStatus());
        assertEquals("internal_error", body.path("error").path("code").textValue());
        assertEquals("An unexpected error occurred", body.path("error").path("message").textValue());
        assertFalse(response.getContentAsString().contains(privateDetail));
        assertEquals("no-referrer", response.getHeader("Referrer-Policy"));
        assertEquals("no-store", response.getHeader(HttpHeaders.CACHE_CONTROL));
    }

    private static List<ILoggingEvent> captureLogs(
            Class<?> loggerType,
            ThrowingAction action) throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(loggerType);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            action.run();
            return List.copyOf(appender.list);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private static final class TestTransactionManager extends AbstractPlatformTransactionManager {
        private final ThreadLocal<TestTransaction> current = new ThreadLocal<>();
        private final boolean failCommit;
        private int beginCount;
        private int commitCount;

        TestTransactionManager() {
            this(false);
        }

        TestTransactionManager(boolean failCommit) {
            this.failCommit = failCommit;
        }

        int beginCount() {
            return beginCount;
        }

        int commitCount() {
            return commitCount;
        }

        @Override
        protected Object doGetTransaction() {
            TestTransaction transaction = current.get();
            return transaction == null ? new TestTransaction() : transaction;
        }

        @Override
        protected boolean isExistingTransaction(Object transaction) {
            return ((TestTransaction) transaction).active;
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            assertTrue(definition.isReadOnly());
            assertEquals(
                TransactionDefinition.ISOLATION_REPEATABLE_READ,
                definition.getIsolationLevel());
            TestTransaction active = (TestTransaction) transaction;
            active.active = true;
            current.set(active);
            beginCount++;
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            commitCount++;
            if (failCommit) {
                throw new TransactionSystemException("transaction commit detail");
            }
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }

        @Override
        protected void doCleanupAfterCompletion(Object transaction) {
            ((TestTransaction) transaction).active = false;
            current.remove();
        }
    }

    private static final class TestTransaction {
        private boolean active;
    }

    private static final class FailingTransactionTemplate extends TransactionTemplate {
        @Override
        public <T> T execute(TransactionCallback<T> action) {
            throw new CannotCreateTransactionException("transaction initialization detail");
        }
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }

    private record StandingFixture(
            ApiCredentialAuthenticationFilter filter,
            ApiRateLimiter limiter,
            PrivilegedAccountService privilegedAccountService,
            RoutingBinding binding,
            TenantContext tenantContext) {
    }
}
