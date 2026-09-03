package ooo.klae.connex.backend.beans;

import java.time.LocalDateTime;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Immutable published definition of one sales sequence.
 * The publisher is a joined projection from the separately mutable attribution pointer.
 */
@Data
@NoArgsConstructor
public class SequenceVersion {
    private long id;
    private int workspaceId;
    private int sequenceId;
    private int versionNumber;
    private String definitionJson;
    private byte[] definitionHash;
    private Integer publishedById;
    private LocalDateTime createdAt;
}
