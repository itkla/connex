package ooo.klae.connex.backend.controllers;

import static org.hamcrest.Matchers.hasKey;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import ooo.klae.connex.backend.ai.assistant.AiAssistantTurnService;
import ooo.klae.connex.backend.ai.assistant.AiAssistantToolCallReadService;
import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteToolService;
import ooo.klae.connex.backend.ai.assistant.AiChatAttachmentService;
import ooo.klae.connex.backend.dto.AiChatMessageCreateRequest;
import ooo.klae.connex.backend.dto.AiAssistantToolCallDto;
import ooo.klae.connex.backend.dto.AiAssistantToolCallReadDto;
import ooo.klae.connex.backend.dto.AiChatAttachmentDto;
import ooo.klae.connex.backend.dto.AiChatMessageDto;
import ooo.klae.connex.backend.dto.AiChatParticipantDto;
import ooo.klae.connex.backend.dto.AiChatPresenceDto;
import ooo.klae.connex.backend.dto.AiChatQueryScopeDto;
import ooo.klae.connex.backend.dto.AiChatScopePreviewDto;
import ooo.klae.connex.backend.dto.AiChatScopePreviewRequest;
import ooo.klae.connex.backend.dto.AiChatSessionCreateRequest;
import ooo.klae.connex.backend.dto.AiChatSessionDetailDto;
import ooo.klae.connex.backend.dto.AiChatSessionDto;
import ooo.klae.connex.backend.dto.AiChatSessionUpdateRequest;
import ooo.klae.connex.backend.dto.AiChatTurnAcceptedDto;
import ooo.klae.connex.backend.dto.AiChatTurnCreateRequest;
import ooo.klae.connex.backend.dto.AiChatTurnDto;
import ooo.klae.connex.backend.dto.PageResponse;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.GlobalExceptionHandler;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.observability.ErrorReporter;
import ooo.klae.connex.backend.services.AiAssistantService;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.storage.UploadSource;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class AiAssistantControllerTest {

    @Mock private AiAssistantService service;
    @Mock private AiAssistantTurnService turnService;
    @Mock private AiAssistantToolCallReadService toolCallReadService;
    @Mock private AiAssistantWriteToolService writeToolService;
    @Mock private AiChatAttachmentService attachmentService;
    @Mock private ErrorReporter errorReporter;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                new AiAssistantController(
                        service, turnService, toolCallReadService,
                        writeToolService, attachmentService))
            .setControllerAdvice(new GlobalExceptionHandler(errorReporter, new TenantContext()))
            .build();
    }

    @Test
    void turnPostReturnsAcceptedHandleWithoutUsingTheAppendEndpoint() throws Exception {
        when(turnService.start(
                org.mockito.ArgumentMatchers.eq(42), any(AiChatTurnCreateRequest.class)))
            .thenReturn(new AiChatTurnAcceptedDto(
                    19, 42, "be5775f1-3ee0-40cb-922f-6d419b78fa52", "accepted"));

        mockMvc.perform(post("/api/ai/assistant/sessions/42/turns")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"What is new?\",\"pageContext\":[{\"kind\":\"person\",\"id\":7}]}"))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.turnId").value(19))
            .andExpect(jsonPath("$.sessionId").value(42))
            .andExpect(jsonPath("$.status").value("accepted"));

        verify(turnService).start(
                org.mockito.ArgumentMatchers.eq(42), any(AiChatTurnCreateRequest.class));
    }

    /**
     * The preview is the sentence a member confirms before a broad request runs, so its shape is
     * part of the contract: the interpreted scope, the evaluated cohort size, the caps the retrieval
     * will apply, and whether a skill would run all have to arrive together.
     */
    @Test
    void scopePreviewReturnsTheInterpretedScopeAndTheEvaluatedBreadth() throws Exception {
        when(turnService.previewScope(any(AiChatScopePreviewRequest.class)))
            .thenReturn(new AiChatScopePreviewDto(
                    new AiChatQueryScopeDto(
                            true, "2026-05-26", "2026-08-23", 90, "all_team", List.of(),
                            List.of("cool", "cold"), List.of("company"), List.of(),
                            List.of(), List.of(), null, 128, false, 200, 100, 10, List.of()),
                    "activity_digest_v1",
                    "1.0.0",
                    true));

        mockMvc.perform(post("/api/ai/assistant/sessions/scope-preview")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"List recent activity for cool accounts\","
                        + "\"pageContext\":[],"
                        + "\"scope\":{\"periodDays\":90,\"warmthBands\":[\"cool\",\"cold\"],"
                        + "\"recordKinds\":[\"company\"]}}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.scope.declared").value(true))
            .andExpect(jsonPath("$.scope.periodStart").value("2026-05-26"))
            .andExpect(jsonPath("$.scope.matchedRecordCount").value(128))
            .andExpect(jsonPath("$.scope.recordCap").value(200))
            .andExpect(jsonPath("$.scope.activityCap").value(100))
            .andExpect(jsonPath("$.scope.perRecordCap").value(10))
            .andExpect(jsonPath("$.skillKey").value("activity_digest_v1"))
            .andExpect(jsonPath("$.confirmationRecommended").value(true));

        verify(turnService).previewScope(any(AiChatScopePreviewRequest.class));
    }

    @Test
    void scopePreviewRejectsAScopeTheServerCannotExecuteAsDeclared() throws Exception {
        when(turnService.previewScope(any(AiChatScopePreviewRequest.class)))
            .thenThrow(new BadRequestException(
                    "Assistant scope cannot be executed as declared: "
                            + "warmth_unsupported_for_deals"));

        mockMvc.perform(post("/api/ai/assistant/sessions/scope-preview")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"Which deals need attention?\",\"pageContext\":[],"
                        + "\"scope\":{\"warmthBands\":[\"cool\"],"
                        + "\"recordKinds\":[\"deal\"]}}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void scopePreviewRejectsAnUnsupportedFilterValueAtTheBoundary() throws Exception {
        mockMvc.perform(post("/api/ai/assistant/sessions/scope-preview")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"Anything\",\"pageContext\":[],"
                        + "\"scope\":{\"warmthBands\":[\"lukewarm\"]}}"))
            .andExpect(status().is4xxClientError());

        verify(turnService, org.mockito.Mockito.never())
            .previewScope(any(AiChatScopePreviewRequest.class));
    }

    @Test
    void scopePreviewSurfacesAnAuthorizationFailureRatherThanAnEmptyPreview() throws Exception {
        when(turnService.previewScope(any(AiChatScopePreviewRequest.class)))
            .thenThrow(new ForbiddenException("Requires the AI_USE permission in this workspace"));

        mockMvc.perform(post("/api/ai/assistant/sessions/scope-preview")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"Anything\",\"pageContext\":[],\"scope\":{}}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void turnGetReturnsTheDurableTerminalState() throws Exception {
        when(turnService.get(42, 19)).thenReturn(
                new AiChatTurnDto(19, 42, "timed_out", "generation_timeout"));

        mockMvc.perform(get("/api/ai/assistant/sessions/42/turns/19"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.turnId").value(19))
            .andExpect(jsonPath("$.sessionId").value(42))
            .andExpect(jsonPath("$.status").value("timed_out"))
            .andExpect(jsonPath("$.terminalReason").value("generation_timeout"));

        verify(turnService).get(42, 19);
    }

    @Test
    void turnGetExposesPartialContentAndCancelReturnsNoContent() throws Exception {
        when(turnService.get(42, 19)).thenReturn(
                new AiChatTurnDto(19, 42, "running", null, "Partial 😀"));

        mockMvc.perform(get("/api/ai/assistant/sessions/42/turns/19"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partialContent").value("Partial 😀"));
        mockMvc.perform(post("/api/ai/assistant/sessions/42/turns/19/cancel"))
                .andExpect(status().isNoContent());

        verify(turnService).cancel(42, 19);
    }

    @Test
    void attachmentEndpointsUseSessionScopedMultipartContract() throws Exception {
        AiChatAttachmentDto attachment = new AiChatAttachmentDto(
                91, "notes.txt", "text/plain", 7, "text",
                "2026-08-11 00:00:00.000000");
        when(attachmentService.list(42)).thenReturn(List.of(attachment));
        when(attachmentService.upload(
                org.mockito.ArgumentMatchers.eq(42), any(UploadSource.class)))
                .thenReturn(attachment);
        MockMultipartFile file = new MockMultipartFile(
                "file", "notes.txt", "text/plain", "content".getBytes());

        mockMvc.perform(get("/api/ai/assistant/sessions/42/attachments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(91))
                .andExpect(jsonPath("$[0].kind").value("text"));
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .multipart("/api/ai/assistant/sessions/42/attachments")
                .file(file))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fileName").value("notes.txt"));
        mockMvc.perform(delete("/api/ai/assistant/sessions/42/attachments/91"))
                .andExpect(status().isNoContent());

        verify(attachmentService).list(42);
        verify(attachmentService).upload(
                org.mockito.ArgumentMatchers.eq(42), any(UploadSource.class));
        verify(attachmentService).delete(42, 91);
    }

    @Test
    void approvalRejectionAndUndoUseSessionScopedToolCallEndpoints() throws Exception {
        JsonMapper objectMapper = JsonMapper.builder().build();
        when(writeToolService.approve(42, 29)).thenReturn(new AiAssistantToolCallDto(
                29, "change_deal_stage", "confirm", "executed",
                objectMapper.readTree("{\"stage\":\"Proposal\"}"), false, null));
        when(writeToolService.reject(42, 30)).thenReturn(new AiAssistantToolCallDto(
                30, "assign_owner", "confirm", "rejected",
                objectMapper.createObjectNode(), false, null));
        when(writeToolService.undo(42, 31)).thenReturn(new AiAssistantToolCallDto(
                31, "create_note", "auto", "undone",
                objectMapper.readTree("{\"recordType\":\"note\"}"), false,
                "2026-08-10T12:10:00Z"));

        mockMvc.perform(post("/api/ai/assistant/sessions/42/tool-calls/29/approve"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("executed"))
            .andExpect(jsonPath("$.result.stage").value("Proposal"));
        mockMvc.perform(post("/api/ai/assistant/sessions/42/tool-calls/30/reject"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("rejected"));
        mockMvc.perform(post("/api/ai/assistant/sessions/42/tool-calls/31/undo"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("undone"));

        verify(writeToolService).approve(42, 29);
        verify(writeToolService).reject(42, 30);
        verify(writeToolService).undo(42, 31);
    }

    @Test
    void toolCallListAndDetailExposeTheSafeReadContract() throws Exception {
        AiAssistantToolCallReadDto toolCall = new AiAssistantToolCallReadDto(
                29,
                "assign_owner",
                "confirm",
                "proposed",
                new AiAssistantToolCallReadDto.Target("person", 31, "Ada Lovelace"),
                "Assign an owner",
                null,
                new AiAssistantToolCallReadDto.Change(
                        "owner", null, true, "Grace Hopper", "ready"),
                List.of(new AiAssistantToolCallReadDto.OutcomeValue("owner", "Grace Hopper")),
                new AiAssistantToolCallReadDto.CreatedRecord("task", 74),
                82,
                19,
                null,
                false,
                "2026-08-10 12:00:00.000000",
                "2026-08-10 12:00:00.000000",
                null);
        when(toolCallReadService.list(42, false)).thenReturn(List.of(toolCall));
        when(toolCallReadService.list(42, true)).thenReturn(List.of(toolCall));
        when(toolCallReadService.get(42, 29)).thenReturn(toolCall);
        when(toolCallReadService.listRetained(42, false)).thenReturn(List.of(toolCall));
        when(toolCallReadService.getRetained(42, 29)).thenReturn(toolCall);

        mockMvc.perform(get("/api/ai/assistant/sessions/42/tool-calls"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].toolName").value("assign_owner"))
            .andExpect(jsonPath("$[0].tier").value("confirm"))
            .andExpect(jsonPath("$[0].status").value("proposed"))
            .andExpect(jsonPath("$[0].target.kind").value("person"))
            .andExpect(jsonPath("$[0].target.id").value(31))
            .andExpect(jsonPath("$[0].target.label").value("Ada Lovelace"))
            .andExpect(jsonPath("$[0].requestSummary").value("Assign an owner"))
            .andExpect(jsonPath("$[0].change.field").value("owner"))
            .andExpect(jsonPath("$[0].change.currentValue").doesNotExist())
            .andExpect(jsonPath("$[0].change").value(hasKey("currentValue")))
            .andExpect(jsonPath("$[0].change.currentValueUnresolved").value(true))
            .andExpect(jsonPath("$[0].change.proposedValue").value("Grace Hopper"))
            .andExpect(jsonPath("$[0].change.state").value("ready"))
            .andExpect(jsonPath("$[0].outcomeValues[0].field").value("owner"))
            .andExpect(jsonPath("$[0].outcomeValues[0].value").value("Grace Hopper"))
            .andExpect(jsonPath("$[0].createdRecord.kind").value("task"))
            .andExpect(jsonPath("$[0].createdRecord.id").value(74))
            .andExpect(jsonPath("$[0].outcomeSummary").doesNotExist())
            .andExpect(jsonPath("$[0]").value(hasKey("outcomeSummary")))
            .andExpect(jsonPath("$[0].messageId").value(82))
            .andExpect(jsonPath("$[0].turnId").value(19))
            .andExpect(jsonPath("$[0].arguments").doesNotExist())
            .andExpect(jsonPath("$[0].result").doesNotExist());
        mockMvc.perform(get("/api/ai/assistant/sessions/42/tool-calls?pendingOnly=true"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].status").value("proposed"));
        mockMvc.perform(get("/api/ai/assistant/sessions/42/tool-calls/29"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(29))
            .andExpect(jsonPath("$.requestSummary").value("Assign an owner"));
        mockMvc.perform(post("/api/ai/assistant/sessions/42/tool-calls/retained"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value(29));
        mockMvc.perform(post(
                "/api/ai/assistant/sessions/42/tool-calls/29/retained"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(29));

        verify(toolCallReadService).list(42, false);
        verify(toolCallReadService).list(42, true);
        verify(toolCallReadService).get(42, 29);
        verify(toolCallReadService).listRetained(42, false);
        verify(toolCallReadService).getRetained(42, 29);
    }

    @Test
    void listAndDetailUsePaginationDefaultsAndCanonicalShape() throws Exception {
        when(service.page(1, 25)).thenReturn(new PageResponse<>(List.of(session()), 1));
        when(service.get(42, 1, 50)).thenReturn(detail());

        mockMvc.perform(get("/api/ai/assistant/sessions"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.items[0].id").value(42))
            .andExpect(jsonPath("$.items[0].workspaceId").value(9))
            .andExpect(jsonPath("$.items[0].createdByUserId").value(7))
            .andExpect(jsonPath("$.items[0].status").value("active"))
            .andExpect(jsonPath("$.items[0].archived").value(false))
            .andExpect(jsonPath("$.items[0].ownedByCurrentUser").value(true));
        mockMvc.perform(get("/api/ai/assistant/sessions/42"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.session.id").value(42))
            .andExpect(jsonPath("$.messages.total").value(1))
            .andExpect(jsonPath("$.messages.items[0].seq").value(1))
            .andExpect(jsonPath("$.messages.items[0].authorKind").value("user"))
            .andExpect(jsonPath("$.messages.items[0].authorDisplayName").value("Aki Tanaka"))
            .andExpect(jsonPath("$.messages.items[0].content").value("Hello"));

        verify(service).page(1, 25);
        verify(service).get(42, 1, 50);
    }

    /**
     * Retained reads disclose a departed member's transcripts and record the disclosure in the
     * audit trail, so they are served over {@code POST}. {@code JSESSIONID} is
     * {@code SameSite=Lax}, which is sent on cross-site top-level {@code GET} navigation, so a
     * URL alone must never be able to trigger one.
     */
    @Test
    void retainedReadsAreServedOverPostOnly() throws Exception {
        when(service.pageRetained(1, 25)).thenReturn(new PageResponse<>(List.of(session()), 1));
        when(service.getRetained(42, 1, 50)).thenReturn(detail());

        mockMvc.perform(post("/api/ai/assistant/sessions/retained"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1));
        mockMvc.perform(post("/api/ai/assistant/sessions/42/retained"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.session.id").value(42));

        verify(service).pageRetained(1, 25);
        verify(service).getRetained(42, 1, 50);
    }

    /**
     * The retired {@code ?scope=retained} query string must no longer reach any recorded read. It
     * now falls through to the caller's own accessible-session read, which cannot return a
     * departed member's private session.
     */
    @Test
    void theRetiredRetainedScopeQueryStringNeverReachesARecordedRead() throws Exception {
        when(service.page(1, 25)).thenReturn(new PageResponse<>(List.of(session()), 1));
        when(service.get(42, 1, 50)).thenReturn(detail());
        when(toolCallReadService.list(42, false)).thenReturn(List.of());
        when(toolCallReadService.get(42, 29)).thenReturn(null);

        mockMvc.perform(get("/api/ai/assistant/sessions?scope=retained"))
            .andExpect(status().isOk());
        mockMvc.perform(get("/api/ai/assistant/sessions/42?scope=retained"))
            .andExpect(status().isOk());
        mockMvc.perform(get("/api/ai/assistant/sessions/42/tool-calls?scope=retained"))
            .andExpect(status().isOk());
        mockMvc.perform(get("/api/ai/assistant/sessions/42/tool-calls/29?scope=retained"))
            .andExpect(status().isOk());

        verify(service).page(1, 25);
        verify(service).get(42, 1, 50);
        verify(service, never()).pageRetained(anyInt(), anyInt());
        verify(service, never()).getRetained(anyInt(), anyInt(), anyInt());
        verify(toolCallReadService, never()).listRetained(anyInt(), anyBoolean());
        verify(toolCallReadService, never()).getRetained(anyInt(), anyInt());
    }

    @Test
    void createReturnsCreatedBodyAndLocation() throws Exception {
        when(service.create(any(AiChatSessionCreateRequest.class))).thenReturn(session());

        mockMvc.perform(post("/api/ai/assistant/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Planning\"}"))
            .andExpect(status().isCreated())
            .andExpect(header().string("Location", "/api/ai/assistant/sessions/42"))
            .andExpect(jsonPath("$.id").value(42))
            .andExpect(jsonPath("$.title").value("Planning"))
            .andExpect(jsonPath("$.visibility").value("private"));
    }

    @Test
    void patchDeleteAndMessagePostUseTheContractVerbsAndStatuses() throws Exception {
        when(service.update(org.mockito.ArgumentMatchers.eq(42), any(AiChatSessionUpdateRequest.class)))
            .thenReturn(session());
        when(service.appendMessage(
                org.mockito.ArgumentMatchers.eq(42), any(AiChatMessageCreateRequest.class)))
            .thenReturn(message());

        mockMvc.perform(patch("/api/ai/assistant/sessions/42")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Renamed\",\"archived\":true}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(42));
        mockMvc.perform(delete("/api/ai/assistant/sessions/42"))
            .andExpect(status().isNoContent())
            .andExpect(content().string(""));
        mockMvc.perform(post("/api/ai/assistant/sessions/42/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"Hello\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value(81))
            .andExpect(jsonPath("$.sessionId").value(42))
            .andExpect(jsonPath("$.seq").value(1));
        mockMvc.perform(put("/api/ai/assistant/sessions/42")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Wrong verb\"}"))
            .andExpect(status().isMethodNotAllowed());

        verify(service).archive(42);
    }

    @Test
    void collaborationEndpointsExposeInvitationsMembershipAndPresence() throws Exception {
        AiChatParticipantDto participant = new AiChatParticipantDto(
                8, "Mina Sato", null, "participant", "invited", false);
        AiChatPresenceDto presence = new AiChatPresenceDto(
                42, List.of(participant), List.of(8));
        when(service.pageInvitations(1, 25))
                .thenReturn(new PageResponse<>(List.of(session()), 1));
        when(service.setShared(42, true)).thenReturn(session());
        when(service.invite(42, 8)).thenReturn(participant);
        when(service.join(42)).thenReturn(session());
        when(service.participants(42)).thenReturn(List.of(participant));
        when(service.presence(42)).thenReturn(presence);
        when(service.touchPresence(42, true)).thenReturn(presence);

        mockMvc.perform(get("/api/ai/assistant/sessions/invitations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1));
        mockMvc.perform(patch("/api/ai/assistant/sessions/42/sharing")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shared\":true}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/ai/assistant/sessions/42/invitations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":8}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("invited"));
        mockMvc.perform(post("/api/ai/assistant/sessions/42/join"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/ai/assistant/sessions/42/participants"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].displayName").value("Mina Sato"));
        mockMvc.perform(get("/api/ai/assistant/sessions/42/presence"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.typingUserIds[0]").value(8));
        mockMvc.perform(put("/api/ai/assistant/sessions/42/presence")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"typing\":true}"))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/ai/assistant/sessions/42/presence"))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/ai/assistant/sessions/42/participants/8"))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/ai/assistant/sessions/42/leave"))
                .andExpect(status().isNoContent());

        verify(service).setShared(42, true);
        verify(service).invite(42, 8);
        verify(service).join(42);
        verify(service).removeParticipant(42, 8);
        verify(service).leavePresence(42);
        verify(service).leave(42);
    }

    @Test
    void beanAndPaginationValidationReturnBadRequest() throws Exception {
        when(service.page(0, 25)).thenThrow(new BadRequestException(
            "Page must be positive and size must be between 1 and 100"));
        when(service.get(42, 1, 101)).thenThrow(new BadRequestException(
            "Page must be positive and size must be between 1 and 100"));

        mockMvc.perform(post("/api/ai/assistant/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"   \"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors.title").exists());
        mockMvc.perform(post("/api/ai/assistant/sessions/42/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors.content").exists());
        mockMvc.perform(post("/api/ai/assistant/sessions/42/turns")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"Question\",\"pageContext\":[{\"kind\":\"contact\",\"id\":0}]}"))
            .andExpect(status().isBadRequest());
        mockMvc.perform(patch("/api/ai/assistant/sessions/42/sharing")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/ai/assistant/sessions/42/invitations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":0}"))
            .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/ai/assistant/sessions?page=0"))
            .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/ai/assistant/sessions/42?size=101"))
            .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/ai/assistant/sessions?page=not-a-number"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void inaccessibleAndArchivedTargetsMapToForbiddenAndConflict() throws Exception {
        when(service.get(42, 1, 50)).thenThrow(
            new ForbiddenException("AI assistant session is not accessible"));
        when(service.appendMessage(
                org.mockito.ArgumentMatchers.eq(43), any(AiChatMessageCreateRequest.class)))
            .thenThrow(new ConflictException("Archived sessions cannot accept messages"));
        doThrow(new ForbiddenException("AI assistant session is not accessible"))
            .when(service).archive(44);

        mockMvc.perform(get("/api/ai/assistant/sessions/42"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FORBIDDEN"))
            .andExpect(jsonPath("$.message").value("AI assistant session is not accessible"));
        mockMvc.perform(post("/api/ai/assistant/sessions/43/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"Hello\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value("Archived sessions cannot accept messages"));
        mockMvc.perform(delete("/api/ai/assistant/sessions/44"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FORBIDDEN"))
            .andExpect(jsonPath("$.message").value("AI assistant session is not accessible"));
    }

    @Test
    void inaccessibleTurnTargetUsesTheGenericNotFoundResponse() throws Exception {
        when(turnService.start(
                org.mockito.ArgumentMatchers.eq(42), any(AiChatTurnCreateRequest.class)))
            .thenThrow(new ResourceNotFoundException("AI assistant session is not accessible"));

        mockMvc.perform(post("/api/ai/assistant/sessions/42/turns")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"Question\"}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
            .andExpect(jsonPath("$.message").value("AI assistant session is not accessible"));
    }

    private AiChatSessionDetailDto detail() {
        return new AiChatSessionDetailDto(
            session(), new PageResponse<>(List.of(message()), 1));
    }

    private AiChatSessionDto session() {
        AiChatSessionDto session = new AiChatSessionDto();
        session.setId(42);
        session.setWorkspaceId(9);
        session.setCreatedByUserId(7);
        session.setTitle("Planning");
        session.setVisibility("private");
        session.setStatus("active");
        session.setArchived(false);
        session.setOwnedByCurrentUser(true);
        session.setLastMessageAt("2026-08-09 10:00:00.000000");
        session.setCreatedAt("2026-08-09 10:00:00.000000");
        session.setUpdatedAt("2026-08-09 10:00:00.000000");
        return session;
    }

    private AiChatMessageDto message() {
        AiChatMessageDto message = new AiChatMessageDto();
        message.setId(81);
        message.setSessionId(42);
        message.setSeq(1);
        message.setAuthorKind("user");
        message.setAuthorUserId(7);
        message.setAuthorDisplayName("Aki Tanaka");
        message.setContent("Hello");
        message.setCreatedAt("2026-08-09 10:01:00.000000");
        return message;
    }
}
