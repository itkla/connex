package ooo.klae.connex.backend.services;

import java.time.Instant;
import java.util.Objects;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/**
 * Resolves the packaged product build information from the build metadata.
 *
 * <p>Both the public version endpoint and the support bundle manifest read the version from
 * here, so a bundle can never disagree with what the instance reports about itself.
 */
@Service
@RequiredArgsConstructor
public class ProductVersionService {
    private static final String DEVELOPMENT_VERSION = "dev";

    private final ObjectProvider<BuildProperties> buildProperties;

    /**
     * Returns the packaged product version, or the development default when build metadata is
     * unavailable.
     *
     * @return the current product version
     */
    public String version() {
        BuildProperties properties = buildProperties.getIfAvailable();
        if (properties == null) {
            return DEVELOPMENT_VERSION;
        }
        return Objects.requireNonNullElse(properties.getVersion(), DEVELOPMENT_VERSION);
    }

    /**
     * Returns the ISO-8601 build timestamp, or null when build metadata is unavailable.
     *
     * @return the build time, or null
     */
    public String buildTime() {
        BuildProperties properties = buildProperties.getIfAvailable();
        if (properties == null) {
            return null;
        }
        Instant buildTime = properties.getTime();
        return buildTime == null ? null : buildTime.toString();
    }

    /**
     * Returns the git commit the artifact was built from, or null when unavailable.
     *
     * @return the git commit, or null
     */
    public String gitSha() {
        BuildProperties properties = buildProperties.getIfAvailable();
        if (properties == null) {
            return null;
        }
        return properties.get("gitSha");
    }
}
