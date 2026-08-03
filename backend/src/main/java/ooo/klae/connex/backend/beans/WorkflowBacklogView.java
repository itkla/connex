package ooo.klae.connex.backend.beans;

import java.time.LocalDateTime;

import lombok.Data;
import lombok.NoArgsConstructor;

/** Bounded aggregate of queued and waiting work for one workflow. */
@Data
@NoArgsConstructor
public class WorkflowBacklogView {
    private int queuedCount;
    private LocalDateTime oldestQueuedAt;
    private int waitingCount;
    private int dueNowCount;
    private int overdueCount;
    private LocalDateTime nextResumeAt;
    private int recentFailureCount;
}
