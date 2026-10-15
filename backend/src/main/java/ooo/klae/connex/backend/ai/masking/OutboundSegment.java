package ooo.klae.connex.backend.ai.masking;

import java.util.Objects;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import tools.jackson.databind.ObjectMapper;

/** Text and its source, retained independently of provider serialization for the egress boundary. */
public record OutboundSegment(
        String text, Origin origin,
        @com.fasterxml.jackson.annotation.JsonIgnore List<MaskingEngine.InsertedSpan> insertedSpans) {
    /** Static vocabulary is never member data; provider text cannot authorize member exposure. */
    public enum Origin {
        SERVER_STATIC,
        TENANT_DATA,
        PROVIDER_AUTHORED
    }

    public OutboundSegment {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(origin, "origin");
        insertedSpans = new MaskingEngine.MaskedText(text, insertedSpans).insertedSpans();
    }

    /** Unannotated source text never gains insertion provenance from its spelling. */
    public OutboundSegment(String text, Origin origin) {
        this(text, origin, List.of());
    }

    /** Serializes a complete JSON string while carrying exact masker insertion offsets through escaping. */
    public static OutboundSegment jsonString(
            MaskingEngine.MaskedText masked, Origin origin, ObjectMapper mapper) {
        StringBuilder json = new StringBuilder("\"");
        List<MaskingEngine.InsertedSpan> spans = new ArrayList<>();
        int copied = 0;
        for (MaskingEngine.InsertedSpan span : masked.insertedSpans()) {
            appendJsonStringPart(json, masked.text().substring(copied, span.start()), mapper);
            int start = json.length();
            appendJsonStringPart(json, masked.text().substring(span.start(), span.end()), mapper);
            spans.add(new MaskingEngine.InsertedSpan(start, json.length()));
            copied = span.end();
        }
        appendJsonStringPart(json, masked.text().substring(copied), mapper);
        return new OutboundSegment(json.append('"').toString(), origin, spans);
    }

    private static void appendJsonStringPart(StringBuilder target, String text, ObjectMapper mapper) {
        String json = mapper.writeValueAsString(text);
        if (json.length() < 2 || json.charAt(0) != '"' || json.charAt(json.length() - 1) != '"') {
            throw new IllegalArgumentException("Outbound field serialization must produce a JSON string");
        }
        target.append(json, 1, json.length() - 1);
    }

    /**
     * Neutralizes only recorded insertions for MEMBER scanning. Spaces retain JSON string syntax
     * and field decoding, so unannotated provider literals, escaped values and keys stay visible.
     * The dispatched text is unchanged; marker-shaped source text is never inferred to be trusted.
     */
    String memberScanText() {
        if (insertedSpans.isEmpty()) {
            return text;
        }
        char[] scanned = text.toCharArray();
        for (MaskingEngine.InsertedSpan span : insertedSpans) {
            Arrays.fill(scanned, span.start(), span.end(), ' ');
        }
        return new String(scanned);
    }

    @Override
    public String toString() {
        return "OutboundSegment[origin=" + origin + ", text=<redacted>]";
    }
}
