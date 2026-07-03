package ooo.klae.connex.backend.webauthn;

import java.time.Instant;

import lombok.Data;

/**
 * Persistence row for {@code webauthn_user_entity}: the stable WebAuthn user handle
 * mapped to an {@code app_user}. Translated to and from Spring Security's
 * {@code PublicKeyCredentialUserEntity} by {@link MyBatisPublicKeyCredentialUserEntityRepository}.
 */
@Data
public class WebauthnUserEntityRow {
    private String id;
    private int userId;
    private String name;
    private String displayName;
    private Instant createdAt;
}
