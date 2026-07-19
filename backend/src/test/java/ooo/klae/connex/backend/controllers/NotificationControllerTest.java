package ooo.klae.connex.backend.controllers;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import ooo.klae.connex.backend.dto.NotificationDto;
import ooo.klae.connex.backend.dto.NotificationPageDto;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.GlobalExceptionHandler;
import ooo.klae.connex.backend.services.NotificationPreferenceService;
import ooo.klae.connex.backend.services.NotificationQuietHoursService;
import ooo.klae.connex.backend.services.NotificationService;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class NotificationControllerTest {
    @Mock private NotificationService notificationService;
    @Mock private NotificationPreferenceService preferenceService;
    @Mock private NotificationQuietHoursService quietHoursService;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                new NotificationController(notificationService, preferenceService, quietHoursService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    @Test
    void listBindsRepeatableFiltersAndBothStatusAliases() throws Exception {
        when(notificationService.getPage(
            "snoozed",
            null,
            List.of("task.due", "deal.close"),
            List.of("task", "deal"),
            List.of("critical"),
            7,
            "deal",
            9,
            2,
            50
        )).thenReturn(new NotificationPageDto(List.of(), 0, 0));

        mockMvc.perform(get("/api/notifications")
                .param("status", "snoozed")
                .param("type", "task.due", "deal.close")
                .param("category", "task", "deal")
                .param("severity", "critical")
                .param("workspaceId", "7")
                .param("contextType", "deal")
                .param("contextId", "9")
                .param("page", "2")
                .param("size", "50"))
            .andExpect(status().isOk());

        verify(notificationService).getPage(
            "snoozed", null, List.of("task.due", "deal.close"), List.of("task", "deal"),
            List.of("critical"), 7, "deal", 9, 2, 50);
    }

    @Test
    void invalidLegacyHoursAreRejectedBeforeService() throws Exception {
        mockMvc.perform(post("/api/notifications/9/snooze")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("hours", 0))))
            .andExpect(status().isBadRequest());

        verifyNoInteractions(notificationService);
    }

    @Test
    void snoozeConflictMapsTo409AndUnsnoozeMapsTo200() throws Exception {
        when(notificationService.snooze(eq(9), any()))
            .thenThrow(new ConflictException("Dismissed or resolved notifications cannot be snoozed"));
        when(notificationService.unsnooze(9)).thenReturn(new NotificationDto());

        mockMvc.perform(post("/api/notifications/9/snooze")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "preset", "tomorrow_morning", "timezone", "UTC"))))
            .andExpect(status().isConflict());
        mockMvc.perform(post("/api/notifications/9/unsnooze"))
            .andExpect(status().isOk());
    }

    @Test
    void quietHoursRejectMalformedTimeBeforeService() throws Exception {
        mockMvc.perform(put("/api/notification-preferences/quiet-hours")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "enabled", true,
                    "timezone", "UTC",
                    "start", "9:00",
                    "end", "07:00",
                    "days", List.of("MONDAY")))))
            .andExpect(status().isBadRequest());

        verifyNoInteractions(quietHoursService);
    }
}
