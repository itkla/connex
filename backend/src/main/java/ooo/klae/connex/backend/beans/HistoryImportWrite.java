package ooo.klae.connex.backend.beans;

import lombok.Data;

/**
 * Mapper-bound interaction-history row with canonical fields and provenance.
 */
@Data
public class HistoryImportWrite {
    private int workspaceId;
    private int personId;
    private int actorId;
    private String occurredAt;
    private String type;
    private String subject;
    private String notes;
    private String content;
    private String title;
    private String description;
    private String dueDate;
    private boolean completed;
    private String status;
    private int position;
    private String historyImportKey;
    private String historyPayloadHash;
    private String historySourceId;
    private String historySourceRowRef;
}
