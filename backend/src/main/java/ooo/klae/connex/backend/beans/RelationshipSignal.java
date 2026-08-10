package ooo.klae.connex.backend.beans;

import java.time.LocalDateTime;

import lombok.Data;
import lombok.NoArgsConstructor;

/** Persisted canonical relationship signal and the requesting user's optional lifecycle state. */
@Data
@NoArgsConstructor
public class RelationshipSignal {
    private long id;
    private int workspaceId;
    private String family;
    private String subjectType;
    private int subjectId;
    private String subjectLabel;
    private String priority;
    private int priorityRank;
    private int rankValue;
    private String dedupeKey;
    private String evidenceJson;
    private String rankExplanationJson;
    private LocalDateTime evidenceAsOf;
    private String sourceStateHash;
    private String generationToken;
    private long version;
    private LocalDateTime resolvedAt;
    private String disposition;
    private LocalDateTime snoozeUntil;
    private String dismissedSourceHash;
    private Integer taskId;
    private String taskSourceHash;
    private Long stateVersion;
}
