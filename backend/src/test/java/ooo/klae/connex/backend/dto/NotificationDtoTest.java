package ooo.klae.connex.backend.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.beans.Notification;

class NotificationDtoTest {

    @Test
    void mentionProjectionClearsLegacySourceLabels() {
        Notification notification = new Notification();
        notification.setType("task.mention");
        notification.setSourceLabel("Private note title");

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
