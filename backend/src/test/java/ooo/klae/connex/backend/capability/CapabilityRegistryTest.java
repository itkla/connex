package ooo.klae.connex.backend.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.config.DeploymentProperties;
import ooo.klae.connex.backend.connectedaccounts.ConnectedAccountProviders;
import ooo.klae.connex.backend.connectedaccounts.ConnectedCaptureProperties;
import ooo.klae.connex.backend.delivery.DeliveryProperties;
import ooo.klae.connex.backend.mail.MailProperties;
import ooo.klae.connex.backend.services.BusinessCardService;
import ooo.klae.connex.backend.services.SsoConnectionService;
import ooo.klae.connex.backend.sso.SocialLoginClientRegistrations;

@ExtendWith(MockitoExtension.class)
class CapabilityRegistryTest {

    @Mock private SsoConnectionService ssoConnectionService;
    @Mock private SocialLoginClientRegistrations socialLoginClientRegistrations;
    @Mock private ConnectedAccountProviders connectedAccountProviders;
    @Mock private ConnectedCaptureProperties connectedCaptureProperties;
    @Mock private MailProperties mailProperties;
    @Mock private BusinessCardService businessCardService;
    @Mock private DeploymentProperties deploymentProperties;
    @Mock private DeliveryProperties deliveryProperties;

    private CapabilityRegistry capabilityRegistry;

    @BeforeEach
    void setUp() {
        capabilityRegistry = new CapabilityRegistry(ssoConnectionService,
                socialLoginClientRegistrations, connectedAccountProviders, connectedCaptureProperties,
                mailProperties, businessCardService,
                deploymentProperties, capability -> true, deliveryProperties);
    }

    @Test
    void operatorSettingsDetermineAvailabilityWhenProfileIsUnset() {
        when(deploymentProperties.isConfigured()).thenReturn(false);
        when(socialLoginClientRegistrations.isGoogleEnabled()).thenReturn(true);
        when(mailProperties.isManaged()).thenReturn(true);
        when(businessCardService.isAvailable()).thenReturn(true);
        when(businessCardService.isImportAvailable()).thenReturn(true);

        assertFalse(capabilityRegistry.isAvailable(Capability.SSO));
        assertTrue(capabilityRegistry.isAvailable(Capability.SOCIAL_LOGIN_GOOGLE));
        assertFalse(capabilityRegistry.isAvailable(Capability.SOCIAL_LOGIN_MICROSOFT));
        assertTrue(capabilityRegistry.isAvailable(Capability.MANAGED_MAIL));
        assertTrue(capabilityRegistry.isAvailable(Capability.BUSINESS_CARD_SCANNING));
        assertTrue(capabilityRegistry.isAvailable(Capability.BUSINESS_CARD_IMPORT));

        verify(ssoConnectionService).isInstanceEnabled();
        verify(socialLoginClientRegistrations).isGoogleEnabled();
        verify(socialLoginClientRegistrations).isMicrosoftEnabled();
        verify(mailProperties).isManaged();
        verify(businessCardService).isAvailable();
        verify(businessCardService).isImportAvailable();
    }

    @Test
    void operatorSettingRemainsAvailableUnderANonForbiddingProfile() {
        when(deploymentProperties.isConfigured()).thenReturn(true);
        when(deploymentProperties.getProfile()).thenReturn(DeploymentProperties.PROFILE_SAAS);
        when(ssoConnectionService.isInstanceEnabled()).thenReturn(true);

        assertTrue(capabilityRegistry.isAvailable(Capability.SSO));
    }

    @Test
    void forbiddenProfileOverridesTheOperatorSetting() {
        CapabilityRegistry restrictedRegistry = new CapabilityRegistry(
                ssoConnectionService,
                socialLoginClientRegistrations,
                connectedAccountProviders,
                connectedCaptureProperties,
                mailProperties,
                businessCardService,
                deploymentProperties,
                capability -> true,
                deliveryProperties,
                Map.of(Capability.SSO, Set.of(DeploymentProperties.PROFILE_SAAS)));
        when(deploymentProperties.isConfigured()).thenReturn(true);
        when(deploymentProperties.getProfile()).thenReturn(DeploymentProperties.PROFILE_SAAS);

        assertFalse(restrictedRegistry.isAvailable(Capability.SSO));

        verifyNoInteractions(ssoConnectionService);
    }

