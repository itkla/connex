package ooo.klae.connex.backend.services;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.dto.CspViolationRecord;
import ooo.klae.connex.backend.util.ClientIpResolver.ResolvedClientIp;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Turns browser Content Security Policy violation reports into bounded operator log events.
 *
 * <p>Reports arrive unauthenticated from any browser, so nothing here is persisted and only an
 * allowlisted set of fields is read. {@code script-sample}, {@code referrer}, {@code original-policy}
 * and every other free-text or policy-echo field is deliberately ignored: they carry page content
 * and would turn the operator log into an untrusted-content sink. The client address is used solely
 * as the throttle key and is never logged.
 */
@Service
@RequiredArgsConstructor
public class CspReportService {
    static final int MAX_REPORTS_PER_REQUEST = 10;
    static final String LEGACY_FORMAT = "legacy";
    static final String REPORTING_API_FORMAT = "reporting-api";

    private static final Logger log = LoggerFactory.getLogger(CspReportService.class);
    private static final int MAX_VALUE_LENGTH = 256;
    private static final char DELETE = 0x7f;
    private static final String UNKNOWN = "unknown";
    private static final String OTHER = "other";
    private static final Pattern DIRECTIVE = Pattern.compile("[a-z-]{1,40}");
    private static final Set<String> BLOCKED_KEYWORDS = Set.of(
            "inline", "eval", "data", "blob", "self", "wasm-eval", "about");
    private static final Set<String> HOST_SCHEMES = Set.of("http", "https", "ws", "wss");
    private static final String CSP_VIOLATION_REPORT_TYPE = "csp-violation";
    private static final List<String> DIRECTIVE_FIELDS = List.of(
            "effective-directive", "effectiveDirective", "violated-directive", "violatedDirective");
    private static final List<String> BLOCKED_FIELDS = List.of(
            "blocked-uri", "blockedURL", "blockedURI");
    private static final List<String> DOCUMENT_FIELDS = List.of(
            "document-uri", "documentURL", "documentURI");

    private final ObjectMapper objectMapper;
    private final CspReportRateLimiter rateLimiter;

