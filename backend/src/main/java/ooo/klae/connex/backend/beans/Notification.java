package ooo.klae.connex.backend.beans;

import com.fasterxml.jackson.annotation.JsonRawValue;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Durable per-recipient notification snapshot.
 */
@Data
@NoArgsConstructor
public class Notification {
    private int id;
    private int workspaceId;
    private String workspaceName;
    private int recipientId;
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
    private String dedupeKey;
    private String triggeredAt;
    private String readAt;
    private String dismissedAt;
    private String resolvedAt;
    private String snoozedUntil;
    private String createdAt;
    private String updatedAt;

    @JsonRawValue
    public String getData() {
        return data;
    }
}