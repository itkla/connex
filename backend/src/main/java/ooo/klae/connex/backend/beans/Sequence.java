package ooo.klae.connex.backend.beans;

import java.time.LocalDateTime;
import java.time.LocalTime;

import lombok.Data;
import lombok.NoArgsConstructor;

/** Workspace-scoped sales sequence template. */
@Data
@NoArgsConstructor
public class Sequence {
    private int id;
    private int workspaceId;
    private String name;
    private String purpose;
    private Integer ownerId;
    private String visibility;
    private String status;
    private String timezone;
    private int weekdayMask;
    private LocalTime sendWindowStart;
    private LocalTime sendWindowEnd;
    private Integer createdById;
    private Integer updatedById;
    private LocalDateTime archivedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
