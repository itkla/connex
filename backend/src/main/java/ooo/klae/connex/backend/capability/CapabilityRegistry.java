package ooo.klae.connex.backend.capability;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import ooo.klae.connex.backend.config.DeploymentProperties;
import ooo.klae.connex.backend.connectedaccounts.ConnectedAccountProviders;
import ooo.klae.connex.backend.connectedaccounts.ConnectedCaptureProperties;
import ooo.klae.connex.backend.delivery.DeliveryProperties;
import ooo.klae.connex.backend.mail.MailProperties;
import ooo.klae.connex.backend.services.BusinessCardService;
import ooo.klae.connex.backend.services.SsoConnectionService;
import ooo.klae.connex.backend.signature.SignatureProperties;
import ooo.klae.connex.backend.sso.SocialLoginClientRegistrations;

/**
 * Resolves whether instance-level product capabilities are currently available.
 */
@Service
public class CapabilityRegistry {

    /**
     * Declares the deployment-edition policy: the profiles under which each capability is
     * refused regardless of entitlement, rollout, or operator configuration.
     *
     * <p>Managed mail is the only profile-constrained capability. It is transport operated by
     * Connex on the customer's behalf, which cannot exist in an on-prem installation the
     * customer runs itself; an on-prem operator configures their own SMTP instead. Every other
     * capability is deliberately profile-neutral because its availability is already decided by
     * its own operator setting — the deployment edition says nothing about whether an operator
     * wants SSO, social login, connected accounts, card scanning, or campaign delivery.
     *
     * <p>Every capability is listed explicitly, and {@code CapabilityRegistryTest} pins this
     * table literally, so adding a capability forces a deliberate profile decision here.
     */
    private static final Map<Capability, Set<String>> FORBIDDEN_PROFILES = Map.ofEntries(
            Map.entry(Capability.SSO, Set.of()),
            Map.entry(Capability.SOCIAL_LOGIN_GOOGLE, Set.of()),
            Map.entry(Capability.SOCIAL_LOGIN_MICROSOFT, Set.of()),
            Map.entry(Capability.CONNECTED_ACCOUNTS_GOOGLE, Set.of()),
            Map.entry(Capability.CONNECTED_ACCOUNTS_MICROSOFT, Set.of()),
            Map.entry(Capability.CONNECTED_CAPTURE_GOOGLE, Set.of()),
            Map.entry(Capability.CONNECTED_CAPTURE_MICROSOFT, Set.of()),
            Map.entry(Capability.MANAGED_MAIL, Set.of(DeploymentProperties.PROFILE_ON_PREM)),
            Map.entry(Capability.BUSINESS_CARD_SCANNING, Set.of()),
            Map.entry(Capability.BUSINESS_CARD_IMPORT, Set.of()),
            Map.entry(Capability.CAMPAIGN_DELIVERY, Set.of()),
            Map.entry(Capability.DOCUMENT_SIGNATURE, Set.of()));

    private static final Map<Capability, Set<String>> PRODUCTION_PROFILE_POLICY =
            immutableForbiddenProfiles(FORBIDDEN_PROFILES);

    private final SsoConnectionService ssoConnectionService;
    private final SocialLoginClientRegistrations socialLoginClientRegistrations;
    private final ConnectedAccountProviders connectedAccountProviders;
    private final ConnectedCaptureProperties connectedCaptureProperties;
    private final MailProperties mailProperties;
    private final BusinessCardService businessCardService;
    private final DeploymentProperties deploymentProperties;
    private final CapabilityEntitlement capabilityEntitlement;
    private final DeliveryProperties deliveryProperties;
    private final SignatureProperties signatureProperties;
    private final Map<Capability, Set<String>> forbiddenProfiles;

