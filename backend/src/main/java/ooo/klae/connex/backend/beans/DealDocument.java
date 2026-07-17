package ooo.klae.connex.backend.beans;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A generated commercial document on a deal. {@code content} is an immutable JSON snapshot written
 * once at generation (see {@code DocumentContent}); only {@code status} transitions afterward.
 */
@Data
@NoArgsConstructor
public class DealDocument {
    private int id;
    private int workspaceId;
    private int dealId;
    private Integer templateId;
    private String type;
    private String locale;
    private String status;
    private int version;
    private String title;
    private String content;
    private String currency;
    private String generatedAt;
    private Integer createdBy;
    private String createdAt;
    private String updatedAt;
}
