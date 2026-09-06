package ooo.klae.connex.backend.publicapi;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import lombok.Data;
import lombok.NoArgsConstructor;

/** Persisted hash-only metadata for one workspace-bound personal API credential. */
@Data
@NoArgsConstructor
public class ApiCredential {
    private long id;
    private int workspaceId;
    private int organizationId;
    private int createdById;
    private long membershipId;
    private String name;
    private String tokenHash;
    private String tokenLast4;
    private LocalDateTime expiresAt;
    private LocalDateTime lastUsedAt;
    private LocalDateTime revokedAt;
    private Integer revokedById;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private List<String> scopes = new ArrayList<>();
}