    /**
     * Creates the registry from the typed instance configuration sources.
     *
     * @param ssoConnectionService SSO capability source
     * @param socialLoginClientRegistrations social-login capability source
     * @param connectedAccountProviders connected-accounts capability source
     * @param mailProperties mail capability source
     * @param businessCardService business-card capability source
     * @param deploymentProperties active deployment profile
     * @param capabilityEntitlement capability entitlement source
     * @param deliveryProperties native delivery capability source
     * @param signatureProperties document-signature capability source
     */
    @Autowired
    public CapabilityRegistry(SsoConnectionService ssoConnectionService,
            SocialLoginClientRegistrations socialLoginClientRegistrations,
            ConnectedAccountProviders connectedAccountProviders,
            ConnectedCaptureProperties connectedCaptureProperties,
            MailProperties mailProperties,
            BusinessCardService businessCardService,
            DeploymentProperties deploymentProperties,
            CapabilityEntitlement capabilityEntitlement,
            DeliveryProperties deliveryProperties,
            SignatureProperties signatureProperties) {
        this(ssoConnectionService, socialLoginClientRegistrations, connectedAccountProviders,
                connectedCaptureProperties,
                mailProperties, businessCardService,
                deploymentProperties, capabilityEntitlement, deliveryProperties, signatureProperties,
                PRODUCTION_PROFILE_POLICY);
    }

    CapabilityRegistry(SsoConnectionService ssoConnectionService,
            SocialLoginClientRegistrations socialLoginClientRegistrations,
            ConnectedAccountProviders connectedAccountProviders,
            ConnectedCaptureProperties connectedCaptureProperties,
            MailProperties mailProperties,
            BusinessCardService businessCardService,
            DeploymentProperties deploymentProperties,
            CapabilityEntitlement capabilityEntitlement,
            DeliveryProperties deliveryProperties,
            SignatureProperties signatureProperties,
            Map<Capability, Set<String>> forbiddenProfiles) {
        this.ssoConnectionService = Objects.requireNonNull(ssoConnectionService, "ssoConnectionService");
        this.socialLoginClientRegistrations = Objects.requireNonNull(
                socialLoginClientRegistrations, "socialLoginClientRegistrations");
        this.connectedAccountProviders = Objects.requireNonNull(
                connectedAccountProviders, "connectedAccountProviders");
        this.connectedCaptureProperties = Objects.requireNonNull(
                connectedCaptureProperties, "connectedCaptureProperties");
        this.mailProperties = Objects.requireNonNull(mailProperties, "mailProperties");
        this.businessCardService = Objects.requireNonNull(businessCardService, "businessCardService");
        this.deploymentProperties = Objects.requireNonNull(deploymentProperties, "deploymentProperties");
        this.capabilityEntitlement = Objects.requireNonNull(capabilityEntitlement, "capabilityEntitlement");
        this.deliveryProperties = Objects.requireNonNull(deliveryProperties, "deliveryProperties");
        this.signatureProperties = Objects.requireNonNull(signatureProperties, "signatureProperties");
        this.forbiddenProfiles = immutableForbiddenProfiles(forbiddenProfiles);
    }

    /**
     * Returns whether every availability factor permits the requested capability.
     *
     * @param capability capability to evaluate
     * @return {@code true} when the capability is available
     */
    public boolean isAvailable(Capability capability) {
        Capability requiredCapability = Objects.requireNonNull(capability, "capability");
        return profileConstraint(requiredCapability)
                && entitlement(requiredCapability)
                && rollout(requiredCapability)
                && operatorSetting(requiredCapability);
    }

    /**
     * Returns availability using only last-known readiness, starting no storage or sidecar
     * probe. Read-only diagnostics use this so rendering a health report never itself
     * contacts the OCR sidecar or object storage.
     *
     * @param capability capability to evaluate
     * @return {@code true} when the capability is available as of the latest observation
     */
    public boolean isAvailableWithoutProbing(Capability capability) {
        Capability requiredCapability = Objects.requireNonNull(capability, "capability");
        return switch (requiredCapability) {
            case BUSINESS_CARD_SCANNING -> profileConstraint(requiredCapability)
                    && entitlement(requiredCapability)
                    && rollout(requiredCapability)
                    && businessCardService.isAvailableCached();
            case BUSINESS_CARD_IMPORT -> profileConstraint(requiredCapability)
                    && entitlement(requiredCapability)
                    && rollout(requiredCapability)
                    && businessCardService.isImportAvailableCached();
            default -> isAvailable(requiredCapability);
        };
    }

