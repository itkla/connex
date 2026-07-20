package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

import ooo.klae.connex.backend.dto.RuleAction;
import ooo.klae.connex.backend.dto.RuleTrigger;
import ooo.klae.connex.backend.dto.WorkflowCanvas;
import ooo.klae.connex.backend.dto.WorkflowDefinition;
import ooo.klae.connex.backend.dto.WorkflowEdge;
import ooo.klae.connex.backend.dto.WorkflowNode;
import ooo.klae.connex.backend.exceptions.BadRequestException;

/** Verifies strict workflow validation, canonical determinism, size limits, and hashing. */
class WorkflowDraftCanonicalizerTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final WorkflowDraftCanonicalizer canonicalizer = new WorkflowDraftCanonicalizer();

    @Test
    void canonicalizationIsDeterministicAndPreservesConfigurationArrayOrder() {
        String definitionA = """
            {"schemaVersion":1,"entryNodeId":"trigger","nodes":[
              {"type":"TRIGGER","id":"trigger","config":{"events":["deal.won","deal.updated"],"type":"entity_change"}},
              {"type":"ACTION","id":"action-1","config":{"title":"Notify","type":"notify"}},
              {"type":"END","id":"end"}],
             "edges":[
              {"id":"trigger--next--action-1","sourceNodeId":"trigger","targetNodeId":"action-1","outcome":"next"},
              {"id":"action-1--next--end","sourceNodeId":"action-1","targetNodeId":"end","outcome":"next"}]}
            """;
        String definitionB = """
            {"edges":[
              {"outcome":"next","targetNodeId":"end","sourceNodeId":"action-1","id":"action-1--next--end"},
              {"outcome":"next","targetNodeId":"action-1","sourceNodeId":"trigger","id":"trigger--next--action-1"}],
             "nodes":[
              {"id":"end","type":"END"},
              {"config":{"type":"notify","title":"Notify"},"id":"action-1","type":"ACTION"},
              {"config":{"type":"entity_change","events":["deal.won","deal.updated"]},"id":"trigger","type":"TRIGGER"}],
             "entryNodeId":"trigger","schemaVersion":1}
            """;
        String canvasA = """
            {"viewport":{"zoom":1.000,"y":0.0,"x":0E+3},"positions":{
              "trigger":{"y":0.00,"x":0.0},"action-1":{"x":240.000,"y":0},"end":{"x":4.80e2,"y":0}}}
            """;
        String canvasB = """
            {"positions":{"end":{"y":0.0,"x":480},"action-1":{"y":0,"x":2.40e2},"trigger":{"x":0,"y":0}},
             "viewport":{"x":0,"y":0,"zoom":1}}
            """;

        WorkflowDraftCanonicalizer.CanonicalDraft first = canonicalize(definitionA, canvasA);
        WorkflowDraftCanonicalizer.CanonicalDraft second = canonicalize(definitionB, canvasB);

        assertEquals(first.definitionJson(), second.definitionJson());
        assertEquals(first.canvasJson(), second.canvasJson());
        assertArrayEquals(first.definitionHash(), second.definitionHash());
        assertTrue(first.definitionJson().contains("\"events\":[\"deal.won\",\"deal.updated\"]"));
        assertTrue(first.definitionJson().indexOf("\"id\":\"action-1\"")
            < first.definitionJson().indexOf("\"id\":\"end\""));
        assertTrue(first.canvasJson().contains("\"action-1\":{\"x\":240,\"y\":0}"));
        assertTrue(first.canvasJson().contains("\"viewport\":{\"x\":0,\"y\":0,\"zoom\":1}"));
        assertEquals("Workflow", first.name());
        assertNull(first.description());
        assertEquals("deal", first.recordType());
        assertEquals("user", first.executionMode());
    }

    @Test
    void rejectsDuplicateIdsDanglingEdgesAndInvalidOpaqueIds() {
        String duplicateNodes = definition(
            """
            [{"type":"END","id":"end"},{"type":"END","id":"end"}]
            """,
            "[]",
            "end");
        assertThrows(BadRequestException.class, () -> canonicalize(duplicateNodes, canvas("end")));

        String duplicateEdges = definition(
            """
            [{"type":"TRIGGER","id":"trigger","config":null},{"type":"END","id":"end"}]
            """,
            """
            [{"id":"edge","sourceNodeId":"trigger","targetNodeId":"end","outcome":"next"},
             {"id":"edge","sourceNodeId":"trigger","targetNodeId":"end","outcome":"next"}]
            """,
            "trigger");
        assertThrows(BadRequestException.class, () -> canonicalize(duplicateEdges, canvas("trigger", "end")));

        String dangling = definition(
            """
            [{"type":"TRIGGER","id":"trigger","config":null}]
            """,
            """
            [{"id":"edge","sourceNodeId":"trigger","targetNodeId":"missing","outcome":"next"}]
            """,
            "trigger");
        assertThrows(BadRequestException.class, () -> canonicalize(dangling, canvas("trigger")));

        String invalidId = definition("[{\"type\":\"END\",\"id\":\"1bad\"}]", "[]", null);
        assertThrows(BadRequestException.class, () -> canonicalize(invalidId, emptyCanvas()));
    }

    @Test
    void enforcesGraphAndActionLimits() {
        List<WorkflowNode> nodes = new ArrayList<>();
        for (int index = 0; index < 51; index++) {
            nodes.add(new WorkflowNode.End("node-" + index));
        }
        WorkflowDefinition tooManyNodes = new WorkflowDefinition(1, null, nodes, List.of());
        assertThrows(BadRequestException.class,
            () -> canonicalize(json(tooManyNodes), emptyCanvas()));

        List<WorkflowNode> actionNodes = new ArrayList<>();
        for (int index = 0; index < 17; index++) {
            RuleAction action = new RuleAction();
            action.setType("notify");
            action.setTitle("Action " + index);
            actionNodes.add(new WorkflowNode.Action("action-" + index, action));
        }
        WorkflowDefinition tooManyActions = new WorkflowDefinition(1, null, actionNodes, List.of());
        assertThrows(BadRequestException.class,
            () -> canonicalize(json(tooManyActions), emptyCanvas()));

        List<WorkflowEdge> edges = new ArrayList<>();
        for (int index = 0; index < 101; index++) {
            edges.add(new WorkflowEdge(
                "edge-" + index, "start", "end", WorkflowEdge.Outcome.NEXT));
        }
        WorkflowDefinition tooManyEdges = new WorkflowDefinition(
            1,
            "start",
            List.of(new WorkflowNode.Trigger("start", null), new WorkflowNode.End("end")),
            edges);
        assertThrows(BadRequestException.class,
            () -> canonicalize(json(tooManyEdges), canvas("start", "end")));
    }

    @Test
    void enforcesCanvasReferencesCoordinateBoundsAndZoomBounds() {
        String definition = minimalDefinition();
        assertDoesNotThrow(() -> canonicalize(definition,
            "{\"positions\":{\"end\":{\"x\":-1000000,\"y\":1000000}},"
                + "\"viewport\":{\"x\":1000000,\"y\":-1000000,\"zoom\":4}}"));
        assertDoesNotThrow(() -> canonicalize(definition,
            "{\"positions\":{\"end\":{\"x\":0,\"y\":0}},"
                + "\"viewport\":{\"x\":0,\"y\":0,\"zoom\":0.1}}"));
        assertThrows(BadRequestException.class,
            () -> canonicalize(definition, canvas("missing")));
        assertThrows(BadRequestException.class,
            () -> canonicalize(definition,
                "{\"positions\":{\"end\":{\"x\":1000000.0001,\"y\":0}},\"viewport\":{\"x\":0,\"y\":0,\"zoom\":1}}"));
        assertThrows(BadRequestException.class,
            () -> canonicalize(definition,
                "{\"positions\":{\"end\":{\"x\":0,\"y\":0}},\"viewport\":{\"x\":0,\"y\":0,\"zoom\":0.09}}"));
        assertThrows(BadRequestException.class,
            () -> canonicalize(definition,
                "{\"positions\":{\"end\":{\"x\":0,\"y\":0}},\"viewport\":{\"x\":0,\"y\":0,\"zoom\":4.01}}"));
    }

    @Test
    void rejectsUnknownPropertiesAndDuplicateJsonKeys() {
        String unknown = """
            {"schemaVersion":1,"entryNodeId":"end","nodes":[{"type":"END","id":"end"}],"edges":[],"extra":true}
            """;
        assertThrows(BadRequestException.class, () -> canonicalize(unknown, canvas("end")));

        String unknownConfig = """
            {"schemaVersion":1,"entryNodeId":"trigger","nodes":[
              {"type":"TRIGGER","id":"trigger","config":{"type":"entity_change","unknown":true}}],"edges":[]}
            """;
        assertThrows(BadRequestException.class,
            () -> canonicalize(unknownConfig, canvas("trigger")));

        String duplicateDefinitionKey = """
            {"schemaVersion":1,"schemaVersion":1,"entryNodeId":"end","nodes":[{"type":"END","id":"end"}],"edges":[]}
            """;
        assertThrows(BadRequestException.class,
            () -> canonicalize(duplicateDefinitionKey, canvas("end")));

        String duplicateCanvasKey = """
            {"positions":{"end":{"x":0,"y":0},"end":{"x":1,"y":0}},"viewport":{"x":0,"y":0,"zoom":1}}
            """;
        assertThrows(BadRequestException.class,
            () -> canonicalize(minimalDefinition(), duplicateCanvasKey));
    }

    @Test
    void rejectsScalarCoercionAndNullPrimitiveCreatorValues() {
        assertThrows(BadRequestException.class, () -> canonicalize(
            "{\"schemaVersion\":\"1\",\"entryNodeId\":\"end\","
                + "\"nodes\":[{\"type\":\"END\",\"id\":\"end\"}],\"edges\":[]}",
            canvas("end")));
        assertThrows(BadRequestException.class, () -> canonicalize(
            "{\"schemaVersion\":1.0,\"entryNodeId\":\"end\","
                + "\"nodes\":[{\"type\":\"END\",\"id\":\"end\"}],\"edges\":[]}",
            canvas("end")));
        assertThrows(BadRequestException.class, () -> canonicalize(
            "{\"schemaVersion\":null,\"entryNodeId\":\"end\","
                + "\"nodes\":[{\"type\":\"END\",\"id\":\"end\"}],\"edges\":[]}",
            canvas("end")));
        assertThrows(BadRequestException.class, () -> canonicalize(
            minimalDefinition(),
            "{\"positions\":{\"end\":{\"x\":\"0\",\"y\":0}},"
                + "\"viewport\":{\"x\":0,\"y\":0,\"zoom\":1}}"));
        assertThrows(BadRequestException.class, () -> canonicalize(
            minimalDefinition(),
            "{\"positions\":{\"end\":{\"x\":0,\"y\":0}},"
                + "\"viewport\":{\"x\":0,\"y\":0,\"zoom\":\"1\"}}"));
    }

    @Test
    void enforcesUtf8CapsAndHashesTheSpecifiedWrapper() throws Exception {
        String oversizedDefinition = """
            {"schemaVersion":1,"entryNodeId":"action","nodes":[{"type":"ACTION","id":"action","config":{"type":"notify","title":"%s"}}],"edges":[]}
            """.formatted("é".repeat(33_000));
        assertThrows(BadRequestException.class,
            () -> canonicalize(oversizedDefinition, canvas("action")));

        String oversizedCanvas = "{\"positions\":{}," + " ".repeat(17_000)
            + "\"viewport\":{\"x\":0,\"y\":0,\"zoom\":1}}";
        assertThrows(BadRequestException.class,
            () -> canonicalize(minimalDefinition(), oversizedCanvas));

        WorkflowDraftCanonicalizer.CanonicalDraft canonical = canonicalize(
            minimalDefinition(), canvas("end"));
        String wrapper = "{\"definition\":" + canonical.definitionJson()
            + ",\"canvas\":" + canonical.canvasJson() + "}";
        byte[] expected = MessageDigest.getInstance("SHA-256")
            .digest(wrapper.getBytes(StandardCharsets.UTF_8));
        assertArrayEquals(expected, canonical.definitionHash());
        assertArrayEquals(
            HexFormat.of().parseHex("b7ed478578a0dc0d5c4e158fff8dcb59257dc189cf05486fdcdb436552d267dc"),
            canonical.definitionHash());
        assertEquals(32, canonical.definitionHash().length);
    }

    @Test
    void validatesAndNormalizesDraftMetadata() {
        assertThrows(BadRequestException.class, () -> canonicalizer.canonicalizeDraftJson(
            "   ", null, null, "user", minimalDefinition(), canvas("end")));
        assertThrows(BadRequestException.class, () -> canonicalizer.canonicalizeDraftJson(
            "a".repeat(129), null, null, "user", minimalDefinition(), canvas("end")));
        assertThrows(BadRequestException.class, () -> canonicalizer.canonicalizeDraftJson(
            "Workflow", "a".repeat(513), null, "user", minimalDefinition(), canvas("end")));
        assertThrows(BadRequestException.class, () -> canonicalizer.canonicalizeDraftJson(
            "Workflow", null, null, "owner", minimalDefinition(), canvas("end")));
    }

    @Test
    void allowsIncompleteDraftTopologyButRequiresTheExplicitEntryProperty() {
        assertDoesNotThrow(() -> canonicalize(
            "{\"schemaVersion\":1,\"entryNodeId\":null,\"nodes\":[],\"edges\":[]}",
            emptyCanvas()));
        assertThrows(BadRequestException.class, () -> canonicalize(
            "{\"schemaVersion\":1,\"nodes\":[],\"edges\":[]}",
            emptyCanvas()));
    }

    @Test
    void canonicalDraftExposesNoMutableGraphAndDefensivelyClonesItsHash() {
        WorkflowDraftCanonicalizer.CanonicalDraft draft = canonicalize(
            minimalDefinition(), canvas("end"));

        assertEquals(
            List.of(
                "name",
                "description",
                "recordType",
                "executionMode",
                "definitionJson",
                "canvasJson",
                "definitionHash"),
            Arrays.stream(WorkflowDraftCanonicalizer.CanonicalDraft.class.getRecordComponents())
                .map(component -> component.getName())
                .toList());
        assertFalse(Arrays.stream(WorkflowDraftCanonicalizer.CanonicalDraft.class.getRecordComponents())
            .anyMatch(component -> component.getType() == WorkflowDefinition.class
                || component.getType() == WorkflowCanvas.class));
        byte[] expected = draft.definitionHash();
        byte[] exposed = draft.definitionHash();
        exposed[0] ^= 0x7f;
        assertArrayEquals(expected, draft.definitionHash());
    }

    private WorkflowDraftCanonicalizer.CanonicalDraft canonicalize(
            String definitionJson, String canvasJson) {
        return canonicalizer.canonicalizeDraftJson(
            " Workflow ", "   ", " Deal ", " User ", definitionJson, canvasJson);
    }

    private static String minimalDefinition() {
        return definition("[{\"type\":\"END\",\"id\":\"end\"}]", "[]", "end");
    }

    private static String definition(String nodes, String edges, String entryNodeId) {
        String entry = entryNodeId == null ? "null" : "\"" + entryNodeId + "\"";
        return "{\"schemaVersion\":1,\"entryNodeId\":" + entry
            + ",\"nodes\":" + nodes + ",\"edges\":" + edges + "}";
    }

    private static String canvas(String... nodeIds) {
        Map<String, WorkflowCanvas.Position> positions = new LinkedHashMap<>();
        for (int index = 0; index < nodeIds.length; index++) {
            positions.put(nodeIds[index], new WorkflowCanvas.Position(
                BigDecimal.valueOf(index * 240L), BigDecimal.ZERO));
        }
        return json(new WorkflowCanvas(
            positions,
            new WorkflowCanvas.Viewport(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ONE)));
    }

    private static String emptyCanvas() {
        return canvas();
    }

    private static String json(Object value) {
        return JSON.writeValueAsString(value);
    }
}
