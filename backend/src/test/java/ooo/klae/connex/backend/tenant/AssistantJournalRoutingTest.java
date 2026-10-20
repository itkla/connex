package ooo.klae.connex.backend.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

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
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.HandlerMapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import ooo.klae.connex.backend.ai.assistant.AiAssistantToolCallReadService;
import ooo.klae.connex.backend.ai.assistant.AiAssistantTurnService;
import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteToolService;
import ooo.klae.connex.backend.ai.assistant.AiBriefScheduleService;
import ooo.klae.connex.backend.ai.assistant.AiChatAttachmentService;
import ooo.klae.connex.backend.ai.assistant.AiCommandCenterService;
import ooo.klae.connex.backend.ai.assistant.AiSkillDirectoryService;
import ooo.klae.connex.backend.ai.assistant.AiWatchService;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.config.AuditIntegrityProperties;
import ooo.klae.connex.backend.controllers.AiAssistantController;
import ooo.klae.connex.backend.controllers.AiAssistantProactiveController;
import ooo.klae.connex.backend.controllers.AiAssistantSkillController;
import ooo.klae.connex.backend.dto.AiChatSessionDto;
import ooo.klae.connex.backend.exceptions.GlobalExceptionHandler;
import ooo.klae.connex.backend.observability.ClientAssertedCorrelationPseudonymizer;
import ooo.klae.connex.backend.observability.CorrelationIds;
import ooo.klae.connex.backend.observability.ErrorReporter;
import ooo.klae.connex.backend.services.AiAssistantService;
import ooo.klae.connex.backend.services.WorkspaceService;

/**
 * Drives the assistant surface through a real {@code RequestMappingHandlerMapping} so the journal
 * record carries the template the shipped {@code @RequestMapping} metadata actually produces.
 *
 * <p>A hand-set {@code BEST_MATCHING_PATTERN_ATTRIBUTE} would only prove a test constant equals
 * itself and would keep passing after a mapping rename, so every request here is routed.
 *
 * <p>A path segment's content is irrelevant to the journal, because the record carries the Spring
 * mapping template and never {@code getRequestURI()} or {@code getQueryString()} — and the
 * proactive controller's {@code /watches/{id}} is in fact unconstrained, so a digit constraint is
 * not the reason this is safe. The sentinels therefore live in the two channels that could leak:
 * the query string and the request body. Every sentinel assertion inspects the encoded ECS
 * document, because the journal logs a constant message and puts every value in a key-value pair,
 * so a check against the formatted message alone could never fail.
 */
class AssistantJournalRoutingTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String CORRELATION_ID = "request_ID-1234";
    private static final String QUERY_SENTINEL = "SENTINEL_QUERY_SECRET";
    private static final String BODY_SENTINEL = "SENTINEL_BODY_SECRET";

    private final WorkspaceService workspaceService = mock(WorkspaceService.class);
    private final TenantCatalogResolver catalogResolver = mock(TenantCatalogResolver.class);
    private final WorkspaceRequestResolver requestResolver = mock(WorkspaceRequestResolver.class);
    private final WorkspaceCookie workspaceCookie = mock(WorkspaceCookie.class);
    private final TenantContext tenantContext = new TenantContext();
    private final AiAssistantService assistantService = mock(AiAssistantService.class);
    private final AiAssistantTurnService turnService = mock(AiAssistantTurnService.class);
    private final AiAssistantToolCallReadService toolCallReadService =
        mock(AiAssistantToolCallReadService.class);
    private final AiAssistantWriteToolService writeToolService =
        mock(AiAssistantWriteToolService.class);
    private final AiChatAttachmentService attachmentService = mock(AiChatAttachmentService.class);
    private final AiBriefScheduleService briefScheduleService = mock(AiBriefScheduleService.class);
    private final AiWatchService watchService = mock(AiWatchService.class);
    private final AiCommandCenterService commandCenterService = mock(AiCommandCenterService.class);
    private final AiSkillDirectoryService skillDirectoryService =
        mock(AiSkillDirectoryService.class);
    private final ErrorReporter errorReporter = mock(ErrorReporter.class);
    private final Logger logger = (Logger) LoggerFactory.getLogger(TenantResolutionInterceptor.class);
    private final ListAppender<ILoggingEvent> appender = new FreezingListAppender();

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        AuditIntegrityProperties integrityProperties = new AuditIntegrityProperties();
        integrityProperties.setHmacSecret("assistant-journal-test-secret-at-least-32-bytes");
        ClientAssertedCorrelationPseudonymizer correlationPseudonymizer =
            new ClientAssertedCorrelationPseudonymizer(integrityProperties);
        TenantResolutionInterceptor interceptor = new TenantResolutionInterceptor(
            workspaceService,
            tenantContext,
            catalogResolver,
            requestResolver,
            workspaceCookie,
            correlationPseudonymizer);
        when(requestResolver.resolve(any(), eq(7))).thenReturn(11);
        when(workspaceService.getRole(11, 7)).thenReturn("owner");
        when(workspaceService.getOrgId(11)).thenReturn(3);
        when(catalogResolver.resolveCatalog(3)).thenReturn(null);
        AiChatSessionDto created = new AiChatSessionDto();
        created.setId(42);
        when(assistantService.create(any())).thenReturn(created);

        mockMvc = MockMvcBuilders.standaloneSetup(
                new AiAssistantController(
                    assistantService, turnService, toolCallReadService,
                    writeToolService, attachmentService),
                new AiAssistantProactiveController(
                    briefScheduleService, watchService, commandCenterService),
                new AiAssistantSkillController(skillDirectoryService))
            .setControllerAdvice(new GlobalExceptionHandler(errorReporter, tenantContext))
            .addInterceptors(interceptor)
            .build();

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
    void assistantTurnStartJournalsItsRealMappingTemplateWithoutSessionText() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/ai/assistant/sessions/42/turns?q=" + QUERY_SENTINEL)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"" + BODY_SENTINEL + "\"}"))
            .andReturn();

        assertEquals(202, result.getResponse().getStatus());
        String routedTemplate = (String) result.getRequest()
            .getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        assertNotNull(routedTemplate);

        assertEquals(1, appender.list.size());
        ILoggingEvent event = appender.list.getFirst();
        Map<String, Object> fields = fields(event);
        assertEquals(routedTemplate, fields.get("requestPath"));
        assertNotEquals(result.getRequest().getRequestURI(), fields.get("requestPath"));
        assertEquals("POST", fields.get("requestMethod"));
        assertEquals(3, fields.get("connexOrganizationId"));
        assertEquals(202, fields.get("responseStatus"));
        assertEquals(TenantResolutionInterceptor.JOURNAL_EVENT_CLASS, fields.get("eventClass"));

        JsonNode ecs = encodeEcs(event);
        assertEquals(routedTemplate, ecs.path("requestPath").textValue());
        assertFalse(ecs.toString().contains("SENTINEL"));
    }

    @Test
    void theQuerySentinelOccupiesTheRealQueryString() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/ai/assistant/skills?context=" + QUERY_SENTINEL))
            .andReturn();

        assertEquals("context=" + QUERY_SENTINEL, result.getRequest().getQueryString(),
            "MockMvc's param() populates the parameter map without setting a query string, so the "
                + "sentinel has to arrive in the URI or every leak assertion guards nothing");
    }

    @Test
    void assistantClientDrivenReadsAreQuietUntilTheyFail() throws Exception {
        assertQuietThenLoudOnFailure(
            get("/api/ai/assistant/sessions/42/turns/7"),
            () -> when(turnService.get(anyInt(), anyInt()))
                .thenThrow(new RuntimeException("boom")));
        assertQuietThenLoudOnFailure(
            post("/api/ai/assistant/sessions/scope-preview")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"" + BODY_SENTINEL + "\"}"),
            () -> when(turnService.previewScope(any()))
                .thenThrow(new RuntimeException("boom")));
        assertQuietThenLoudOnFailure(
            get("/api/ai/assistant/skills?context=" + QUERY_SENTINEL),
            () -> when(skillDirectoryService.list(any()))
                .thenThrow(new RuntimeException("boom")));
    }

    @Test
    void assistantReadsTheRealtimeReconnectReDrivesAreNeverJournaled() throws Exception {
        assertNeverJournaled(
            get("/api/ai/assistant/sessions"),
            () -> when(assistantService.page(anyInt(), anyInt()))
                .thenThrow(new RuntimeException("boom")));
        assertNeverJournaled(
            get("/api/ai/assistant/sessions/invitations"),
            () -> when(assistantService.pageInvitations(anyInt(), anyInt()))
                .thenThrow(new RuntimeException("boom")));
        assertNeverJournaled(
            get("/api/ai/assistant/sessions/42"),
            () -> when(assistantService.get(anyInt(), anyInt(), anyInt()))
                .thenThrow(new RuntimeException("boom")));
        assertNeverJournaled(
            get("/api/ai/assistant/sessions/42/attachments"),
            () -> when(attachmentService.list(anyInt()))
                .thenThrow(new RuntimeException("boom")));
        assertNeverJournaled(
            get("/api/ai/assistant/sessions/42/participants"),
            () -> when(assistantService.participants(anyInt()))
                .thenThrow(new RuntimeException("boom")));
        assertNeverJournaled(
            get("/api/ai/assistant/sessions/42/presence"),
            () -> when(assistantService.presence(anyInt()))
                .thenThrow(new RuntimeException("boom")));
        assertNeverJournaled(
            get("/api/ai/assistant/sessions/42/tool-calls"),
            () -> when(toolCallReadService.list(anyInt(), anyBoolean()))
                .thenThrow(new RuntimeException("boom")));
    }

    @Test
    void assistantPresenceHeartbeatIsNeverJournaled() throws Exception {
        assertNeverJournaled(
            put("/api/ai/assistant/sessions/42/presence")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"typing\":true}"),
            () -> when(assistantService.touchPresence(anyInt(), anyBoolean()))
                .thenThrow(new RuntimeException("boom")));
    }

    @Test
    void assistantMemberActionsAreJournaledOnSuccess() throws Exception {
        assertJournaledOnSuccess(201, post("/api/ai/assistant/sessions")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"Pipeline review\"}"));
        assertJournaledOnSuccess(201, post("/api/ai/assistant/sessions/42/messages")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"content\":\"" + BODY_SENTINEL + "\"}"));
        assertJournaledOnSuccess(204, post("/api/ai/assistant/sessions/42/turns/7/cancel"));
        assertJournaledOnSuccess(200, patch("/api/ai/assistant/sessions/42/sharing")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"shared\":true}"));
        assertJournaledOnSuccess(201, post("/api/ai/assistant/watches")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"watchType\":\"no_interaction\",\"subjectKind\":\"person\","
                + "\"subjectId\":1,\"thresholdDays\":7}"));
    }

    private void assertQuietThenLoudOnFailure(
            MockHttpServletRequestBuilder builder, Runnable failureStub) throws Exception {
        appender.list.clear();
        MvcResult success = mockMvc.perform(builder).andReturn();
        assertTrue(success.getResponse().getStatus() < 400,
            success.getRequest().getRequestURI() + " did not succeed");
        assertEquals(0, appender.list.size(),
            "a successful client-driven read was journaled: " + success.getRequest().getRequestURI());

        failureStub.run();
        MvcResult failure = mockMvc.perform(builder).andReturn();

        assertEquals(500, failure.getResponse().getStatus());
        assertEquals(1, appender.list.size(),
            "a failing client-driven read was not journaled: " + failure.getRequest().getRequestURI());
        Map<String, Object> failed = fields(appender.list.getFirst());
        assertEquals(500, failed.get("responseStatus"));
        assertEquals(
            failure.getRequest().getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE),
            failed.get("requestPath"));
        assertFalse(encodeEcs(appender.list.getFirst()).toString().contains("SENTINEL"),
            "a sentinel reached the encoded journal record for "
                + failure.getRequest().getRequestURI());
    }

    private void assertNeverJournaled(
            MockHttpServletRequestBuilder builder, Runnable failureStub) throws Exception {
        appender.list.clear();
        MvcResult success = mockMvc.perform(builder).andReturn();
        assertTrue(success.getResponse().getStatus() < 400,
            success.getRequest().getRequestURI() + " did not succeed");
        assertEquals(0, appender.list.size(),
            "a successful fully-silent read was journaled: " + success.getRequest().getRequestURI());

        failureStub.run();
        MvcResult failure = mockMvc.perform(builder).andReturn();

        assertEquals(500, failure.getResponse().getStatus());
        assertEquals(0, appender.list.size(),
            "a failing fully-silent read was journaled, so a client that re-issues it forever can "
                + "fill the support bundle's record cap: " + failure.getRequest().getRequestURI());
    }

    private void assertJournaledOnSuccess(int expectedStatus, MockHttpServletRequestBuilder builder)
            throws Exception {
        appender.list.clear();
        MvcResult result = mockMvc.perform(builder).andReturn();

        assertEquals(expectedStatus, result.getResponse().getStatus(),
            result.getRequest().getRequestURI() + " did not return its documented status");
        assertEquals(1, appender.list.size(),
            "a member action was not journaled: " + result.getRequest().getRequestURI());
        Map<String, Object> fields = fields(appender.list.getFirst());
        assertEquals(
            result.getRequest().getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE),
            fields.get("requestPath"));
        assertEquals(expectedStatus, fields.get("responseStatus"));
        assertEquals(3, fields.get("connexOrganizationId"));
        assertFalse(encodeEcs(appender.list.getFirst()).toString().contains("SENTINEL"),
            "a sentinel reached the encoded journal record for "
                + result.getRequest().getRequestURI());
    }

    private static Map<String, Object> fields(ILoggingEvent event) {
        return event.getKeyValuePairs().stream()
            .collect(Collectors.toMap(pair -> pair.key, pair -> pair.value));
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

    private static final class FreezingListAppender extends ListAppender<ILoggingEvent> {
        @Override
        protected void append(ILoggingEvent event) {
            event.prepareForDeferredProcessing();
            super.append(event);
        }
    }
}
