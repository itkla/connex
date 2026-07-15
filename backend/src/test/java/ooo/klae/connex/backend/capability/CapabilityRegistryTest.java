package ooo.klae.connex.backend.capability;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.config.DeploymentProperties;
import ooo.klae.connex.backend.mail.MailProperties;
import ooo.klae.connex.backend.services.BusinessCardService;
import ooo.klae.connex.backend.services.SsoConnectionService;
import ooo.klae.connex.backend.sso.SocialLoginClientRegistrations;

@ExtendWith(MockitoExtension.class)
class CapabilityRegistryTest {

    @Mock private SsoConnectionService ssoConnectionService;
    @Mock private SocialLoginClientRegistrations socialLoginClientRegistrations;
    @Mock private MailProperties mailProperties;
    @Mock private BusinessCardService businessCardService;
    @Mock private DeploymentProperties deploymentProperties;

    private CapabilityRegistry capabilityRegistry;

    @BeforeEach
    void setUp() {
        capabilityRegistry = new CapabilityRegistry(ssoConnectionService,
                socialLoginClientRegistrations, mailProperties, businessCardService,
                deploymentProperties, capability -> true);
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
                mailProperties,
                businessCardService,
                deploymentProperties,
                capability -> true,
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
                managedMailProperties,
                businessCardService,
                new DeploymentProperties(),
                capability -> capability != Capability.MANAGED_MAIL);

        assertFalse(restrictedRegistry.isAvailable(Capability.MANAGED_MAIL));
    }

    @Test
    void entitlementAndOperatorSettingPermitAvailability() {
        when(deploymentProperties.isConfigured()).thenReturn(false);
        when(ssoConnectionService.isInstanceEnabled()).thenReturn(true);

        assertTrue(capabilityRegistry.isAvailable(Capability.SSO));
    }

    @Test
    void nullCapabilityIsRejected() {
        assertThrows(NullPointerException.class, () -> capabilityRegistry.isAvailable(null));
    }
}
