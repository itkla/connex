package ooo.klae.connex.backend.util;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import tools.jackson.databind.ObjectMapper;

import ooo.klae.connex.backend.exceptions.BadRequestException;

/** Covers every rejection rule in frontend/app/lib/api.ts's public document body and mark validators. */
class DocumentBodySchemaTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidPreviewBodyShapes")
    void rejectsEveryInvalidPreviewBodyShape(String rule, String body) {
        assertThrows(BadRequestException.class,
            () -> DocumentBodySchema.validate(objectMapper.readTree(body)), rule);
        assertThrows(BadRequestException.class,
            () -> DocumentBodySchema.validateFrozenContent("{\"body\":" + body + "}", objectMapper), rule);
    }

    private static Stream<Arguments> invalidPreviewBodyShapes() {
        Stream.Builder<Arguments> cases = Stream.builder();
        for (String value : List.of("false", "0", "\"value\"", "[]")) {
            cases.add(Arguments.of("root must be an object: " + value, value));
        }
        for (String value : List.of("null", "false", "0", "\"value\"", "[]")) {
            cases.add(Arguments.of("child must be an object: " + value, document(value)));
            cases.add(Arguments.of("node attrs must be an object: " + value,
                document("{\"type\":\"paragraph\",\"attrs\":" + value + "}")));
            cases.add(Arguments.of("mark must be an object: " + value,
                markedText("[" + value + "]")));
            cases.add(Arguments.of("mark attrs must be an object: " + value,
                markedText("[{\"type\":\"bold\",\"attrs\":" + value + "}]")));
        }
        for (String value : List.of("null", "false", "0", "[]", "{}")) {
            cases.add(Arguments.of("node type must be a string: " + value,
                document("{\"type\":" + value + "}")));
            cases.add(Arguments.of("mark type must be a string: " + value,
                markedText("[{\"type\":" + value + "}]")));
            cases.add(Arguments.of("optional text must be a string on any node: " + value,
                document("{\"type\":\"paragraph\",\"text\":" + value + "}")));
            cases.add(Arguments.of("text node text must be a string: " + value,
                document(paragraph("{\"type\":\"text\",\"text\":" + value + "}"))));
        }
        for (String value : List.of("null", "false", "0", "\"value\"", "{}")) {
            cases.add(Arguments.of("content must be an array: " + value,
                document("{\"type\":\"paragraph\",\"content\":" + value + "}")));
            cases.add(Arguments.of("marks must be an array: " + value, markedText(value)));
        }
        cases.add(Arguments.of("node type is required", document("{}")));
        cases.add(Arguments.of("mark type is required", markedText("[{}]")));
        cases.add(Arguments.of("text node text is required", document(paragraph("{\"type\":\"text\"}"))));
        cases.add(Arguments.of("root type must be doc", "{\"type\":\"paragraph\"}"));
        cases.add(Arguments.of("unsupported node types are refused", document("{\"type\":\"unknown\"}")));
        cases.add(Arguments.of("doc children must be blocks", document("{\"type\":\"text\",\"text\":\"x\"}")));
        cases.add(Arguments.of("blockquote children must be blocks",
            document("{\"type\":\"blockquote\",\"content\":[{\"type\":\"hardBreak\"}]}")));
        cases.add(Arguments.of("listItem children after the paragraph must be blocks",
            document("{\"type\":\"bulletList\",\"content\":[{\"type\":\"listItem\",\"content\":["
                + "{\"type\":\"paragraph\"},{\"type\":\"hardBreak\"}]}]}")));
        for (String type : List.of("bulletList", "orderedList")) {
            cases.add(Arguments.of(type + " children must be listItem",
                document("{\"type\":\"" + type + "\",\"content\":[{\"type\":\"paragraph\"}]}")));
        }
        for (String type : List.of("paragraph", "heading", "codeBlock", "horizontalRule", "lineItems")) {
            cases.add(Arguments.of(type + " cannot contain blocks",
                document("{\"type\":\"" + type + "\",\"content\":[{\"type\":\"lineItems\"}]}")));
        }
        cases.add(Arguments.of("codeBlock children must be text",
            document("{\"type\":\"codeBlock\",\"content\":[{\"type\":\"hardBreak\"}]}")));
        for (String type : List.of("text", "hardBreak", "mergeToken")) {
            cases.add(Arguments.of(type + " must be a leaf",
                document(paragraph("{\"type\":\"" + type + "\",\"text\":\"x\","
                    + "\"content\":[{\"type\":\"text\",\"text\":\"x\"}]}"))));
        }
        for (String content : List.of("", ",\"content\":[]", ",\"content\":[null]",
                ",\"content\":[{\"type\":\"heading\"}]")) {
            cases.add(Arguments.of("listItem must start with a paragraph: " + content,
                document("{\"type\":\"bulletList\",\"content\":[{\"type\":\"listItem\"" + content + "}]}")));
        }
        cases.add(Arguments.of("depth must not exceed 50", nestedBody(49)));
        cases.add(Arguments.of("node count must not exceed 5000",
            document(String.join(",", Collections.nCopies(5000, "{\"type\":\"paragraph\"}")))));
        return cases.build();
    }

    @Test
    void reportsNestedMarkAttributePath() {
        String body = markedText("[{\"type\":\"bold\",\"attrs\":null}]");

        BadRequestException refusal = assertThrows(BadRequestException.class,
            () -> DocumentBodySchema.validate(objectMapper.readTree(body)));

        assertTrue(refusal.getMessage().contains("body.content[0].content[0].marks[0].attrs"));
    }

    @Test
    void acceptsPreviewCompatibleOptionalFieldsAndMarks() {
        String body = """
            {"type":"doc","attrs":{},"marks":[],"text":"","content":[
              {"type":"paragraph","attrs":{"textAlign":"left"},"text":"","marks":[],"content":[
                {"type":"text","text":"Terms","attrs":{},"content":[],"marks":[
                  {"type":"bold"},{"type":"link","attrs":{"href":"https://example.test"}},
                  {"type":"","attrs":{}},{"type":"custom"}]},
                {"type":"hardBreak"},{"type":"mergeToken","attrs":{"token":"total"}}]},
              {"type":"lineItems"}]}
            """;

        assertDoesNotThrow(() -> DocumentBodySchema.validate(objectMapper.readTree(body)));
        assertDoesNotThrow(() -> DocumentBodySchema.validateFrozenContent("{\"body\":" + body + "}", objectMapper));
    }

    @Test
    void acceptsDepthAndNodeCountBoundaries() {
        assertDoesNotThrow(() -> DocumentBodySchema.validate(objectMapper.readTree(nestedBody(48))));
        String body = document(String.join(",", Collections.nCopies(4999, "{\"type\":\"paragraph\"}")));
        assertDoesNotThrow(() -> DocumentBodySchema.validate(objectMapper.readTree(body)));
        BadRequestException refusal = assertThrows(BadRequestException.class,
            () -> DocumentBodySchema.validate(objectMapper.readTree(nestedBody(49))));
        assertEquals("Document body is nested too deeply", refusal.getMessage());
    }

    private static String markedText(String marks) {
        return document(paragraph("{\"type\":\"text\",\"text\":\"Terms\",\"marks\":" + marks + "}"));
    }

    private static String document(String children) {
        return "{\"type\":\"doc\",\"content\":[" + children + "]}";
    }

    private static String paragraph(String children) {
        return "{\"type\":\"paragraph\",\"content\":[" + children + "]}";
    }

    private static String nestedBody(int levels) {
        String node = paragraph("{\"type\":\"text\",\"text\":\"x\"}");
        for (int index = 0; index < levels; index++) {
            node = "{\"type\":\"blockquote\",\"content\":[" + node + "]}";
        }
        return document(node);
    }
}
