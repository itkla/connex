package ooo.klae.connex.backend.publicapi;

import lombok.Data;
import lombok.NoArgsConstructor;

/** One workspace-anchored credential-scope row used for bounded batch hydration. */
@Data
@NoArgsConstructor
public class ApiCredentialScope {
    private long credentialId;
    private String scope;
}
