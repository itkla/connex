package ooo.klae.connex.backend.dto;

import java.time.LocalDateTime;

import lombok.Data;

@Data
public class AppiIncidentScopeDto {
    private Integer workspaceId;
    private String workspaceName;
    private String entityType;
    private String action;
    private String outcome;
    private long eventCount;
    private LocalDateTime firstSeenAt;
    private LocalDateTime lastSeenAt;
}
