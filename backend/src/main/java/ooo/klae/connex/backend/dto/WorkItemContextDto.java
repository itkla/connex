package ooo.klae.connex.backend.dto;

/** Current source-owned navigation and approval-step context for a work item. */
public record WorkItemContextDto(
    String type,
    int id,
    String label,
    String href,
    Integer stepId,
    Integer stepOrder,
    String stepName,
    Integer requiredCount,
    Boolean escalated
) {
    /** Creates context without approval-step metadata. */
    public WorkItemContextDto(String type, int id, String label, String href) {
        this(type, id, label, href, null, null, null, null, null);
    }
}
