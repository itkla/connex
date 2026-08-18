package ooo.klae.connex.backend.beans;

import java.time.LocalDateTime;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One pass of a contact through the lead lifecycle (#559).
 *
 * <p>A contact may enter, be disqualified, be recycled, and enter again. Every question the epic
 * asks reporting — volume, qualification rate, conversion rate, time to convert, time to first
 * response — is a statement about one pass, so the pass rather than the contact is the unit of
 * analysis, and each pass keeps its own milestones and response outcome even after it closes.
 *
 * <p>Mapped via {@code PersonLifecyclePassMapper} / {@code PersonLifecyclePassMapper.xml}.
 */
@Data
@NoArgsConstructor
public class PersonLifecyclePass {
    private long id;
    private int workspaceId;
    private int personId;
    private LocalDateTime enteredAt;
    private LocalDateTime qualifiedAt;
    private LocalDateTime convertedAt;
    private LocalDateTime disqualifiedAt;
    private LocalDateTime endedAt;
    private LocalDateTime firstResponseStartedAt;
    private LocalDateTime firstRespondedAt;
    private LocalDateTime firstResponseDueAt;
    private LocalDateTime firstResponseBreachedAt;
    private Integer ownerId;
}
