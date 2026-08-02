package ooo.klae.connex.backend.beans;

import java.time.LocalDateTime;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Metadata-only outcome of one tenant-visible scheduled-job boundary.
 */
@Data
@NoArgsConstructor
public class JobRun {
    private int id;
    private String jobName;
    private Integer workspaceId;
    private String status;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private String detail;
}
