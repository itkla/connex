package ooo.klae.connex.backend.businesscard;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/** Enforces encrypted or explicitly isolated OCR transport outside local development. */
final class BusinessCardOcrTransportStartupValidator {
    private static final Pattern PRIVATE_SERVICE_HOST = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");

    private BusinessCardOcrTransportStartupValidator() {
    }

    static void validate(BusinessCardProperties properties, Environment environment) {
        URI endpoint = properties.getOcrBaseUrl();
        if (endpoint == null
                || !"http".equalsIgnoreCase(endpoint.getScheme())
                || environment.acceptsProfiles(Profiles.of("dev"))) {
            return;
        }
        String endpointHost = normalized(endpoint.getHost());
        String privateHost = normalized(properties.getPlainHttpPrivateHost());
        if (!PRIVATE_SERVICE_HOST.matcher(privateHost).matches() || !privateHost.equals(endpointHost)) {
            throw new IllegalStateException(
                    "Plain-HTTP OCR endpoints outside dev must match the explicitly isolated private service host");
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