    private boolean profileConstraint(Capability capability) {
        if (!deploymentProperties.isConfigured()) {
            return true;
        }
        return !forbiddenProfiles.get(capability).contains(deploymentProperties.getProfile());
    }

    /**
     * Delegates the licensed or paid-for availability factor to the installed entitlement seam.
     */
    private boolean entitlement(Capability capability) {
        return capabilityEntitlement.isEntitled(capability);
    }

    /**
     * Rollout seam for future gradual capability releases.
     */
    private boolean rollout(Capability capability) {
        return true;
    }

    private boolean operatorSetting(Capability capability) {
        return switch (capability) {
            case SSO -> ssoConnectionService.isInstanceEnabled();
            case SOCIAL_LOGIN_GOOGLE -> socialLoginClientRegistrations.isGoogleEnabled();
            case SOCIAL_LOGIN_MICROSOFT -> socialLoginClientRegistrations.isMicrosoftEnabled();
            case CONNECTED_ACCOUNTS_GOOGLE -> connectedAccountProviders.isGoogleEnabled();
            case CONNECTED_ACCOUNTS_MICROSOFT -> connectedAccountProviders.isMicrosoftEnabled();
            case CONNECTED_CAPTURE_GOOGLE -> connectedAccountProviders.isGoogleEnabled()
                    && connectedCaptureProperties.isCaptureEnabled(ConnectedAccountProviders.GOOGLE);
            case CONNECTED_CAPTURE_MICROSOFT -> connectedAccountProviders.isMicrosoftEnabled()
                    && connectedCaptureProperties.isCaptureEnabled(ConnectedAccountProviders.MICROSOFT);
            case MANAGED_MAIL -> mailProperties.isManaged();
            case BUSINESS_CARD_SCANNING -> businessCardService.isAvailable();
            case BUSINESS_CARD_IMPORT -> businessCardService.isImportAvailable();
            case CAMPAIGN_DELIVERY -> deliveryProperties.isEnabled();
            case DOCUMENT_SIGNATURE -> signatureProperties.isEnabled();
        };
    }

    /**
     * Returns the production deployment-edition policy mapping every capability to the
     * deployment profiles that refuse it.
     *
     * @return unmodifiable policy covering every {@link Capability}
     */
    public static Map<Capability, Set<String>> deploymentProfilePolicy() {
        return PRODUCTION_PROFILE_POLICY;
    }

    /**
     * Returns whether the deployment-edition policy permits a capability under a profile.
     *
     * @param capability capability to evaluate
     * @param profile deployment profile, or {@code null} or blank when none is configured
     * @return {@code true} when no configured profile forbids the capability
     */
    public static boolean isAllowedForProfile(Capability capability, String profile) {
        Objects.requireNonNull(capability, "capability");
        if (profile == null || profile.isBlank()) {
            return true;
        }
        return !PRODUCTION_PROFILE_POLICY.get(capability).contains(profile);
    }

    private static Map<Capability, Set<String>> immutableForbiddenProfiles(
            Map<Capability, Set<String>> forbiddenProfiles) {
        Objects.requireNonNull(forbiddenProfiles, "forbiddenProfiles");
        EnumMap<Capability, Set<String>> immutableProfiles = new EnumMap<>(Capability.class);
        for (Capability capability : Capability.values()) {
            Set<String> profiles = forbiddenProfiles.getOrDefault(capability, Set.of());
            immutableProfiles.put(capability, Set.copyOf(profiles));
        }
        return Collections.unmodifiableMap(immutableProfiles);
    }
}
