package ooo.klae.connex.backend.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A workspace a record is shared with.
 */
@Data
@NoArgsConstructor
public class ShareDto {
    private int workspaceId;
    private String workspaceName;
    private boolean canEdit;
    private String createdAt;
}
