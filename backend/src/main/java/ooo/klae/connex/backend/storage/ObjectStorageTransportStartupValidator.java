package ooo.klae.connex.backend.storage;

import java.net.URI;

import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/** Enforces encrypted S3-compatible object transport outside local development. */
public final class ObjectStorageTransportStartupValidator {
    private ObjectStorageTransportStartupValidator() {
    }

    static void validate(ObjectStorageProperties properties, Environment environment) {
        URI endpoint = properties.s3EndpointUri();
        if (endpoint != null
                && "http".equalsIgnoreCase(endpoint.getScheme())
                && !environment.acceptsProfiles(Profiles.of("dev"))) {
            throw new IllegalStateException(
                    "Plain-HTTP S3 endpoints are permitted only with the dev Spring profile");
        }
    }
}
