package ooo.klae.connex.backend.webauthn;

import java.util.LinkedHashSet;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.web.webauthn.api.AuthenticatorSelectionCriteria;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRpEntity;
import org.springframework.security.web.webauthn.api.ResidentKeyRequirement;
import org.springframework.security.web.webauthn.api.UserVerificationRequirement;
import org.springframework.security.web.webauthn.authentication.HttpSessionPublicKeyCredentialRequestOptionsRepository;
import org.springframework.security.web.webauthn.authentication.PublicKeyCredentialRequestOptionsRepository;
import org.springframework.security.web.webauthn.management.PublicKeyCredentialUserEntityRepository;
import org.springframework.security.web.webauthn.management.UserCredentialRepository;
import org.springframework.security.web.webauthn.management.WebAuthnRelyingPartyOperations;
import org.springframework.security.web.webauthn.management.Webauthn4JRelyingPartyOperations;
import org.springframework.security.web.webauthn.registration.HttpSessionPublicKeyCredentialCreationOptionsRepository;
import org.springframework.security.web.webauthn.registration.PublicKeyCredentialCreationOptionsRepository;

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
        Webauthn4JRelyingPartyOperations operations = new Webauthn4JRelyingPartyOperations(
            userEntities,
            userCredentials,
            rpEntity,
            new LinkedHashSet<>(properties.getAllowedOrigins()));
        operations.setCustomizeCreationOptions(builder -> builder.authenticatorSelection(
            AuthenticatorSelectionCriteria.builder()
                .residentKey(ResidentKeyRequirement.REQUIRED)
                .userVerification(UserVerificationRequirement.REQUIRED)
                .build()));
        operations.setCustomizeRequestOptions(builder ->
            builder.userVerification(UserVerificationRequirement.REQUIRED));
        return operations;
    }

    @Bean
    public PublicKeyCredentialCreationOptionsRepository creationOptionsRepository() {
        return new HttpSessionPublicKeyCredentialCreationOptionsRepository();
    }

    @Bean
    public PublicKeyCredentialRequestOptionsRepository requestOptionsRepository() {
        return new HttpSessionPublicKeyCredentialRequestOptionsRepository();
    }
}
