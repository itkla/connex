package ooo.klae.connex.backend.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.logging.logback.StructuredLogEncoder;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.observability.CorrelationIds;
import ooo.klae.connex.backend.services.WorkspaceService;

class TenantResolutionJournalTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String CORRELATION_ID = "request_ID-1234";

    private final WorkspaceService workspaceService = mock(WorkspaceService.class);
    private final TenantCatalogResolver catalogResolver = mock(TenantCatalogResolver.class);
    private final WorkspaceRequestResolver requestResolver = mock(WorkspaceRequestResolver.class);
    private final WorkspaceCookie workspaceCookie = mock(WorkspaceCookie.class);
    private final TenantContext tenantContext = new TenantContext();
    private final Logger logger = (Logger) LoggerFactory.getLogger(TenantResolutionInterceptor.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    private TenantResolutionInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new TenantResolutionInterceptor(
            workspaceService,
            tenantContext,
            catalogResolver,
            requestResolver,
            workspaceCookie);
        User user = new User();
        user.setId(7);
        Authentication authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn(user);
        SecurityContextHolder.getContext().setAuthentication(authentication);
        appender.setContext(logger.getLoggerContext());
        appender.start();
        logger.addAppender(appender);
        MDC.put(CorrelationIds.MDC_KEY, CORRELATION_ID);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        appender.stop();
        tenantContext.clear();
        MDC.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void resolvedAllowlistedHandlerEmitsProjectableEcsWithoutRawPathOrQuery() throws Exception {
        MockHttpServletRequest request = resolvedRequest();
        request.setAttribute(
            HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,
            "/api/persons/{id}/profile-picture/{token:.+}");
        request.setRequestURI("/api/persons/12/profile-picture/SENTINEL_PATH_SECRET");
        request.setQueryString("q=SENTINEL_QUERY_SECRET");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(404);
        HandlerMethod handler = handler(new AttributableHandler());

        interceptor.preHandle(request, response, handler);
        interceptor.afterCompletion(request, response, handler, null);

        assertEquals(1, appender.list.size());
        ILoggingEvent event = appender.list.getFirst();
        Map<String, Object> fields = event.getKeyValuePairs().stream()
            .collect(Collectors.toMap(pair -> pair.key, pair -> pair.value));
        assertEquals(3, fields.get("connexOrganizationId"));
        assertEquals("GET", fields.get("requestMethod"));
        assertEquals("/api/persons/{id}/profile-picture/{token:.+}", fields.get("requestPath"));
        assertEquals(404, fields.get("responseStatus"));
        assertEquals(TenantResolutionInterceptor.JOURNAL_EVENT_CLASS, fields.get("eventClass"));
        assertFalse(event.getFormattedMessage().contains("SENTINEL"));
        assertEquals(CORRELATION_ID, event.getMDCPropertyMap().get(CorrelationIds.MDC_KEY));
        assertFalse(tenantContext.isResolved());

        JsonNode ecs = encodeEcs(event);
        assertEquals(3, ecs.path("connexOrganizationId").intValue());
        assertEquals("GET", ecs.path("requestMethod").textValue());
        assertEquals("/api/persons/{id}/profile-picture/{token:.+}", ecs.path("requestPath").textValue());
        assertEquals(404, ecs.path("responseStatus").intValue());
        assertEquals(TenantResolutionInterceptor.JOURNAL_EVENT_CLASS, ecs.path("eventClass").textValue());
        assertEquals(CORRELATION_ID, ecs.path("correlationId").textValue());
        assertEquals("INFO", ecs.path("log").path("level").textValue());
        assertEquals(TenantResolutionInterceptor.class.getName(), ecs.path("log").path("logger").textValue());
        assertFalse(ecs.toString().contains("SENTINEL"));
    }

    @Test
    void unresolvedRequestDropsAStaleOrganizationAttributeAndEmitsNothing() throws Exception {
        MockHttpServletRequest request = request();
        request.setAttribute(TenantResolutionInterceptor.ORGANIZATION_ID_ATTRIBUTE, 999);
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/persons/{id}");
        when(requestResolver.resolve(any(), eq(7))).thenReturn(null);
        HandlerMethod handler = handler(new AttributableHandler());

        interceptor.preHandle(request, new MockHttpServletResponse(), handler);
        interceptor.afterCompletion(request, new MockHttpServletResponse(), handler, null);

        assertNull(request.getAttribute(TenantResolutionInterceptor.ORGANIZATION_ID_ATTRIBUTE));
        assertEquals(0, appender.list.size());
    }

    @Test
    void resolvedUnmarkedHandlerEmitsNothing() throws Exception {
        MockHttpServletRequest request = resolvedRequest();
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/orgs/{orgId}/support-bundle");
        MockHttpServletResponse response = new MockHttpServletResponse();
        HandlerMethod handler = handler(new UnmarkedHandler());

        interceptor.preHandle(request, response, handler);
        interceptor.afterCompletion(request, response, handler, null);

        assertEquals(0, appender.list.size());
        assertFalse(tenantContext.isResolved());
    }

    @Test
    void asyncRedispatchToAnotherOrganizationEmitsNothing() throws Exception {
        MockHttpServletRequest request = resolvedRequest();
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/deals/{id}");
        MockHttpServletResponse response = new MockHttpServletResponse();
        HandlerMethod handler = handler(new AttributableHandler());
        when(requestResolver.resolve(request, 7)).thenReturn(11, 12);
        when(workspaceService.getRole(12, 7)).thenReturn("owner");
        when(workspaceService.getOrgId(12)).thenReturn(4);
        when(catalogResolver.resolveCatalog(4)).thenReturn(null);

        interceptor.preHandle(request, response, handler);
        interceptor.afterConcurrentHandlingStarted(request, response, handler);

        assertEquals(0, appender.list.size());
        assertFalse(tenantContext.isResolved());

        interceptor.preHandle(request, response, handler);
        assertEquals(4, request.getAttribute(TenantResolutionInterceptor.ORGANIZATION_ID_ATTRIBUTE));
        interceptor.afterCompletion(request, response, handler, null);

        assertEquals(0, appender.list.size());
        assertFalse(tenantContext.isResolved());
    }

    private MockHttpServletRequest resolvedRequest() {
        MockHttpServletRequest request = request();
        when(requestResolver.resolve(request, 7)).thenReturn(11);
        when(workspaceService.getRole(11, 7)).thenReturn("owner");
        when(workspaceService.getOrgId(11)).thenReturn(3);
        when(catalogResolver.resolveCatalog(3)).thenReturn(null);
        return request;
    }

    private static MockHttpServletRequest request() {
        return new MockHttpServletRequest("GET", "/api/persons/12");
    }

    private static HandlerMethod handler(Object target) throws NoSuchMethodException {
        Method method = target.getClass().getDeclaredMethod("handle");
        return new HandlerMethod(target, method);
    }

    private static JsonNode encodeEcs(ILoggingEvent event) throws Exception {
        LoggerContext context = new LoggerContext();
        context.putObject(Environment.class.getName(), new MockEnvironment());
        StructuredLogEncoder encoder = new StructuredLogEncoder();
        encoder.setContext(context);
        encoder.setFormat("ecs");
        encoder.start();
        try {
            return OBJECT_MAPPER.readTree(new String(encoder.encode(event), StandardCharsets.UTF_8));
        } finally {
            encoder.stop();
            context.stop();
        }
    }

    @TenantJournalAttributable
    private static final class AttributableHandler {
        public void handle() {
        }
    }

    private static final class UnmarkedHandler {
        public void handle() {
        }
    }
}
