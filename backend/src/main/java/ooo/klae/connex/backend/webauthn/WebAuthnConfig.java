package ooo.klae.connex.backend.webauthn;

import java.util.LinkedHashSet;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRpEntity;
import org.springframework.security.web.webauthn.management.PublicKeyCredentialUserEntityRepository;
import org.springframework.security.web.webauthn.management.UserCredentialRepository;
import org.springframework.security.web.webauthn.management.WebAuthnRelyingPartyOperations;
import org.springframework.security.web.webauthn.management.Webauthn4JRelyingPartyOperations;

/**
 * Wires Spring Security's webauthn4j-backed relying-party operations against the MyBatis
 * credential repositories. The operations object performs attestation/assertion verification;
 * the ceremony endpoints in {@code WebAuthnController} drive it imperatively so a passkey login
 * completes through the same {@code AuthService} session ceremony as password login.
 */
@Configuration
public class WebAuthnConfig {

    @Bean
    public WebAuthnRelyingPartyOperations webAuthnRelyingPartyOperations(
            PublicKeyCredentialUserEntityRepository userEntities,
            UserCredentialRepository userCredentials,
            WebAuthnProperties properties) {
        PublicKeyCredentialRpEntity rpEntity = PublicKeyCredentialRpEntity.builder()
            .id(properties.getRpId())
            .name(properties.getRpName())
            .build();
        return new Webauthn4JRelyingPartyOperations(
            userEntities,
            userCredentials,
            rpEntity,
            new LinkedHashSet<>(properties.getAllowedOrigins()));
    }
}
