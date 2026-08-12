package ooo.klae.connex.backend.beans;

import lombok.Data;

/** Tenant-local workspace AI availability and assistant turn limits. */
@Data
public class AiWorkspaceGovernance {
    private int workspaceId;
    private boolean aiEnabled;
    private int assistantMaxSteps;
}
