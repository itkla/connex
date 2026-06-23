package ooo.klae.connex.backend.dto;

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
    private int id;
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
    private String createdAt;
    private String updatedAt;

    @JsonRawValue
    public String getData() {
        return data;
    }

    public static NotificationDto from(Notification notification) {
        NotificationDto dto = new NotificationDto();
        dto.setId(notification.getId());
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
        dto.setSourceLabel(notification.getSourceLabel());
        dto.setContextType(notification.getContextType());
        dto.setContextId(notification.getContextId());
        dto.setContextLabel(notification.getContextLabel());
        dto.setActionUrl(notification.getActionUrl());
        dto.setData(notification.getData());
        dto.setTriggeredAt(notification.getTriggeredAt());
        dto.setReadAt(notification.getReadAt());
        dto.setDismissedAt(notification.getDismissedAt());
        dto.setResolvedAt(notification.getResolvedAt());
        dto.setCreatedAt(notification.getCreatedAt());
        dto.setUpdatedAt(notification.getUpdatedAt());
        return dto;
    }
}