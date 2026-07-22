package ooo.klae.connex.backend.beans;

import lombok.Data;
import lombok.NoArgsConstructor;

/** A caller's workspace-scoped default saved view for one record type. */
@Data
@NoArgsConstructor
public class SavedViewDefault {
    private int workspaceId;
    private int userId;
    private String recordType;
    private int savedViewId;
    private String createdAt;
    private String updatedAt;
}
