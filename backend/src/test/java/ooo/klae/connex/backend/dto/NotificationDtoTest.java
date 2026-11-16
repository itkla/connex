package ooo.klae.connex.backend.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.beans.Notification;
import tools.jackson.databind.ObjectMapper;

class NotificationDtoTest {

    @Test
    void invitationDeadlineIsSerializedAsUtc() {
        Notification notification = new Notification();
        notification.setType("workspace.join");
        notification.setInvitationExpiresAt("2026-11-15 12:30:00");

        var json = new ObjectMapper().valueToTree(NotificationDto.from(notification));

        assertEquals("2026-11-15T12:30:00Z", json.get("invitationExpiresAt").asString());
    }

    @Test
    void ordinaryNoticeHasNoInvitationDeadline() {
        assertNull(NotificationDto.from(new Notification()).getInvitationExpiresAt());
    }

    @Test
    void mentionProjectionClearsLegacySourceLabels() {
        Notification notification = new Notification();
        notification.setType("task.mention");
        notification.setSourceLabel("Private note title");

        assertNull(NotificationDto.from(notification).getSourceLabel());
    }

    @Test
    void replyProjectionClearsPersistedAttachmentLabels() {
        Notification notification = new Notification();
        notification.setType("comment.reply");
        notification.setSourceLabel("@Acquisition-Target-Secret.pdf");

        assertNull(NotificationDto.from(notification).getSourceLabel());
    }

    @Test
    void nonMentionProjectionKeepsSourceLabels() {
        Notification notification = new Notification();
        notification.setType("task.due");
        notification.setSourceLabel("Send proposal");

        assertEquals("Send proposal", NotificationDto.from(notification).getSourceLabel());
    }
}
