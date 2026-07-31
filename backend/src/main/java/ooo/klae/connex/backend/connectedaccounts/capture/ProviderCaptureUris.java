package ooo.klae.connex.backend.connectedaccounts.capture;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Fixed-host URI construction and opaque cursor validation for provider capture.
 */
final class ProviderCaptureUris {
    private ProviderCaptureUris() {
    }

    static URI build(String base, Map<String, String> parameters) {
        StringBuilder value = new StringBuilder(base);
        char separator = base.contains("?") ? '&' : '?';
        for (Map.Entry<String, String> parameter : parameters.entrySet()) {
            if (parameter.getValue() != null) {
                value.append(separator)
                    .append(URLEncoder.encode(parameter.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(parameter.getValue(), StandardCharsets.UTF_8));
                separator = '&';
            }
        }
        return URI.create(value.toString());
    }

    static Map<String, String> parameters() {
        return new LinkedHashMap<>();
    }

    static URI requireOpaqueCursor(
            String cursor, String scheme, String host, String requiredPathPrefix) {
        URI uri;
        try {
            uri = URI.create(cursor);
        } catch (IllegalArgumentException exception) {
            throw invalidCursor(exception);
        }
        if (!scheme.equals(uri.getScheme())
                || !host.equalsIgnoreCase(uri.getHost())
                || uri.getUserInfo() != null
                || uri.getPort() != -1
                || uri.getFragment() != null
                || uri.getPath() == null
                || !uri.getPath().startsWith(requiredPathPrefix)) {
            throw invalidCursor(null);
        }
        return uri;
    }

    private static ProviderCaptureException invalidCursor(Throwable cause) {
        String message = "Provider cursor is not an authorized fixed-host URI";
        if (cause == null) {
            return new ProviderCaptureException("cursor_invalid", true, true, message);
        }
        return new ProviderCaptureException("cursor_invalid", true, true, message, cause);
    }
}
