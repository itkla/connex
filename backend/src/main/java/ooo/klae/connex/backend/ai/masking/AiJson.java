package ooo.klae.connex.backend.ai.masking;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Extracts a single JSON object from a possibly noisy provider completion. Providers may wrap the
 * object in code fences, a prose preamble, or trailing commentary; this locates the first plausible
 * JSON-object start and returns it only when it parses cleanly. A malformed object fails closed so a
 * complete nested object cannot be mistaken for the provider's top-level response. Placeholder
 * tokens such as {@code {{P1}}} are not considered JSON-object starts.
 */
public final class AiJson {
    private static final int MAX_SCAN_CHARS = 100_000;
    private static final int MAX_CANDIDATES = 256;
    private static final Pattern PLACEHOLDER_TOKEN =
            Pattern.compile("\\{\\{\\s*[A-Z][1-9][0-9]*\\s*}}");

    private AiJson() {
    }

    /**
     * Returns the first substring of {@code text} that parses as a complete JSON object. To keep the
     * scan linear against adversarial provider output, only the first {@value #MAX_SCAN_CHARS}
     * characters are considered and at most {@value #MAX_CANDIDATES} candidate positions are tried;
     * legitimate output is far smaller (bounded by the invocation token cap).
     * @param text provider completion text
     * @param mapper configured object mapper
     * @return the parsed object node, or {@code null} when no complete JSON object is present
     */
    public static ObjectNode extractObject(String text, ObjectMapper mapper) {
        Objects.requireNonNull(mapper, "mapper");
        if (text == null || text.isBlank()) {
            return null;
        }
        String scan = text.length() > MAX_SCAN_CHARS ? text.substring(0, MAX_SCAN_CHARS) : text;
        Matcher placeholder = PLACEHOLDER_TOKEN.matcher(scan);
        int from = 0;
        for (int attempt = 0; attempt < MAX_CANDIDATES; attempt++) {
            int brace = scan.indexOf('{', from);
            if (brace < 0) {
                return null;
            }
            placeholder.region(brace, scan.length());
            if (placeholder.lookingAt()) {
                from = placeholder.end();
                continue;
            }
            return tryParseObject(scan.substring(brace), mapper);
        }
        return null;
    }

    private static ObjectNode tryParseObject(String candidate, ObjectMapper mapper) {
        try (JsonParser parser = mapper.createParser(candidate)) {
            if (parser.nextToken() == null) {
                return null;
            }
            JsonNode node = parser.readValueAsTree();
            return node instanceof ObjectNode object ? object : null;
        } catch (JacksonException exception) {
            return null;
        }
    }
}
