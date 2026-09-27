package ooo.klae.connex.backend.controllers;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import ooo.klae.connex.backend.config.OneTimeLinkFlowCookie;
import ooo.klae.connex.backend.dto.AcceptDocumentRequest;
import ooo.klae.connex.backend.dto.DocumentAcceptanceDecisionDto;
import ooo.klae.connex.backend.dto.DocumentAcceptancePreviewDto;
import ooo.klae.connex.backend.exceptions.GlobalExceptionHandler;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.exceptions.TooManyRequestsException;
import ooo.klae.connex.backend.observability.ErrorReporter;
import ooo.klae.connex.backend.services.DocumentAcceptanceService;
import ooo.klae.connex.backend.services.DocumentAcceptanceService.GrantedLink;
import ooo.klae.connex.backend.services.DocumentAcceptanceService.Link;
import ooo.klae.connex.backend.services.OneTimeLinkFlowService;
import ooo.klae.connex.backend.services.OneTimeLinkFlowService.IssuedGrant;
import ooo.klae.connex.backend.services.OneTimeLinkFlowService.Purpose;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.util.ClientIpResolver;
import ooo.klae.connex.backend.util.OneTimeTokenDigest;

@ExtendWith(MockitoExtension.class)
class DocumentAcceptanceControllerTest {
    private static final String TOKEN = "w42-" + "a".repeat(64);
    private static final String GRANT = "b".repeat(64);
    private static final Link LINK = new Link(42, OneTimeTokenDigest.sha256(TOKEN));
    private static final String FLOW_ID = OneTimeTokenDigest.sha256(GRANT);
    private static final GrantedLink GRANTED = new GrantedLink(LINK, FLOW_ID);
    private static final String SOURCE = "198.51.100.20";

    @Mock private DocumentAcceptanceService acceptanceService;
    @Mock private ClientIpResolver clientIpResolver;
    @Mock private OneTimeLinkFlowService flowService;
    @Mock private OneTimeLinkFlowCookie flowCookie;
    @Mock private ErrorReporter errorReporter;
    @Mock private TenantContext tenantContext;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                new DocumentAcceptanceController(
                    acceptanceService, clientIpResolver, flowService, flowCookie))
            .setControllerAdvice(new GlobalExceptionHandler(errorReporter, tenantContext))
            .build();
    }

    @Test
    void previewReadsTheGrantCookieOnly() throws Exception {
        Instant expiry = Instant.parse("2026-09-08T10:30:00Z");
        when(clientIpResolver.resolve(any(HttpServletRequest.class))).thenReturn(SOURCE);
        when(acceptanceService.admitGrant(any(), eq(GRANT))).thenReturn(GRANTED);
        when(acceptanceService.admitGrant(any(), isNull()))
            .thenThrow(new ResourceNotFoundException("Document link is no longer available"));
        when(acceptanceService.preview(GRANTED, SOURCE)).thenReturn(
            new DocumentAcceptancePreviewDto(
                FLOW_ID,
                null,
                "Autumn renewal",
                "Hikari Systems",
                "r***@example.test",
                "sent",
                "pending",
                true,
                "quote",
                "Frozen document title",
                3,
                "en",
                expiry));

        mockMvc.perform(get("/api/document-acceptance")
                .cookie(new Cookie(OneTimeLinkFlowCookie.DOCUMENT_ACCEPTANCE, GRANT)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.flowId").value(FLOW_ID))
            .andExpect(jsonPath("$.expiresAt").value("2026-09-08T10:30:00Z"));

        mockMvc.perform(get("/api/document-acceptance").queryParam("token", TOKEN))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        verify(acceptanceService, never()).exchange(any(), any());
        verify(acceptanceService).preview(GRANTED, SOURCE);
    }

    @Test
    void decisionsMustEchoTheFlowIdentityTheyWereRenderedFrom() throws Exception {
        when(clientIpResolver.resolve(any(HttpServletRequest.class))).thenReturn(SOURCE);
        when(acceptanceService.admitGrant(any(), eq(GRANT))).thenReturn(GRANTED);
        when(acceptanceService.accept(
                eq(GRANTED), any(AcceptDocumentRequest.class), eq(SOURCE), eq("agent")))
            .thenReturn(new DocumentAcceptanceDecisionDto("completed", "completed", true));
        Cookie grant = new Cookie(OneTimeLinkFlowCookie.DOCUMENT_ACCEPTANCE, GRANT);

        mockMvc.perform(post("/api/document-acceptance/accept")
                .cookie(grant)
                .header("User-Agent", "agent")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"typedName\":\"Rina Sato\"}"))
            .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/document-acceptance/accept")
                .cookie(grant)
                .header("User-Agent", "agent")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"flowId\":\"not-a-digest\",\"typedName\":\"Rina Sato\"}"))
            .andExpect(status().isBadRequest());
        verify(acceptanceService, never()).accept(any(), any(), any(), any());

        mockMvc.perform(post("/api/document-acceptance/accept")
                .cookie(grant)
                .header("User-Agent", "agent")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"flowId\":\"" + FLOW_ID + "\",\"typedName\":\"Rina Sato\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.completed").value(true));
        verify(acceptanceService).accept(
            eq(GRANTED), eq(new AcceptDocumentRequest(FLOW_ID, "Rina Sato")), eq(SOURCE), eq("agent"));
    }

    @Test
    void exchangeIssuesTheRoutedGrantAndRedirectsWithoutTheToken() throws Exception {
        when(clientIpResolver.resolve(any(HttpServletRequest.class))).thenReturn(SOURCE);
        when(acceptanceService.exchange(TOKEN, SOURCE)).thenReturn(LINK);
        when(flowService.issueRouted(
                any(), eq(Purpose.DOCUMENT_ACCEPTANCE), eq(LINK.tokenHash()), eq(42)))
            .thenReturn(new IssuedGrant(GRANT, Duration.ofMinutes(60)));

        MvcResult result = mockMvc.perform(post("/api/document-acceptance/exchange")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + TOKEN + "\"}"))
            .andExpect(status().isSeeOther())
            .andExpect(header().string("Location", "/document-acceptance"))
            .andReturn();

        verify(flowCookie).set(
            any(), eq(Purpose.DOCUMENT_ACCEPTANCE), eq(GRANT), eq(Duration.ofMinutes(60)));
        assertFalse(result.getResponse().getContentAsString().contains(TOKEN));
        for (String name : result.getResponse().getHeaderNames()) {
            for (String value : result.getResponse().getHeaders(name)) {
                assertFalse(value.contains(TOKEN), name);
            }
        }
    }

    @Test
    void tooManyRequestsFromExchangeIsA429() throws Exception {
        when(clientIpResolver.resolve(any(HttpServletRequest.class))).thenReturn(SOURCE);
        when(acceptanceService.exchange(TOKEN, SOURCE)).thenThrow(
            new TooManyRequestsException(
                "Too many document-link requests. Please try again later."));

        mockMvc.perform(post("/api/document-acceptance/exchange")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + TOKEN + "\"}"))
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"));
    }
}
