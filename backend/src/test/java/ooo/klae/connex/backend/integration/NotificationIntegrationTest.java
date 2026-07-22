package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.Filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.request.RequestContextHolder;

import ooo.klae.connex.backend.beans.Notification;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.mappers.NotificationMapper;
import ooo.klae.connex.backend.mappers.TaskMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import tools.jackson.databind.ObjectMapper;

/** Authenticated HTTP coverage for notification snooze, scoping, and global quiet hours. */
@SpringBootTest
@Transactional
class NotificationIntegrationTest {
    private static final String PASSWORD = "Notification-Test-Pw1!";

    @Autowired private WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") private Filter springSecurityFilterChain;
    @Autowired private UserMapper userMapper;
    @Autowired private WorkspaceMapper workspaceMapper;
    @Autowired private NotificationMapper notificationMapper;
    @Autowired private TaskMapper taskMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private ObjectMapper objectMapper;

    private MockMvc mockMvc;
    private Workspace workspace;

    @BeforeEach
    void setUp() {
        RequestContextHolder.resetRequestAttributes();
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .addFilters(springSecurityFilterChain)
            .build();
        workspace = workspaceMapper.getDefaultWorkspace();
        if (workspace == null) {
            workspace = new Workspace();
            workspace.setName("Notification Test Workspace");
            workspace.setSlug("notification-test-" + suffix());
            workspaceMapper.insert(workspace);
        }
    }

    @Test
    void snoozeAndQuietHoursRemainOwnedByTheAuthenticatedRecipient() throws Exception {
        User alice = newMember();
        User bob = newMember();
        Notification notification = notification(alice);
        notificationMapper.upsert(notification);
        MockHttpSession aliceSession = login(alice.getUsername());
        MockHttpSession bobSession = login(bob.getUsername());

        mockMvc.perform(post("/api/notifications/{id}/snooze", notification.getId())
                .session(bobSession)
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("hours", 1))))
            .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/notifications/{id}/snooze", notification.getId())
                .session(aliceSession)
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("preset", "tomorrow_morning", "timezone", "UTC"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.snoozedUntil").exists())
            .andExpect(jsonPath("$.snoozeTimezone").value("UTC"));

        mockMvc.perform(get("/api/notifications")
                .session(aliceSession)
                .param("status", "snoozed")
                .param("type", "task.due"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1));

        mockMvc.perform(get("/api/notifications/counts")
                .session(aliceSession))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.unread").value(0))
            .andExpect(jsonPath("$.snoozed").value(1))
            .andExpect(jsonPath("$.stateVersion").isNumber())
            .andExpect(jsonPath("$.asOf").exists())
            .andExpect(jsonPath("$.nextSnoozeExpiry").exists())
            .andExpect(jsonPath("$.quietHoursActive").value(false));

        mockMvc.perform(post("/api/notifications/{id}/unsnooze", notification.getId())
                .session(aliceSession)
                .with(csrf().asHeader()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.snoozedUntil").doesNotExist());
        mockMvc.perform(post("/api/notifications/{id}/unsnooze", notification.getId())
                .session(aliceSession)
                .with(csrf().asHeader()))
            .andExpect(status().isOk());

        mockMvc.perform(put("/api/notification-preferences/quiet-hours")
                .session(aliceSession)
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of(
                    "enabled", true,
                    "timezone", "America/New_York",
                    "start", "22:00",
                    "end", "07:00",
                    "days", List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY")))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.enabled").value(true))
            .andExpect(jsonPath("$.timezone").value("America/New_York"))
            .andExpect(jsonPath("$.bypassPolicy").value("security_only"));

        mockMvc.perform(get("/api/notification-preferences/quiet-hours")
                .session(bobSession))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.enabled").value(false))
            .andExpect(jsonPath("$.timezone").value("UTC"));

        mockMvc.perform(get("/api/notifications")
                .session(aliceSession)
                .param("status", "active")
                .param("state", "unread"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void dismissedNotificationCannotBeSnoozed() throws Exception {
        User recipient = newMember();
        Notification notification = notification(recipient);
        notificationMapper.upsert(notification);
        notificationMapper.dismiss(recipient.getId(), notification.getId());

        mockMvc.perform(post("/api/notifications/{id}/snooze", notification.getId())
                .session(login(recipient.getUsername()))
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("hours", 1))))
            .andExpect(status().isConflict());
    }

    @Test
    void removedMembershipAndDeletedSourceReturnNotFound() throws Exception {
        User recipient = newMember();
        Workspace removedWorkspace = new Workspace();
        removedWorkspace.setName("Removed Notification Workspace");
        removedWorkspace.setSlug("removed-notification-" + suffix());
        removedWorkspace.setOrgId(workspace.getOrgId());
        workspaceMapper.insert(removedWorkspace);
        workspaceMapper.addMember(removedWorkspace.getId(), recipient.getId(), "member");
        Notification removedMembershipNotification = notification(recipient);
        removedMembershipNotification.setWorkspaceId(removedWorkspace.getId());
        notificationMapper.upsert(removedMembershipNotification);

        Task task = new Task();
        task.setWorkspaceId(workspace.getId());
        task.setDescription("Revoked notification source");
        task.setStatus("todo");
        task.setAssignedTo(recipient);
        taskMapper.insert(task);
        Notification deletedSourceNotification = notification(recipient);
        deletedSourceNotification.setSourceType("task");
        deletedSourceNotification.setSourceId(task.getId());
        notificationMapper.upsert(deletedSourceNotification);
        MockHttpSession session = login(recipient.getUsername());

        workspaceMapper.removeMember(removedWorkspace.getId(), recipient.getId());
        taskMapper.delete(workspace.getId(), task.getId());

        assertSnoozeNotFound(session, removedMembershipNotification.getId());
        assertSnoozeNotFound(session, deletedSourceNotification.getId());
    }

    private User newMember() {
        String value = suffix();
        User user = new User();
        user.setUsername("notification_user_" + value);
        user.setDisplayName("Notification User " + value);
        user.setEmail(value + "@example.com");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setTimezone("UTC");
        userMapper.insert(user);
        workspaceMapper.addMember(workspace.getId(), user.getId(), "member");
        return user;
    }

    private Notification notification(User recipient) {
        String value = suffix();
        Notification notification = new Notification();
        notification.setWorkspaceId(workspace.getId());
        notification.setRecipientId(recipient.getId());
        notification.setType("task.due");
        notification.setCategory("task");
        notification.setSeverity("warning");
        notification.setTemplateVersion(1);
        notification.setTitle("Task due " + value);
        notification.setBody("Task body");
        notification.setDedupeKey("task.due:" + value);
        notification.setTriggeredAt("2026-07-20 00:00:00");
        return notification;
    }

    private MockHttpSession login(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("username", username, "password", PASSWORD))))
            .andExpect(status().isOk())
            .andReturn();
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertNotNull(session);
        return session;
    }

    private void assertSnoozeNotFound(MockHttpSession session, int notificationId) throws Exception {
        mockMvc.perform(post("/api/notifications/{id}/snooze", notificationId)
                .header("X-Workspace-Id", workspace.getId())
                .session(session)
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("hours", 1))))
            .andExpect(status().isNotFound());
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
