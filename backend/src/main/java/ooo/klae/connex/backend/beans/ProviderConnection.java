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
    private String grantedScopes;
    private String credentialRef;
    private String lastSyncAt;
    private String errorCode;
    private String createdAt;
    private String updatedAt;
}
