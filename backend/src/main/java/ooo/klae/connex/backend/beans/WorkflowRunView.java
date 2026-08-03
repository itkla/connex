package ooo.klae.connex.backend.beans;

import java.time.LocalDateTime;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/** Canonical run row with immutable version summary fields for bounded read projections. */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class WorkflowRunView extends WorkflowRun {
    private int versionNumber;
    private byte[] versionDefinitionHash;
    private LocalDateTime versionPublishedAt;
}
