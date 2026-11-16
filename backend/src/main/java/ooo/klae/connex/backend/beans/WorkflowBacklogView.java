package ooo.klae.connex.backend.beans;

import java.time.LocalDateTime;

import lombok.Data;
import lombok.NoArgsConstructor;

/** Backlog counts and the latest completed outcome for one workflow. */
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
    private boolean latestOutcomeFailed;
}
