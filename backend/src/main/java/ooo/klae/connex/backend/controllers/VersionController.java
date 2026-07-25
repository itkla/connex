package ooo.klae.connex.backend.controllers;

import java.time.Instant;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonInclude;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

/**
 * Instance-level product build information endpoint.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class VersionController {
    private final ObjectProvider<BuildProperties> buildProperties;

    /**
     * Returns the packaged product version and build time, or development defaults when build
     * metadata is unavailable.
     *
     * @return the current product build information
     */
    @GetMapping("/version")
    public VersionResponse version() {
        BuildProperties properties = buildProperties.getIfAvailable();
        if (properties == null) {
            return new VersionResponse("dev", null, null);
        }

        Instant buildTime = properties.getTime();
        return new VersionResponse(
                Objects.requireNonNullElse(properties.getVersion(), "dev"),
                buildTime == null ? null : buildTime.toString(),
                properties.get("gitSha"));
    }

    /**
     * Product build information served to any caller.
     *
     * @param version   the packaged product version, or {@code "dev"} when unavailable
     * @param buildTime the ISO-8601 build timestamp, or null when unavailable
     * @param gitSha    the git commit the artifact was built from ({@code "unknown"} when the
     *                  build ran without a repository), or null when build metadata is absent
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record VersionResponse(String version, String buildTime, String gitSha) {
    }
}
