package ooo.klae.connex.backend.services;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.StreamWriteFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.DecimalNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.type.LogicalType;

import ooo.klae.connex.backend.dto.WorkflowCanvas;
import ooo.klae.connex.backend.dto.WorkflowDefinition;
import ooo.klae.connex.backend.dto.WorkflowEdge;
import ooo.klae.connex.backend.dto.WorkflowNode;
import ooo.klae.connex.backend.exceptions.BadRequestException;

/** Validates workflow draft boundaries and produces deterministic persisted JSON and hashes. */
@Component
public class WorkflowDraftCanonicalizer {

    private static final int MAX_DEFINITION_BYTES = 64 * 1024;
    private static final int MAX_CANVAS_BYTES = 16 * 1024;
    private static final int MAX_NODES = 50;
    private static final int MAX_EDGES = 100;
    private static final int MAX_ACTIONS = 16;
    private static final BigDecimal MAX_COORDINATE = new BigDecimal("1000000");
    private static final BigDecimal MIN_ZOOM = new BigDecimal("0.1");
    private static final BigDecimal MAX_ZOOM = new BigDecimal("4");
    private static final Pattern OPAQUE_ID = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,63}");
    private static final JsonMapper JSON = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
        .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
        .withCoercionConfig(
            LogicalType.Integer,
            config -> config.setCoercion(CoercionInputShape.Float, CoercionAction.Fail))
        .build();

    CanonicalDraft canonicalizeDraftJson(
            String name,
            String description,
            String recordType,
            String executionMode,
            String definitionJson,
            String canvasJson) {
        requireInputSize(definitionJson, MAX_DEFINITION_BYTES, "Workflow definition is too large");
        requireInputSize(canvasJson, MAX_CANVAS_BYTES, "Workflow canvas is too large");

        WorkflowDefinition definition = parse(
            definitionJson, WorkflowDefinition.class, "Invalid workflow definition");
        WorkflowCanvas canvas = parse(canvasJson, WorkflowCanvas.class, "Invalid workflow canvas");
        return canonicalizeDraft(
            name, description, recordType, executionMode, definition, canvas);
    }

    CanonicalDraft canonicalizeDraft(
            String name,
            String description,
            String recordType,
            String executionMode,
            WorkflowDefinition definition,
            WorkflowCanvas canvas) {
        validateDefinition(definition);
        validateCanvas(canvas, definition.nodes());

        String normalizedName = normalizeName(name);
        String normalizedDescription = normalizeDescription(description);
        String normalizedRecordType = normalizeRecordType(recordType);
        String normalizedExecutionMode = normalizeExecutionMode(executionMode);
        String canonicalDefinitionJson = canonicalDefinition(definition);
        String canonicalCanvasJson = canonicalCanvas(canvas);
        requireInputSize(canonicalDefinitionJson, MAX_DEFINITION_BYTES, "Workflow definition is too large");
        requireInputSize(canonicalCanvasJson, MAX_CANVAS_BYTES, "Workflow canvas is too large");

        byte[] hash = hash(canonicalDefinitionJson, canonicalCanvasJson);
        return new CanonicalDraft(
            normalizedName,
            normalizedDescription,
            normalizedRecordType,
            normalizedExecutionMode,
            canonicalDefinitionJson,
            canonicalCanvasJson,
            hash);
    }

    private static void validateDefinition(WorkflowDefinition definition) {
        if (definition == null || definition.schemaVersion() != 1) {
            throw new BadRequestException("Workflow definition must use schemaVersion 1");
        }
        if (definition.nodes() == null) {
            throw new BadRequestException("Workflow nodes are required");
        }
        if (definition.edges() == null) {
            throw new BadRequestException("Workflow edges are required");
        }
        if (definition.nodes().size() > MAX_NODES) {
            throw new BadRequestException("Workflow definition exceeds 50 nodes");
        }
        if (definition.edges().size() > MAX_EDGES) {
            throw new BadRequestException("Workflow definition exceeds 100 edges");
        }

        Set<String> nodeIds = new HashSet<>();
        int actionCount = 0;
        for (WorkflowNode node : definition.nodes()) {
            if (node == null || !validId(node.id())) {
                throw new BadRequestException("Workflow contains an invalid node id");
            }
            if (!nodeIds.add(node.id())) {
                throw new BadRequestException("Workflow contains a duplicate node id: " + node.id());
            }
            if (node instanceof WorkflowNode.Action) {
                actionCount++;
            }
        }
        if (actionCount > MAX_ACTIONS) {
            throw new BadRequestException("Workflow definition exceeds 16 actions");
        }
        if (definition.entryNodeId() != null) {
            if (!validId(definition.entryNodeId())) {
                throw new BadRequestException("Workflow entryNodeId is invalid");
            }
            if (!nodeIds.contains(definition.entryNodeId())) {
                throw new BadRequestException("Workflow entryNodeId references a missing node");
            }
        }

        Set<String> edgeIds = new HashSet<>();
        for (WorkflowEdge edge : definition.edges()) {
            if (edge == null || !validId(edge.id())) {
                throw new BadRequestException("Workflow contains an invalid edge id");
            }
            if (!edgeIds.add(edge.id())) {
                throw new BadRequestException("Workflow contains a duplicate edge id: " + edge.id());
            }
            if (!validId(edge.sourceNodeId()) || !validId(edge.targetNodeId())) {
                throw new BadRequestException("Workflow edge contains an invalid node reference");
            }
            if (!nodeIds.contains(edge.sourceNodeId()) || !nodeIds.contains(edge.targetNodeId())) {
                throw new BadRequestException("Workflow edge references a missing node: " + edge.id());
            }
            if (edge.outcome() == null) {
                throw new BadRequestException("Workflow edge outcome is required");
            }
        }
    }

    private static void validateCanvas(WorkflowCanvas canvas, List<WorkflowNode> nodes) {
        if (canvas == null || canvas.positions() == null) {
            throw new BadRequestException("Workflow canvas positions are required");
        }
        if (canvas.viewport() == null) {
            throw new BadRequestException("Workflow canvas viewport is required");
        }

        Set<String> nodeIds = new HashSet<>();
        for (WorkflowNode node : nodes) {
            nodeIds.add(node.id());
        }
        for (Map.Entry<String, WorkflowCanvas.Position> entry : canvas.positions().entrySet()) {
            if (!validId(entry.getKey()) || !nodeIds.contains(entry.getKey())) {
                throw new BadRequestException("Workflow canvas references a missing node: " + entry.getKey());
            }
            WorkflowCanvas.Position position = entry.getValue();
            if (position == null) {
                throw new BadRequestException("Workflow canvas position is required: " + entry.getKey());
            }
            requireCoordinate(position.x(), "Workflow canvas x coordinate is invalid");
            requireCoordinate(position.y(), "Workflow canvas y coordinate is invalid");
        }

        requireCoordinate(canvas.viewport().x(), "Workflow viewport x coordinate is invalid");
        requireCoordinate(canvas.viewport().y(), "Workflow viewport y coordinate is invalid");
        BigDecimal zoom = canvas.viewport().zoom();
        if (zoom == null || zoom.compareTo(MIN_ZOOM) < 0 || zoom.compareTo(MAX_ZOOM) > 0) {
            throw new BadRequestException("Workflow viewport zoom must be between 0.1 and 4");
        }
    }

    private static String normalizeName(String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new BadRequestException("Workflow name is required");
        }
        String normalized = name.trim();
        if (normalized.length() > 128) {
            throw new BadRequestException("Workflow name must be at most 128 characters");
        }
        return normalized;
    }

    private static String normalizeDescription(String description) {
        if (description == null || description.isBlank()) {
            return null;
        }
        if (description.length() > 512) {
            throw new BadRequestException("Workflow description must be at most 512 characters");
        }
        return description;
    }

    private static String normalizeRecordType(String recordType) {
        if (recordType == null || recordType.isBlank()) {
            return null;
        }
        String normalized = recordType.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() > 16) {
            throw new BadRequestException("Workflow record type must be at most 16 characters");
        }
        return normalized;
    }

    private static String normalizeExecutionMode(String executionMode) {
        String normalized = executionMode == null
            ? null
            : executionMode.trim().toLowerCase(Locale.ROOT);
        if (!"user".equals(normalized) && !"system".equals(normalized)) {
            throw new BadRequestException("Workflow execution mode must be user or system");
        }
        return normalized;
    }

    private static String canonicalDefinition(WorkflowDefinition definition) {
        ObjectNode root = objectTree(definition, "Workflow definition could not be canonicalized");
        sortArrayById(root, "nodes");
        sortArrayById(root, "edges");
        return writeCanonical(canonicalNode(root), "Workflow definition could not be canonicalized");
    }

    private static String canonicalCanvas(WorkflowCanvas canvas) {
        ObjectNode root = objectTree(canvas, "Workflow canvas could not be canonicalized");
        return writeCanonical(canonicalNode(root), "Workflow canvas could not be canonicalized");
    }

    private static ObjectNode objectTree(Object value, String message) {
        try {
            JsonNode node = JSON.readTree(JSON.writeValueAsBytes(value));
            if (!(node instanceof ObjectNode objectNode)) {
                throw new BadRequestException(message);
            }
            return objectNode;
        } catch (JacksonException exception) {
            throw new BadRequestException(message);
        }
    }

    private static void sortArrayById(ObjectNode root, String property) {
        JsonNode value = root.get(property);
        if (!(value instanceof ArrayNode array)) {
            throw new BadRequestException("Workflow " + property + " are invalid");
        }
        List<JsonNode> values = new ArrayList<>();
        array.forEach(values::add);
        values.sort(Comparator.comparing(node -> node.get("id").asString()));
        ArrayNode sorted = JSON.createArrayNode();
        sorted.addAll(values);
        root.set(property, sorted);
    }

    private static JsonNode canonicalNode(JsonNode node) {
        if (node instanceof ObjectNode objectNode) {
            List<Map.Entry<String, JsonNode>> properties = new ArrayList<>(objectNode.properties());
            properties.sort(Map.Entry.comparingByKey());
            ObjectNode canonical = JSON.createObjectNode();
            for (Map.Entry<String, JsonNode> property : properties) {
                canonical.set(property.getKey(), canonicalNode(property.getValue()));
            }
            return canonical;
        }
        if (node instanceof ArrayNode arrayNode) {
            ArrayNode canonical = JSON.createArrayNode();
            for (JsonNode element : arrayNode) {
                canonical.add(canonicalNode(element));
            }
            return canonical;
        }
        if (node.isFloatingPointNumber()) {
            BigDecimal normalized = node.decimalValue().stripTrailingZeros();
            return DecimalNode.valueOf(normalized.signum() == 0 ? BigDecimal.ZERO : normalized);
        }
        return node.deepCopy();
    }

    private static String writeCanonical(JsonNode node, String message) {
        try {
            return JSON.writeValueAsString(node);
        } catch (JacksonException exception) {
            throw new BadRequestException(message);
        }
    }

    private static byte[] hash(String definitionJson, String canvasJson) {
        String payload = "{\"definition\":" + definitionJson + ",\"canvas\":" + canvasJson + "}";
        try {
            return MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static <T> T parse(String json, Class<T> type, String message) {
        try {
            return JSON.readValue(json, type);
        } catch (JacksonException exception) {
            throw new BadRequestException(message);
        }
    }

    private static void requireInputSize(String json, int maximumBytes, String message) {
        if (json == null || json.getBytes(StandardCharsets.UTF_8).length > maximumBytes) {
            throw new BadRequestException(message);
        }
    }

    private static void requireCoordinate(BigDecimal coordinate, String message) {
        if (coordinate == null || coordinate.abs().compareTo(MAX_COORDINATE) > 0) {
            throw new BadRequestException(message);
        }
    }

    private static boolean validId(String id) {
        return id != null && OPAQUE_ID.matcher(id).matches();
    }

    record CanonicalDraft(
        String name,
        String description,
        String recordType,
        String executionMode,
        String definitionJson,
        String canvasJson,
        byte[] definitionHash
    ) {
        CanonicalDraft {
            definitionHash = definitionHash.clone();
        }

        @Override
        public byte[] definitionHash() {
            return definitionHash.clone();
        }
    }
}
