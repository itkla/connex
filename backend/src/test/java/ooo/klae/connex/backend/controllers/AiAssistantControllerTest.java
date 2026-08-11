package ooo.klae.connex.backend.controllers;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import ooo.klae.connex.backend.ai.assistant.AiAssistantTurnService;
import ooo.klae.connex.backend.dto.AiChatMessageCreateRequest;
import ooo.klae.connex.backend.dto.AiChatMessageDto;
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

@ExtendWith(MockitoExtension.class)
class AiAssistantControllerTest {

    @Mock private AiAssistantService service;
    @Mock private AiAssistantTurnService turnService;
    @Mock private ErrorReporter errorReporter;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new AiAssistantController(service, turnService))
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
            .andExpect(jsonPath("$.messages.items[0].content").value("Hello"));

        verify(service).page(1, 25);
        verify(service).get(42, 1, 50);
    }

    @Test
    void retainedScopeDispatchesToTheSeparateOversightMethods() throws Exception {
        when(service.pageRetained(1, 25)).thenReturn(new PageResponse<>(List.of(session()), 1));
        when(service.getRetained(42, 1, 50)).thenReturn(detail());

        mockMvc.perform(get("/api/ai/assistant/sessions?scope=retained"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1));
        mockMvc.perform(get("/api/ai/assistant/sessions/42?scope=retained"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.session.id").value(42));
        mockMvc.perform(get("/api/ai/assistant/sessions?scope=all"))
            .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/ai/assistant/sessions/42?scope=all"))
            .andExpect(status().isBadRequest());

        verify(service).pageRetained(1, 25);
        verify(service).getRetained(42, 1, 50);
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
    void beanAndPaginationValidationReturnBadRequest() throws Exception {
        when(service.page(0, 25)).thenThrow(new BadRequestException(
            "Page must be positive and size must be between 1 and 100"));
        when(service.get(42, 1, 101)).thenThrow(new BadRequestException(
            "Page must be positive and size must be between 1 and 100"));

        mockMvc.perform(post("/api/ai/assistant/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"   \"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.title").exists());
        mockMvc.perform(post("/api/ai/assistant/sessions/42/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.content").exists());
        mockMvc.perform(post("/api/ai/assistant/sessions/42/turns")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"Question\",\"pageContext\":[{\"kind\":\"contact\",\"id\":0}]}"))
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
            .andExpect(content().string("AI assistant session is not accessible"));
        mockMvc.perform(post("/api/ai/assistant/sessions/43/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"Hello\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value("Archived sessions cannot accept messages"));
        mockMvc.perform(delete("/api/ai/assistant/sessions/44"))
            .andExpect(status().isForbidden())
            .andExpect(content().string("AI assistant session is not accessible"));
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
            .andExpect(content().string("AI assistant session is not accessible"));
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
        message.setContent("Hello");
        message.setCreatedAt("2026-08-09 10:01:00.000000");
        return message;
    }
}
