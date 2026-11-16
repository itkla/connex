package ooo.klae.connex.backend.ai.provider;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import ooo.klae.connex.backend.ai.masking.OutboundSegment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Immutable final wire body and its staged exposure evidence. The existing Runnable transport
 * callback still owns pre-send accounting; transports notify this callback at response headers,
 * including error responses, before consuming any response body or publishing a delta.
 */
public final class AiProviderDispatch implements Runnable {
    private static final Pattern TOKEN = Pattern.compile("\\{\\{\\s*([A-Z][1-9][0-9]*)\\s*}}");
    private final String body;
    private final Set<String> dispatchedTokens;
    private final AiProviderAttemptExecutor executor;

    private AiProviderDispatch(String body, Set<String> tokens, AiProviderAttemptExecutor executor) {
        this.body = body;
        this.dispatchedTokens = Set.copyOf(tokens);
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    /** Returns the exact serialized body whose decoded fields established this evidence. */
    public String body() {
        return body;
    }

    /** Returns canonical token spellings from tenant-bearing positions only. */
    public Set<String> dispatchedTokens() {
        return dispatchedTokens;
    }

    @Override
    public void run() {
        executor.beforeSend();
    }

    /**
     * Notifies dispatch-aware callbacks while retaining legacy accounting-only client callers.
     * Connection failures before response headers must never call this method.
     */
    public static void responded(Runnable beforeSend) {
        if (beforeSend instanceof AiProviderDispatch dispatch) {
            dispatch.executor.onResponse(dispatch.dispatchedTokens);
        }
    }

    @Override
    public String toString() {
        return "AiProviderDispatch[body=<redacted>, evidence=<redacted>]";
    }

    /** Writes text and its positional provenance together, refusing unclassified final fields. */
    public static final class Builder {
        private final ObjectMapper mapper;
        private final Map<JsonNode, Map<String, List<OutboundSegment>>> fields = new IdentityHashMap<>();

        /** Uses the adapter's serializer for both the final body and its decoded correspondence. */
        public Builder(ObjectMapper mapper) {
            this.mapper = Objects.requireNonNull(mapper, "mapper");
        }

        /** Writes one field with exact origin spans, independently checked after serialization. */
        public ObjectNode text(ObjectNode parent, String name, String value, List<OutboundSegment> segments) {
            parent.put(name, value);
            register(parent, name, segments);
            return parent;
        }

        /** Writes a homogeneous field, including static vocabulary, media or provider replay. */
        public ObjectNode text(ObjectNode parent, String name, String value, OutboundSegment.Origin origin) {
            return text(parent, name, value, List.of(new OutboundSegment(value, origin)));
        }

        /** Writes schema vocabulary; no schema position can establish tenant exposure. */
        public void schema(ObjectNode parent, String name, JsonNode schema) {
            parent.set(name, schema.deepCopy());
            classifyTree(parent.get(name));
        }

        private void classifyTree(JsonNode node) {
            if (node.isObject()) {
                for (var property : node.properties()) {
                    if (property.getValue().isString()) {
                        register(node, property.getKey(), List.of(new OutboundSegment(
                                property.getValue().asString(), OutboundSegment.Origin.SERVER_STATIC)));
                    } else {
                        classifyTree(property.getValue());
                    }
                }
            } else if (node.isArray()) {
                for (int index = 0; index < node.size(); index++) {
                    if (node.get(index).isString()) {
                        register(node, Integer.toString(index), List.of(new OutboundSegment(
                                node.get(index).asString(), OutboundSegment.Origin.SERVER_STATIC)));
                    } else {
                        classifyTree(node.get(index));
                    }
                }
            }
        }

        private void register(JsonNode parent, String name, List<OutboundSegment> segments) {
            if (fields.computeIfAbsent(parent, ignored -> new HashMap<>())
                    .putIfAbsent(name, List.copyOf(segments)) != null) {
                throw mismatch();
            }
        }

        /** Serializes once, then decodes and validates every textual position of those exact bytes. */
        public AiProviderDispatch build(ObjectNode root, AiProviderAttemptExecutor executor) {
            Map<String, List<OutboundSegment>> positions = new LinkedHashMap<>();
            collect(root, "", positions);
            int registered = fields.values().stream().mapToInt(Map::size).sum();
            if (positions.size() != registered) {
                throw mismatch();
            }
            return validate(mapper.writeValueAsString(root), positions, mapper, executor);
        }

        private void collect(JsonNode node, String path, Map<String, List<OutboundSegment>> positions) {
            if (node.isObject()) {
                for (var property : node.properties()) {
                    collectField(node, property.getKey(), property.getValue(), path + "/"
                            + property.getKey().replace("~", "~0").replace("/", "~1"), positions);
                }
            } else if (node.isArray()) {
                for (int index = 0; index < node.size(); index++) {
                    collectField(node, Integer.toString(index), node.get(index), path + "/" + index, positions);
                }
            }
        }

        private void collectField(JsonNode parent, String name, JsonNode value, String path,
                Map<String, List<OutboundSegment>> positions) {
            if (value.isString()) {
                List<OutboundSegment> segments = fields.getOrDefault(parent, Map.of()).get(name);
                if (segments == null) {
                    throw mismatch();
                }
                positions.put(path, segments);
            } else {
                collect(value, path, positions);
            }
        }
    }

    /** Refuses missing, extra or changed text fields without exposing payload text in the error. */
    static AiProviderDispatch validate(String body, Map<String, List<OutboundSegment>> positions,
            ObjectMapper mapper, AiProviderAttemptExecutor executor) {
        JsonNode decoded = mapper.readTree(body.getBytes(StandardCharsets.UTF_8));
        Set<String> tokens = new HashSet<>();
        if (decoded == null || countText(decoded) != positions.size()) {
            throw mismatch();
        }
        for (var position : positions.entrySet()) {
            JsonNode field = decoded.at(position.getKey());
            String expected = position.getValue().stream().map(OutboundSegment::text)
                    .collect(java.util.stream.Collectors.joining());
            if (!field.isString() || !field.asString().equals(expected)) {
                throw mismatch();
            }
            int offset = 0;
            for (OutboundSegment segment : position.getValue()) {
                int end = offset + segment.text().length();
                if (segment.origin() == OutboundSegment.Origin.TENANT_DATA
                        || segment.origin() == OutboundSegment.Origin.TENANT_TOOL_RESULT) {
                    var matcher = TOKEN.matcher(field.asString().substring(offset, end));
                    while (matcher.find()) {
                        tokens.add("{{" + matcher.group(1) + "}}");
                    }
                }
                offset = end;
            }
        }
        return new AiProviderDispatch(body, tokens, executor);
    }

    private static int countText(JsonNode node) {
        if (node.isString()) {
            return 1;
        }
        int count = 0;
        for (JsonNode child : node) {
            count += countText(child);
        }
        return count;
    }

    private static AiProviderException mismatch() {
        return new AiProviderException("AI provider body provenance mismatch");
    }
}
