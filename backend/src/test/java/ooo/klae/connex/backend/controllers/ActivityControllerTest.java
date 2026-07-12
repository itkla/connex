package ooo.klae.connex.backend.controllers;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import ooo.klae.connex.backend.services.ActivityService;

@ExtendWith(MockitoExtension.class)
class ActivityControllerTest {
    @Mock private ActivityService activityService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ActivityController(activityService)).build();
    }

    @Test
    void createRejectsAnInvalidTimestampBeforeCallingTheService() throws Exception {
        mockMvc.perform(post("/api/activities")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"type":"call","subject":"Follow up","timestamp":"2024-02-30 10:00:00"}
                    """))
            .andExpect(status().isBadRequest());

        verifyNoInteractions(activityService);
    }

    @Test
    void updateRejectsAMalformedTimestampBeforeCallingTheService() throws Exception {
        mockMvc.perform(put("/api/activities/7")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"type":"call","subject":"Follow up","timestamp":"2024-01-01T10:00:00"}
                    """))
            .andExpect(status().isBadRequest());

        verifyNoInteractions(activityService);
    }
}
