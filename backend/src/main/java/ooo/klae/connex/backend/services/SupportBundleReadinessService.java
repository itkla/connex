package ooo.klae.connex.backend.services;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.AiProviderReadiness;
import ooo.klae.connex.backend.capability.Capability;
import ooo.klae.connex.backend.capability.CapabilityRegistry;
import ooo.klae.connex.backend.config.DeploymentProperties;

/**
 * Builds the readiness projection carried by a support bundle.
 *
 * <p>Everything here is a boolean, a profile name, or a stable code. Provider hosts, endpoints,
 * regions, model identifiers, sender identities and credential-presence detail are all excluded:
 * they identify the tenant or a third party, and a support engineer diagnosing a ticket needs to
 * know whether a capability is available, not where it points.
 */
@Service
@RequiredArgsConstructor
public class SupportBundleReadinessService {

    /**
     * Marks the projection as the bundle-local fallback rather than the aggregated tenant
     * diagnostics payload, so a reader knows which source produced it.
     */
    public static final String FALLBACK_SOURCE = "support_bundle_fallback";

    private final DeploymentProperties deploymentProperties;
    private final CapabilityRegistry capabilityRegistry;
    private final AiProviderReadiness aiProviderReadiness;

    /**
     * Returns the readiness projection for one organization.
     *
     * @param orgId the organization the bundle was requested for
     * @return the readiness projection
     */
    public Map<String, Object> readiness(int orgId) {
        Map<String, Object> readiness = new LinkedHashMap<>();
        readiness.put("source", FALLBACK_SOURCE);
        readiness.put("profile", deploymentProperties.getProfile());
        readiness.put("capabilities", capabilities());
        readiness.put("ai", aiReadiness(orgId));
        return readiness;
    }

    private Map<String, Boolean> capabilities() {
        Map<String, Boolean> capabilities = new LinkedHashMap<>();
        for (Capability capability : Capability.values()) {
            capabilities.put(capability.name(), capabilityRegistry.isAvailable(capability));
        }
        return capabilities;
    }

    private Map<String, Boolean> aiReadiness(int orgId) {
        Map<String, Boolean> readiness = new LinkedHashMap<>();
        readiness.put("providerReady", aiProviderReadiness.isReadyForOrg(orgId));
        readiness.put("imageInputReady", aiProviderReadiness.isImageInputReadyForOrg(orgId));
        return readiness;
    }
}
