package ooo.klae.connex.backend.controllers.v1;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.publicapi.ApiCredentialService;
import ooo.klae.connex.backend.publicapi.ApiCredentialService.CredentialIdentity;

/** Public v1 credential identity endpoint. */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "connex.public-api.enabled", havingValue = "true")
public class PublicApiIdentityController {
    private final ApiCredentialService apiCredentialService;

    /** Returns the secret-free identity of the authenticating credential. */
    @GetMapping("/me")
    public CredentialIdentity me() {
        return apiCredentialService.currentCredential();
    }
}
