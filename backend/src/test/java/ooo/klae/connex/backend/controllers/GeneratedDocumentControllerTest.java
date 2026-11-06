package ooo.klae.connex.backend.controllers;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;

import ooo.klae.connex.backend.dto.GeneratedDocumentTargetDto;
import ooo.klae.connex.backend.services.DealDocumentService;

@WebMvcTest(controllers = GeneratedDocumentController.class, properties = "connex.sso.enabled=false")
@WithMockUser
class GeneratedDocumentControllerTest extends RecordControllerMvcTestSupport {
    @Autowired private MockMvc mockMvc;
    @MockitoBean private DealDocumentService documentService;

    @Test
    void bindsABatchAndReturnsOnlyTheMinimalProjection() throws Exception {
        when(documentService.getTargets(List.of(61, 62)))
            .thenReturn(List.of(new GeneratedDocumentTargetDto(61, 41, "Quote")));
        mockMvc.perform(get("/api/documents/targets").queryParam("ids", "61,62"))
            .andExpect(status().isOk())
            .andExpect(content().json("[{\"id\":61,\"dealId\":41,\"title\":\"Quote\"}]", JsonCompareMode.STRICT));
        verify(documentService).getTargets(List.of(61, 62));
    }

    @Test
    @WithAnonymousUser
    void anonymousReadsAreRefusedBeforeServiceInvocation() throws Exception {
        mockMvc.perform(get("/api/documents/targets").queryParam("ids", "61"))
            .andExpect(status().isUnauthorized());
        verifyNoInteractions(documentService);
    }

    @Test
    void missingAndMalformedIdsAreBadRequests() throws Exception {
        mockMvc.perform(get("/api/documents/targets")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/documents/targets").queryParam("ids", "not-an-id"))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(documentService);
    }

    @Test
    void invalidBatchUsesTheEstablishedBadRequestResponse() throws Exception {
        mockMvc.perform(get("/api/documents/targets").queryParam("ids", "0"))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(documentService);
    }

    @Test
    void overCapRequestsAreRefusedBeforeServiceInvocation() throws Exception {
        String[] ids = IntStream.rangeClosed(1, 51).mapToObj(Integer::toString).toArray(String[]::new);
        mockMvc.perform(get("/api/documents/targets").queryParam("ids", ids))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(documentService);
    }
}
