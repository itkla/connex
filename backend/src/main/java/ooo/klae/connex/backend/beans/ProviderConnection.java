package ooo.klae.connex.backend.beans;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * A user's OAuth connection to an external mail/calendar provider. Tokens live in the secret
 * store under the user scope; this row carries only the opaque reference and display metadata.
 */
@Data
@NoArgsConstructor
@ToString(exclude = "credentialRef")
public class ProviderConnection {
    private int id;
    private int userId;
    private String provider;
    private String status;
    private String providerAccountEmail;
    private String providerAccountId;
    private String grantedScopes;
    private String credentialRef;
    private long credentialGeneration;
    private String accessTokenExpiresAt;
    private String refreshLeaseOwner;
    private String refreshLeaseUntil;
    private String disconnectingAt;
    private String disconnectAttemptAt;
    private boolean captureReconcileRequired;
    private int captureReconcileAfterWorkspaceId;
    private String captureReconcileLeaseOwner;
    private String captureReconcileLeaseUntil;
    private String captureReconcileNextAttemptAt;
    private int captureReconcileFailures;
    private String lastSyncAt;
    private String errorCode;
    private String createdAt;
    private String updatedAt;
}
