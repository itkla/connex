package ooo.klae.connex.backend.beans;

import java.time.LocalDateTime;

import lombok.Data;
import lombok.NoArgsConstructor;

/** Immutable provenance for a canonical workflow installed from a curated recipe. */
@Data
@NoArgsConstructor
public class WorkflowRecipeOrigin {
    private int workspaceId;
    private int workflowId;
    private String recipeKey;
    private int recipeVersion;
    private byte[] templateHash;
    private Integer installedById;
    private LocalDateTime installedAt;
}
