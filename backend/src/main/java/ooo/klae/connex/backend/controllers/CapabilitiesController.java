package ooo.klae.connex.backend.controllers;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.capability.Capability;
import ooo.klae.connex.backend.capability.CapabilityRegistry;

/**
 * Public instance-level product capability endpoint.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class CapabilitiesController {

    private final CapabilityRegistry capabilityRegistry;

    /**
     * Returns the instance capabilities available to the current client.
     *
     * @return composed capability response
     */
    @GetMapping("/capabilities")
    public CapabilitiesResponse capabilities() {
        return new CapabilitiesResponse(
                capabilityRegistry.isAvailable(Capability.SSO),
                new SocialLogin(
                        capabilityRegistry.isAvailable(Capability.SOCIAL_LOGIN_GOOGLE),
                        capabilityRegistry.isAvailable(Capability.SOCIAL_LOGIN_MICROSOFT)),
                capabilityRegistry.isAvailable(Capability.MANAGED_MAIL),
                capabilityRegistry.isAvailable(Capability.BUSINESS_CARD_SCANNING));
    }

    /**
     * Instance capability response.
     *
     * @param sso whether organization SSO is available
     * @param socialLogin available social-login providers
     * @param mailManaged whether instance-managed mail is enabled
     * @param businessCardScanning whether local OCR and durable card retention are ready
     */
    public record CapabilitiesResponse(
            boolean sso,
            SocialLogin socialLogin,
            boolean mailManaged,
            boolean businessCardScanning) {
    }

    /**
     * Social-login provider availability.
     *
     * @param google whether Google social login is available
     * @param microsoft whether Microsoft social login is available
     */
    public record SocialLogin(boolean google, boolean microsoft) {
    }
}
