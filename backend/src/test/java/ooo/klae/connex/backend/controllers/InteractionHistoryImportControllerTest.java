package ooo.klae.connex.backend.controllers;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import ooo.klae.connex.backend.dto.HistoryImportRequest;
import ooo.klae.connex.backend.services.InteractionHistoryImportService;

@ExtendWith(MockitoExtension.class)
class InteractionHistoryImportControllerTest {

    private static final String VALID_REQUEST = """
        {
          "rows":[{"when":"2026-01-01T00:00:00Z","email":"person@example.com","value":"Imported"}],
          "mapping":[
            {"column":"when","field":"occurredAt"},
            {"column":"email","field":"participantEmail"},
            {"column":"value","field":"subject"}
          ]
        }
        """;

    @Mock private InteractionHistoryImportService importService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
            new InteractionHistoryImportController(importService)).build();
    }

    @Test
    void delegatesAllSixHistoryImportRoutes() throws Exception {
        mockMvc.perform(post("/api/imports/history/activities/preview")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_REQUEST))
            .andExpect(status().isOk());
        mockMvc.perform(post("/api/imports/history/activities")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_REQUEST))
            .andExpect(status().isOk());
        mockMvc.perform(post("/api/imports/history/notes/preview")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_REQUEST))
            .andExpect(status().isOk());
        mockMvc.perform(post("/api/imports/history/notes")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_REQUEST))
            .andExpect(status().isOk());
        mockMvc.perform(post("/api/imports/history/tasks/preview")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_REQUEST))
            .andExpect(status().isOk());
        mockMvc.perform(post("/api/imports/history/tasks")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_REQUEST))
            .andExpect(status().isOk());

        verify(importService).previewActivities(any(HistoryImportRequest.class));
        verify(importService).commitActivities(any(HistoryImportRequest.class));
        verify(importService).previewNotes(any(HistoryImportRequest.class));
        verify(importService).commitNotes(any(HistoryImportRequest.class));
        verify(importService).previewTasks(any(HistoryImportRequest.class));
        verify(importService).commitTasks(any(HistoryImportRequest.class));
    }

    @Test
    void rejectsInvalidBoundsBeforeCallingTheService() throws Exception {
        mockMvc.perform(post("/api/imports/history/activities/preview")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"rows":[],"mapping":[],"links":{"0":0},"duplicateReviewProof":"not-a-proof"}
                    """))
            .andExpect(status().isBadRequest());

        verifyNoInteractions(importService);
    }
}
