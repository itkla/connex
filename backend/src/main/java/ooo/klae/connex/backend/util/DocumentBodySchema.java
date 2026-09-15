package ooo.klae.connex.backend.util;

import java.util.Set;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import ooo.klae.connex.backend.exceptions.BadRequestException;

/** Validates node shapes and placements against the public preview's ProseMirror/Tiptap contract. */
public final class DocumentBodySchema {
    private static final Set<String> BLOCKS = Set.of(
        "paragraph", "heading", "bulletList", "orderedList", "blockquote", "codeBlock",
        "horizontalRule", "lineItems");
    private static final Set<String> INLINE = Set.of("text", "hardBreak", "mergeToken");
    private static final int MAX_DEPTH = 50;
    private static final int MAX_NODES = 5000;

    private DocumentBodySchema() {
    }

    /** Rejects malformed, unsupported or misplaced nodes before persistence or certification. */
    public static void validate(JsonNode root) {
        if (root == null || !root.isObject() || !root.path("type").isString()
                || !"doc".equals(root.path("type").asString())) {
            throw new BadRequestException("Document body must be a document at body");
        }
        validateNode(root, Set.of("doc"), "root", "body", 0, new int[] { 0 });
    }

    /** Refuses delivery or acceptance of frozen content whose body cannot be rendered faithfully. */
    public static void validateFrozenContent(String content, ObjectMapper objectMapper) {
        JsonNode frozen;
        try {
            frozen = content == null ? null : objectMapper.readTree(content);
        } catch (RuntimeException invalid) {
            throw new BadRequestException("Document content cannot be presented; create a new document version");
        }
        if (frozen == null || !frozen.isObject()) {
            throw new BadRequestException("Document content cannot be presented; create a new document version");
        }
        JsonNode body = frozen.get("body");
        if (body != null && !body.isNull()) {
            validate(body);
        }
    }

    private static void validateNode(
            JsonNode node, Set<String> allowed, String parent, String path, int depth, int[] count) {
        if (depth > MAX_DEPTH) {
            throw new BadRequestException("Document body is nested too deeply");
        }
        if (++count[0] > MAX_NODES) {
            throw new BadRequestException("Document body is too large");
        }
        if (!node.isObject() || !node.path("type").isString()) {
            throw new BadRequestException("Document body nodes must have a supported type at " + path);
        }
        validateNodeShape(node, path);
        String type = node.path("type").asString();
        if (!allowed.contains(type)) {
            if ("lineItems".equals(type)) {
                throw new BadRequestException(
                    "Line items must appear as a block, outside paragraphs and other text containers");
            }
            throw new BadRequestException("Document body contains an unsupported node placement in " + parent);
        }
        JsonNode content = node.get("content");
        if ("text".equals(type) && !node.path("text").isString()) {
            throw new BadRequestException("Document text nodes must contain text at " + path);
        }
        Set<String> children = switch (type) {
            case "doc", "blockquote", "listItem" -> BLOCKS;
            case "bulletList", "orderedList" -> Set.of("listItem");
            case "paragraph", "heading" -> INLINE;
            case "codeBlock" -> Set.of("text");
            default -> Set.of();
        };
        if ("listItem".equals(type)
                && (content == null || content.isEmpty()
                    || !content.get(0).path("type").isString()
                    || !"paragraph".equals(content.get(0).path("type").asString()))) {
            throw new BadRequestException("Document list items must start with a paragraph");
        }
        if (content != null) {
            for (int index = 0; index < content.size(); index++) {
                validateNode(content.get(index), children, type, path + ".content[" + index + "]",
                    depth + 1, count);
            }
        }
    }

    private static void validateNodeShape(JsonNode node, String path) {
        validateAttributes(node, path);
        JsonNode content = node.get("content");
        if (content != null && !content.isArray()) {
            throw new BadRequestException("Document node content must be an array at " + path + ".content");
        }
        JsonNode marks = node.get("marks");
        if (marks != null) {
            if (!marks.isArray()) {
                throw new BadRequestException("Document node marks must be an array at " + path + ".marks");
            }
            for (int index = 0; index < marks.size(); index++) {
                JsonNode mark = marks.get(index);
                String markPath = path + ".marks[" + index + "]";
                if (!mark.isObject() || !mark.path("type").isString()) {
                    throw new BadRequestException(
                        "Document marks must be objects with a string type at " + markPath);
                }
                validateAttributes(mark, markPath);
            }
        }
        JsonNode text = node.get("text");
        if (text != null && !text.isString()) {
            throw new BadRequestException("Document node text must be a string at " + path + ".text");
        }
    }

    private static void validateAttributes(JsonNode node, String path) {
        JsonNode attributes = node.get("attrs");
        if (attributes != null && !attributes.isObject()) {
            throw new BadRequestException("Document attributes must be an object at " + path + ".attrs");
        }
    }
}
