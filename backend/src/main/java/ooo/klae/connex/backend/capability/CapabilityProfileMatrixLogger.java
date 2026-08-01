package ooo.klae.connex.backend.capability;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.config.DeploymentProperties;

/**
 * Records the deployment-edition policy in effect for this instance as one structured startup
 * line, so an operator can see which capabilities the active profile refuses without reading
 * source. It lives beside the policy it reports rather than in the deployment-profile validator
 * so that the {@code config} package does not gain a dependency back on {@code capability}, and
 * so the line is emitted for every boot including the dev, test, and seeder profiles that the
 * validator exempts.
 *
 * <p>The line reports the profile gate only. Entitlement, rollout, and each capability's own
 * operator setting are additional gates, so an allowed capability is not necessarily enabled.
 */
@Component
@RequiredArgsConstructor
public class CapabilityProfileMatrixLogger implements ApplicationRunner {

    private static final String UNSET_PROFILE = "unset";
    private static final Logger log = LoggerFactory.getLogger(CapabilityProfileMatrixLogger.class);

    private final DeploymentProperties deploymentProperties;

    @Override
    public void run(ApplicationArguments args) {
        String profile = deploymentProperties.isConfigured() ? deploymentProperties.getProfile() : null;
        List<String> allowed = new ArrayList<>();
        List<String> forbidden = new ArrayList<>();
        for (Capability capability : Capability.values()) {
            if (CapabilityRegistry.isAllowedForProfile(capability, profile)) {
                allowed.add(capability.name());
            } else {
                forbidden.add(capability.name());
            }
        }
        log.info("Deployment capability matrix: profile={}, forbidden={}, allowed={}",
            profile == null ? UNSET_PROFILE : profile, forbidden, allowed);
    }
}
