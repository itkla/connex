package ooo.klae.connex.backend.ai.masking;

import java.util.Objects;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Extracts a single JSON object from a possibly noisy provider completion. Providers may wrap the
 * object in code fences, a prose preamble, or trailing commentary; this locates the first substring
 * that parses cleanly as a JSON object and returns it, ignoring everything around it. The scan hands
 * each candidate to Jackson rather than counting braces, so string literals, escapes, and
 * placeholder tokens such as {@code {{P1}}} can never confuse the boundary detection.
 */
public final class AiJson {
    private static final int MAX_SCAN_CHARS = 100_000;
    private static final int MAX_CANDIDATES = 256;

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
        int from = 0;
        for (int attempt = 0; attempt < MAX_CANDIDATES; attempt++) {
            int brace = scan.indexOf('{', from);
            if (brace < 0) {
                return null;
            }
            ObjectNode object = tryParseObject(scan.substring(brace), mapper);
            if (object != null) {
                return object;
            }
            from = brace + 1;
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
