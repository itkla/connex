package ooo.klae.connex.backend.controllers;

import java.util.Locale;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.capability.Capability;
import ooo.klae.connex.backend.capability.CapabilityRegistry;
import ooo.klae.connex.backend.config.PrivilegedMfaProperties;
import ooo.klae.connex.backend.connectedaccounts.ConnectedAccountProviders;
import ooo.klae.connex.backend.services.WorkflowTriggeredSendGate;

/**
 * Public instance-level product capability endpoint.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class CapabilitiesController {

    private final CapabilityRegistry capabilityRegistry;
    private final PrivilegedMfaProperties privilegedMfaProperties;
    private final ConnectedAccountProviders connectedAccountProviders;
    private final WorkflowTriggeredSendGate workflowTriggeredSendGate;

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
                new ConnectedAccounts(
                        capabilityRegistry.isAvailable(Capability.CONNECTED_ACCOUNTS_GOOGLE),
                        capabilityRegistry.isAvailable(Capability.CONNECTED_ACCOUNTS_MICROSOFT)),
                new ConnectedAccountModes(
                        modeOf(ConnectedAccountProviders.GOOGLE),
                        modeOf(ConnectedAccountProviders.MICROSOFT)),
                new ConnectedCapture(
                        capabilityRegistry.isAvailable(Capability.CONNECTED_CAPTURE_GOOGLE),
                        capabilityRegistry.isAvailable(Capability.CONNECTED_CAPTURE_MICROSOFT)),
                capabilityRegistry.isAvailable(Capability.MANAGED_MAIL),
                capabilityRegistry.isAvailable(Capability.BUSINESS_CARD_SCANNING),
                capabilityRegistry.isAvailable(Capability.BUSINESS_CARD_IMPORT),
                capabilityRegistry.isAvailable(Capability.CAMPAIGN_DELIVERY),
                capabilityRegistry.isAvailable(Capability.DOCUMENT_SIGNATURE),
                workflowTriggeredSendGate.enabled(),
                privilegedMfaProperties.isEnforced());
    }

    private String modeOf(String provider) {
        return connectedAccountProviders.mode(provider).name().toLowerCase(Locale.ROOT);
    }

    /**
     * Instance capability response.
     *
     * @param sso whether organization SSO is available
     * @param socialLogin available social-login providers
     * @param connectedAccounts available connected-account providers
     * @param connectedAccountModes which OAuth client identity each connected-account provider uses
     * @param connectedCapture authorized capture providers
     * @param mailManaged whether instance-managed mail is enabled
     * @param businessCardScanning whether local OCR and durable card retention are ready
     * @param businessCardImport whether reviewed source-image import and retention are ready
     * @param campaignDelivery whether native email campaign delivery is available
     * @param documentSignature whether commercial-document signature delivery is available
     * @param workflowTriggeredSend whether triggered workflow delivery is enabled
     * @param privilegedMfaEnforced whether privileged accounts are subject to mandatory MFA
     */
    public record CapabilitiesResponse(
            boolean sso,
            SocialLogin socialLogin,
            ConnectedAccounts connectedAccounts,
            ConnectedAccountModes connectedAccountModes,
            ConnectedCapture connectedCapture,
            boolean mailManaged,
            boolean businessCardScanning,
            boolean businessCardImport,
            boolean campaignDelivery,
            boolean documentSignature,
            boolean workflowTriggeredSend,
            boolean privilegedMfaEnforced) {
    }

    /**
     * Social-login provider availability.
     *
     * @param google whether Google social login is available
     * @param microsoft whether Microsoft social login is available
     */
    public record SocialLogin(boolean google, boolean microsoft) {
    }

    /**
     * Connected-account provider availability.
     *
     * @param google whether Google connected accounts are available
     * @param microsoft whether Microsoft connected accounts are available
     */
    public record ConnectedAccounts(boolean google, boolean microsoft) {
    }

    /**
     * Which OAuth client identity each connected-account provider uses on this instance:
     * {@code managed} for the Connex-owned installed-application identity, {@code custom} for
     * operator-supplied credentials.
     *
     * @param google the Google connected-account credential mode
     * @param microsoft the Microsoft connected-account credential mode
     */
    public record ConnectedAccountModes(String google, String microsoft) {
    }

    /**
     * Connected-capture provider availability.
     *
     * @param google whether Google capture is authorized
     * @param microsoft whether Microsoft capture is authorized
     */
    public record ConnectedCapture(boolean google, boolean microsoft) {
    }
}
