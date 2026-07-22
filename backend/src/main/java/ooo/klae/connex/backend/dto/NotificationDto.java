package ooo.klae.connex.backend.dto;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

import com.fasterxml.jackson.annotation.JsonRawValue;

import lombok.Data;
import lombok.NoArgsConstructor;
import ooo.klae.connex.backend.beans.Notification;

/**
 * Flat notification response with source and context snapshots.
 */
@Data
@NoArgsConstructor
public class NotificationDto {
    private static final String MENTION_SUFFIX = ".mention";

    private int id;
    private int workspaceId;
    private String workspaceName;
    private String type;
    private String category;
    private String severity;
    private int templateVersion;
    private String title;
    private String body;
    private Integer actorId;
    private String actorLabel;
    private String sourceType;
    private Integer sourceId;
    private String sourceLabel;
    private String contextType;
    private Integer contextId;
    private String contextLabel;
    private String actionUrl;
    private String data;
    private String triggeredAt;
    private String readAt;
    private String dismissedAt;
    private String resolvedAt;
    private String snoozedUntil;
    private String snoozeTimezone;
    private String createdAt;
    private String updatedAt;
    private long stateVersion;

    @JsonRawValue
    public String getData() {
        return data;
    }

    public static NotificationDto from(Notification notification) {
        NotificationDto dto = new NotificationDto();
        dto.setId(notification.getId());
        dto.setWorkspaceId(notification.getWorkspaceId());
        dto.setWorkspaceName(notification.getWorkspaceName());
        dto.setType(notification.getType());
        dto.setCategory(notification.getCategory());
        dto.setSeverity(notification.getSeverity());
        dto.setTemplateVersion(notification.getTemplateVersion());
        dto.setTitle(notification.getTitle());
        dto.setBody(notification.getBody());
        dto.setActorId(notification.getActorId());
        dto.setActorLabel(notification.getActorLabel());
        dto.setSourceType(notification.getSourceType());
        dto.setSourceId(notification.getSourceId());
        dto.setSourceLabel(notification.getType() != null && notification.getType().endsWith(MENTION_SUFFIX)
            ? null
            : notification.getSourceLabel());
        dto.setContextType(notification.getContextType());
        dto.setContextId(notification.getContextId());
        dto.setContextLabel(notification.getContextLabel());
        dto.setActionUrl(notification.getActionUrl());
        dto.setData(notification.getData());
        dto.setTriggeredAt(notification.getTriggeredAt());
        dto.setReadAt(notification.getReadAt());
        dto.setDismissedAt(notification.getDismissedAt());
        dto.setResolvedAt(notification.getResolvedAt());
        dto.setSnoozedUntil(toUtcInstant(notification.getSnoozedUntil()));
        dto.setSnoozeTimezone(notification.getSnoozeTimezone());
        dto.setCreatedAt(notification.getCreatedAt());
        dto.setUpdatedAt(notification.getUpdatedAt());
        return dto;
    }

    private static String toUtcInstant(String value) {
        if (value == null || value.contains("T")) {
            return value;
        }
        try {
            return LocalDateTime.parse(value, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                .toInstant(ZoneOffset.UTC)
                .toString();
        } catch (DateTimeParseException exception) {
            throw new IllegalStateException("Invalid notification UTC timestamp", exception);
        }
    }
}
