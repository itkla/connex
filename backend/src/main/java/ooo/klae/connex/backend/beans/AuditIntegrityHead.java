package ooo.klae.connex.backend.beans;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class AuditIntegrityHead {
    private String scopeType;
    private int scopeId;
    private long nextChainIndex;
    private String currentHash;
}