    @Test
    void entitlementDenialOverridesEnabledOperatorSetting() {
        MailProperties managedMailProperties = new MailProperties();
        managedMailProperties.setManaged(true);
        CapabilityRegistry restrictedRegistry = new CapabilityRegistry(
                ssoConnectionService,
                socialLoginClientRegistrations,
                connectedAccountProviders,
                new ConnectedCaptureProperties(),
                managedMailProperties,
                businessCardService,
                new DeploymentProperties(),
                capability -> capability != Capability.MANAGED_MAIL,
                new DeliveryProperties());

        assertFalse(restrictedRegistry.isAvailable(Capability.MANAGED_MAIL));
    }

    @Test
    void entitlementAndOperatorSettingPermitAvailability() {
        when(deploymentProperties.isConfigured()).thenReturn(false);
        when(ssoConnectionService.isInstanceEnabled()).thenReturn(true);

        assertTrue(capabilityRegistry.isAvailable(Capability.SSO));
    }

    @Test
    void captureRequiresConnectionSchedulingAndProviderIngestionAuthorization() {
        when(connectedAccountProviders.isGoogleEnabled()).thenReturn(true);
        when(connectedCaptureProperties.isCaptureEnabled("google"))
            .thenReturn(false, true);

        assertFalse(capabilityRegistry.isAvailable(
            Capability.CONNECTED_CAPTURE_GOOGLE));
        assertTrue(capabilityRegistry.isAvailable(
            Capability.CONNECTED_CAPTURE_GOOGLE));
    }

    @Test
    void nullCapabilityIsRejected() {
        assertThrows(NullPointerException.class, () -> capabilityRegistry.isAvailable(null));
    }

    @Test
    void productionDeploymentProfileMatrixIsPinned() {
        Map<Capability, Set<String>> expected = new EnumMap<>(Capability.class);
        expected.put(Capability.SSO, Set.of());
        expected.put(Capability.SOCIAL_LOGIN_GOOGLE, Set.of());
        expected.put(Capability.SOCIAL_LOGIN_MICROSOFT, Set.of());
        expected.put(Capability.CONNECTED_ACCOUNTS_GOOGLE, Set.of());
        expected.put(Capability.CONNECTED_ACCOUNTS_MICROSOFT, Set.of());
        expected.put(Capability.CONNECTED_CAPTURE_GOOGLE, Set.of());
        expected.put(Capability.CONNECTED_CAPTURE_MICROSOFT, Set.of());
        expected.put(Capability.MANAGED_MAIL, Set.of(DeploymentProperties.PROFILE_ON_PREM));
        expected.put(Capability.BUSINESS_CARD_SCANNING, Set.of());
        expected.put(Capability.BUSINESS_CARD_IMPORT, Set.of());
        expected.put(Capability.CAMPAIGN_DELIVERY, Set.of());

        assertEquals(EnumSet.allOf(Capability.class), expected.keySet(),
                "A capability was added without pinning its deployment-profile policy");
        assertEquals(expected, CapabilityRegistry.deploymentProfilePolicy());
    }

    @Test
    void productionProfilePolicyRefusesOnlyManagedMailOnPrem() {
        List<String> profiles = List.of(DeploymentProperties.PROFILE_SAAS,
                DeploymentProperties.PROFILE_SILO, DeploymentProperties.PROFILE_ON_PREM);

        for (Capability capability : Capability.values()) {
            for (String profile : profiles) {
                boolean expectedAllowed = !(capability == Capability.MANAGED_MAIL
                        && DeploymentProperties.PROFILE_ON_PREM.equals(profile));
                assertEquals(expectedAllowed, CapabilityRegistry.isAllowedForProfile(capability, profile),
                        capability + " under profile " + profile);
            }
            assertTrue(CapabilityRegistry.isAllowedForProfile(capability, null),
                    capability + " with no configured profile");
        }
    }

    @Test
    void managedMailIsRefusedOnPremRegardlessOfOperatorSetting() {
        when(deploymentProperties.isConfigured()).thenReturn(true);
        when(deploymentProperties.getProfile()).thenReturn(DeploymentProperties.PROFILE_ON_PREM);

        assertFalse(capabilityRegistry.isAvailable(Capability.MANAGED_MAIL));

        verifyNoInteractions(mailProperties);
    }

    @Test
    void managedMailRemainsAvailableUnderSaasAndSilo() {
        when(deploymentProperties.isConfigured()).thenReturn(true);
        when(deploymentProperties.getProfile())
                .thenReturn(DeploymentProperties.PROFILE_SAAS, DeploymentProperties.PROFILE_SILO);
        when(mailProperties.isManaged()).thenReturn(true);

        assertTrue(capabilityRegistry.isAvailable(Capability.MANAGED_MAIL));
        assertTrue(capabilityRegistry.isAvailable(Capability.MANAGED_MAIL));
    }
}
