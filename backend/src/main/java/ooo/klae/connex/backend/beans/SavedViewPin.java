package ooo.klae.connex.backend.beans;

import lombok.Data;
import lombok.NoArgsConstructor;

/** A caller's workspace-scoped pin preference for one saved view. */
@Data
@NoArgsConstructor
public class SavedViewPin {
    private int workspaceId;
    private int userId;
    private int savedViewId;
    private int position;
    private String createdAt;
    private String updatedAt;
}
