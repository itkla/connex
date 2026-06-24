package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.Positive;

import lombok.Data;

/**
 * Request body for sharing a record with another workspace.
 */
@Data
public class ShareRequest {
    @Positive
    private int workspaceId;

    private boolean canEdit;
}