    /**
     * Ingests one report request body and logs every violation it contains.
     *
     * <p>Never throws: an unparsable, throttled or wrongly shaped body yields an empty result so the
     * controller can answer {@code 204} unconditionally.
     *
     * @param body the raw request body
     * @param client the resolved client address and its provenance
     * @return the sanitized records that were logged
     */
    public List<CspViolationRecord> ingest(String body, ResolvedClientIp client) {
        if (body == null || body.isBlank()) {
            return List.of();
        }
        if (!rateLimiter.tryAcquire(throttleKey(client))) {
            log.debug("csp.report.throttled");
            return List.of();
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (JacksonException exception) {
            log.debug("csp.report.unparsable");
            return List.of();
        }
        List<CspViolationRecord> records = collect(root);
        for (CspViolationRecord record : records) {
            log.warn("csp.violation disposition={} directive={} blockedHost={} documentPath={} "
                    + "sourceHost={} line={} statusCode={} format={} forwardedByTrustedProxy={}",
                    record.disposition(), record.directive(), record.blockedHost(),
                    record.documentPath(), record.sourceHost(), record.lineNumber(),
                    record.statusCode(), record.format(), client.forwardedByTrustedProxy());
        }
        return records;
    }

    private static String throttleKey(ResolvedClientIp client) {
        String address = client.address();
        return address == null || address.isBlank() ? UNKNOWN : address;
    }

    private static List<CspViolationRecord> collect(JsonNode root) {
        List<CspViolationRecord> records = new ArrayList<>();
        if (root.isArray()) {
            for (JsonNode entry : root) {
                if (records.size() >= MAX_REPORTS_PER_REQUEST) {
                    break;
                }
                addReportingApiRecord(records, entry);
            }
            return List.copyOf(records);
        }
        if (!root.isObject()) {
            return List.of();
        }
        JsonNode legacy = root.path("csp-report");
        if (legacy.isObject()) {
            return describesViolation(legacy)
                    ? List.of(record(legacy, legacy, LEGACY_FORMAT))
                    : List.of();
        }
        addReportingApiRecord(records, root);
        return List.copyOf(records);
    }

    private static void addReportingApiRecord(List<CspViolationRecord> records, JsonNode entry) {
        if (!entry.isObject()) {
            return;
        }
        JsonNode type = entry.path("type");
        if (type.isTextual() && !CSP_VIOLATION_REPORT_TYPE.equals(type.asString())) {
            return;
        }
        JsonNode violation = entry.path("body").isObject() ? entry.path("body") : entry;
        if (!describesViolation(violation)) {
            return;
        }
        records.add(record(violation, entry, REPORTING_API_FORMAT));
    }

    private static boolean describesViolation(JsonNode violation) {
        return firstText(violation, DIRECTIVE_FIELDS) != null
                || firstText(violation, BLOCKED_FIELDS) != null;
    }

    private static CspViolationRecord record(JsonNode violation, JsonNode envelope, String format) {
        String documentUri = firstText(violation, DOCUMENT_FIELDS);
        return new CspViolationRecord(
                disposition(text(violation, "disposition")),
                directive(firstText(violation, DIRECTIVE_FIELDS)),
                host(firstText(violation, BLOCKED_FIELDS)),
                documentPath(documentUri == null ? text(envelope, "url") : documentUri),
                host(firstText(violation, List.of("source-file", "sourceFile"))),
                number(violation, "line-number", "lineNumber"),
                number(violation, "status-code", "statusCode"),
                format);
    }

    private static String disposition(String value) {
        if (value == null) {
            return UNKNOWN;
        }
        String normalized = bounded(value).toLowerCase(Locale.ROOT);
        return "enforce".equals(normalized) || "report".equals(normalized) ? normalized : UNKNOWN;
    }

    private static String directive(String value) {
        if (value == null) {
            return UNKNOWN;
        }
        String normalized = bounded(value).toLowerCase(Locale.ROOT);
        int separator = normalized.indexOf(' ');
        if (separator > 0) {
            normalized = normalized.substring(0, separator);
        }
        return DIRECTIVE.matcher(normalized).matches() ? normalized : OTHER;
    }

    private static String host(String value) {
        if (value == null) {
            return UNKNOWN;
        }
        String normalized = bounded(value);
        if (normalized.isEmpty()) {
            return UNKNOWN;
        }
        String keyword = normalized.toLowerCase(Locale.ROOT);
        if (BLOCKED_KEYWORDS.contains(keyword)) {
            return keyword;
        }
        try {
            URI uri = new URI(normalized);
            String scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT);
            if (scheme != null && HOST_SCHEMES.contains(scheme) && uri.getHost() != null) {
                String port = uri.getPort() < 0 ? "" : ":" + uri.getPort();
                return scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT) + port;
            }
        } catch (URISyntaxException exception) {
            return OTHER;
        }
        return OTHER;
    }

    private static String documentPath(String value) {
        if (value == null) {
            return UNKNOWN;
        }
        try {
            String path = new URI(bounded(value)).getPath();
            return path == null || path.isEmpty() ? "/" : bounded(path);
        } catch (URISyntaxException exception) {
            return "invalid";
        }
    }

    private static int number(JsonNode violation, String legacyName, String modernName) {
        for (String name : List.of(legacyName, modernName)) {
            JsonNode value = violation.path(name);
            if (value.isIntegralNumber() && value.canConvertToInt()) {
                return value.asInt();
            }
        }
        return -1;
    }

    private static String firstText(JsonNode violation, List<String> names) {
        for (String name : names) {
            String value = text(violation, name);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String text(JsonNode violation, String name) {
        JsonNode value = violation.path(name);
        return value.isTextual() ? value.asString() : null;
    }

    private static String bounded(String value) {
        StringBuilder sanitized = new StringBuilder(Math.min(value.length(), MAX_VALUE_LENGTH));
        for (int index = 0; index < value.length() && sanitized.length() < MAX_VALUE_LENGTH; index++) {
            char character = value.charAt(index);
            if (character >= ' ' && character != DELETE) {
                sanitized.append(character);
            }
        }
        return sanitized.toString();
    }
}
