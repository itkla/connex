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
import ooo.klae.connex.backend.delivery.DeliveryProperties;
import ooo.klae.connex.backend.mail.MailProperties;
import ooo.klae.connex.backend.services.BusinessCardService;
import ooo.klae.connex.backend.services.SsoConnectionService;
import ooo.klae.connex.backend.sso.SocialLoginClientRegistrations;

/**
 * Resolves whether instance-level product capabilities are currently available.
 */
@Service
public class CapabilityRegistry {

    private static final Map<Capability, Set<String>> FORBIDDEN_PROFILES = Map.of(
            Capability.SSO, Set.of(),
            Capability.SOCIAL_LOGIN_GOOGLE, Set.of(),
            Capability.SOCIAL_LOGIN_MICROSOFT, Set.of(),
            Capability.CONNECTED_ACCOUNTS_GOOGLE, Set.of(),
            Capability.CONNECTED_ACCOUNTS_MICROSOFT, Set.of(),
            Capability.MANAGED_MAIL, Set.of(),
            Capability.BUSINESS_CARD_SCANNING, Set.of(),
            Capability.BUSINESS_CARD_IMPORT, Set.of(),
            Capability.CAMPAIGN_DELIVERY, Set.of());

    private final SsoConnectionService ssoConnectionService;
    private final SocialLoginClientRegistrations socialLoginClientRegistrations;
    private final ConnectedAccountProviders connectedAccountProviders;
    private final MailProperties mailProperties;
    private final BusinessCardService businessCardService;
    private final DeploymentProperties deploymentProperties;
    private final CapabilityEntitlement capabilityEntitlement;
    private final DeliveryProperties deliveryProperties;
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
     */
    @Autowired
    public CapabilityRegistry(SsoConnectionService ssoConnectionService,
            SocialLoginClientRegistrations socialLoginClientRegistrations,
            ConnectedAccountProviders connectedAccountProviders,
            MailProperties mailProperties,
            BusinessCardService businessCardService,
            DeploymentProperties deploymentProperties,
            CapabilityEntitlement capabilityEntitlement,
            DeliveryProperties deliveryProperties) {
        this(ssoConnectionService, socialLoginClientRegistrations, connectedAccountProviders,
                mailProperties, businessCardService,
                deploymentProperties, capabilityEntitlement, deliveryProperties, FORBIDDEN_PROFILES);
    }

    CapabilityRegistry(SsoConnectionService ssoConnectionService,
            SocialLoginClientRegistrations socialLoginClientRegistrations,
            ConnectedAccountProviders connectedAccountProviders,
            MailProperties mailProperties,
            BusinessCardService businessCardService,
            DeploymentProperties deploymentProperties,
            CapabilityEntitlement capabilityEntitlement,
            DeliveryProperties deliveryProperties,
            Map<Capability, Set<String>> forbiddenProfiles) {
        this.ssoConnectionService = Objects.requireNonNull(ssoConnectionService, "ssoConnectionService");
        this.socialLoginClientRegistrations = Objects.requireNonNull(
                socialLoginClientRegistrations, "socialLoginClientRegistrations");
        this.connectedAccountProviders = Objects.requireNonNull(
                connectedAccountProviders, "connectedAccountProviders");
        this.mailProperties = Objects.requireNonNull(mailProperties, "mailProperties");
        this.businessCardService = Objects.requireNonNull(businessCardService, "businessCardService");
        this.deploymentProperties = Objects.requireNonNull(deploymentProperties, "deploymentProperties");
        this.capabilityEntitlement = Objects.requireNonNull(capabilityEntitlement, "capabilityEntitlement");
        this.deliveryProperties = Objects.requireNonNull(deliveryProperties, "deliveryProperties");
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
            case MANAGED_MAIL -> mailProperties.isManaged();
            case BUSINESS_CARD_SCANNING -> businessCardService.isAvailable();
            case BUSINESS_CARD_IMPORT -> businessCardService.isImportAvailable();
            case CAMPAIGN_DELIVERY -> deliveryProperties.isEnabled();
        };
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
