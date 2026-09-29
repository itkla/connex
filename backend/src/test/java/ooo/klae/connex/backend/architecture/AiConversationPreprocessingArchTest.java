package ooo.klae.connex.backend.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/** Keeps conversational prose identical across authorized name lookup and provider screening. */
class AiConversationPreprocessingArchTest {
    private static final Pattern NON_CODE = Pattern.compile(
            "/\\*.*?\\*/|//[^\\r\\n]*|\"\"\".*?\"\"\"|\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'",
            Pattern.DOTALL);
    private static final Pattern CALL = Pattern.compile(
            "\\b([A-Za-z_$][\\w$]*(?:\\s*\\.\\s*[A-Za-z_$][\\w$]*)*)\\s*\\(");
    private static final Set<String> CONTROL_FLOW = Set.of("if", "for", "catch", "while", "switch");

    @Test
    void resolverAndEveryConversationAssemblerPathUseTheSamePreprocessingEntryPoint() throws IOException {
        String resolver = compact(source("assistant/AiAssistantIdentifierResolver"));
        assertTrue(resolver.contains("MaskingEngine.mentionScanText(message)"));
        assertTrue(resolver.contains("orgWorkspaceIdsJson, scanText.lookupText(), CANDIDATE_PAGE_SIZE"));
        assertTrue(resolver.contains("MaskingEngine.containsIdentifierMention(scanText, identifierValue(candidate))"));

        String assembler = code(source("assistant/AiAssistantPromptAssembler"));
        String history = compact(methodBody(assembler, "appendHistory"));
        assertTrue(history.contains("MaskingEngine.maskConversationalFreeText( content, context)"));
        assertTrue(history.contains("MaskingEngine.maskConversationalFreeText( replay.content(), context, replay.handles())"));
        assertTrue(history.contains("MaskingEngine.maskFreeText(summary, context)"));
        String summary = compact(methodBody(assembler, "assembleSummary"));
        assertEquals(1, Pattern.compile(Pattern.quote(
                "MaskingEngine.maskFreeText( content, context)"))
                .matcher(summary).results().count());
        assertTrue(summary.contains("MaskingEngine.maskFreeText( content, context, replayHandles)"));
        assertTrue(summary.contains("replayHandles = replay.handles();"));
        assertFalse(assembler.contains("stripDurableLinks"));
        assertFalse(assembler.contains("remapHandles"));
        assertFalse(assembler.contains("setContent("));
        assertEquals(0, Pattern.compile("\\bcrmData\\s*\\(").matcher(history + summary).results().count(),
                "Already masked conversation text must not undergo CRM temporal normalization or a second masking pass");
    }

    @Test
    void conversationPathsCannotAddUnreviewedTextTransformations() throws IOException {
        String assembler = code(source("assistant/AiAssistantPromptAssembler"));
        assertCalls(assembler, "appendHistory", Set.of(
                "Map.of", "MaskingEngine.maskConversationalFreeText",
                "MaskingEngine.maskFreeText", "crmDataMasked", "equals", "message.getAuthorKind",
                "objectMapper.valueToTree", "prompt.assistantTurn", "prompt.userTurn", "reauthorizeAnswer",
                "reauthorizeSummary", "reauthorizeUser", "replay.citations", "replay.content", "replay.handles", "serialize"));
        assertCalls(assembler, "assembleSummary", Set.of(
                "Map.of", "MaskingEngine.maskFreeText", "PromptAssembly.builder",
                "crmDataMasked", "data.put", "equals", "message.getAuthorKind",
                "message.getContent", "message.getStructuredJson", "objectMapper.valueToTree", "prompt.build",
                "prompt.userTurn", "reauthorizeAnswer", "reauthorizeSummary", "reauthorizeUser", "replay.content",
                "replay.handles", "system", "transcript.add"));
        assertCalls(assembler, "reauthorizeAnswer", Set.of(
                "IllegalStateException", "List.of", "Map.of", "Map.copyOf", "ReplayAnswer",
                "citations.add", "handles.put", "stored.handle", "message.getContent",
                "message.getStructuredJson", "metadata.get", "objectMapper.readTree", "orElse",
                "resources.handleFor", "stored.id", "stored.kind", "storedCitations.isArray", "storedResource",
                "storedResources.isArray"));
        assertCalls(assembler, "reauthorizeSummary", Set.of(
                "MaskingEngine.maskField", "equals", "isEmpty", "kind.asString", "kind.isString",
                "message.getContent", "message.getStructuredJson", "metadata.get", "objectMapper.readTree",
                "resources.handleFor", "stored.id", "stored.kind", "stored.value", "storedIdentifiers.isArray",
                "storedIdentifiers.size", "storedResource", "storedResources.isArray", "storedSummaryIdentifier"));
        assertCalls(assembler, "reauthorizeUser", Set.of(
                "MaskingEngine.maskField", "equals", "isEmpty", "kind.asString", "kind.isString",
                "message.getContent", "message.getStructuredJson", "metadata.get", "objectMapper.readTree",
                "resources.handleFor", "stored.id", "stored.kind", "stored.value", "storedIdentifiers.isArray",
                "storedIdentifiers.size", "storedResourceIdentity", "storedResources.isArray",
                "storedResources.isEmpty", "storedSummaryIdentifier"));
        assertCalls(assembler, "crmDataMasked", Set.of("Map.of", "serialize"));
        assertCalls(assembler, "serialize", Set.of("IllegalStateException", "objectMapper.writeValueAsString"));
        assertTrue(compact(methodBody(assembler, "reauthorizeAnswer"))
                .contains("return new ReplayAnswer(message.getContent(), citations, Map.copyOf(handles));"));
        assertProseAssignments(assembler, "appendHistory", List.of(
                "summary = reauthorizeSummary(message, resources, context);",
                "replay = reauthorizeAnswer(message, resources);",
                "masked = MaskingEngine.maskConversationalFreeText( replay.content(), context, replay.handles());",
                "content = reauthorizeUser(message, resources, context);",
                "masked = MaskingEngine.maskConversationalFreeText( content, context);",
                "serialized = serialize(Map.of( , masked));"));
        assertProseAssignments(assembler, "assembleSummary", List.of(
                "content = message.getContent();",
                "replay = reauthorizeAnswer(message, resources);",
                "content = replay.content();",
                "content = reauthorizeUser(message, resources, context);",
                "content = reauthorizeSummary(existingSummary, resources, context);"));
        assertReturns(assembler, "reauthorizeAnswer", List.of(
                "new ReplayAnswer(message.getContent(), List.of(), Map.of())", "null", "null",
                "new ReplayAnswer(message.getContent(), citations, Map.copyOf(handles))"));
        assertReturns(assembler, "reauthorizeSummary", List.of(
                "null", "null", "null", "null", "null", "message.getContent()"));
        assertReturns(assembler, "reauthorizeUser", List.of(
                "message.getContent()", "null", "null", "null",
                "storedResources.isEmpty() ? message.getContent() : null", "null", "null", "message.getContent()"));
    }

    @Test
    void finalCleanupCannotContinueTheBoundedProjectionAfterMatching() throws IOException {
        String conversation = compact(code(source("masking/ConversationText")));
        assertTrue(conversation.contains("projectToFixedPoint(literal, false, removed).requireConverged();"));
        assertTrue(conversation.contains("projectToFixedPoint(source, true, new ArrayList<>()).requireConverged();"));
        assertTrue(conversation.contains("if (!prepareAroundIssuedTokens(text).equals(text) || !stripRecordLinks(text).equals(text))"));
        assertFalse(conversation.contains("String prepared = text;"));
        assertTrue(conversation.contains("return ISSUED_TOKEN.matcher(text).replaceAll( );"));
        String resolver = compact(code(source("assistant/AiAssistantIdentifierResolver")));
        assertTrue(resolver.contains("catch (MaskingLeakException exception)"));
        assertTrue(resolver.contains("throw AiAssistantLoopException.malformed( );"));
    }

    @Test
    void registrationAdmissionReplacementAndOutboundScanningShareConversationPreparation() throws IOException {
        String canonical = compact(source("masking/CanonicalText"));
        String masking = compact(source("masking/MaskingEngine"));
        String context = compact(source("masking/MaskingContext"));
        String outbound = compact(source("masking/OutboundLeakScan"));
        assertTrue(canonical.contains("return project(raw).value();"));
        assertTrue(canonical.contains("return projectLabels(project(raw));"));
        assertTrue(canonical.contains("return project(ConversationText.projectLabels(literal), true);"));
        assertTrue(masking.contains("CanonicalText.Projection prepared = CanonicalText.prepareProjection(text);"));
        assertTrue(masking.contains("CanonicalText.Projection literal = CanonicalText.project(prepared);"));
        assertTrue(masking.contains("CanonicalText.Projection labels = CanonicalText.projectLabels(literal);"));
        assertFalse(masking.contains("ConversationText.preprocess("));
        assertFalse(context.contains("ConversationText.preprocess("));
        assertFalse(outbound.contains("ConversationText.preprocess("));
        assertTrue(masking.contains("return ConversationText.finishMasked(replaced.toString());"));
        assertTrue(masking.contains("masked = maskFreeTextPreservingIssuedPlaceholders(masked, ctx, preserveTrustedCollisions);"));
        assertTrue(masking.contains("return CanonicalText.prepare(value);"));
        assertTrue(masking.contains("prepared = text;"));
        assertTrue(masking.contains("return CanonicalText.canonical(rawValue);"));
        assertTrue(context.contains("CanonicalText.StoredIdentifier stored = CanonicalText.storedIdentifier(rawValue);"));
        assertTrue(context.contains("String canonicalValue = stored.literal();"));
        assertTrue(context.contains("stored.label(), token,"));
        assertTrue(canonical.contains("Projection literal = project(raw);"));
        assertTrue(canonical.contains("Optional<Projection> labels = ConversationText.storedLabels(literal);"));
        assertTrue(canonical.contains("labels.map(value -> project(value, true).value()).orElse(\"\"), labels.isPresent()"));
        assertTrue(outbound.contains("return CanonicalText.canonical(value);"));
    }

    private static void assertCalls(String source, String method, Set<String> expected) {
        Set<String> actual = new LinkedHashSet<>();
        Matcher calls = CALL.matcher(methodBody(source, method));
        while (calls.find()) {
            String call = calls.group(1).replaceAll("\\s+", "");
            if (!CONTROL_FLOW.contains(call)) {
                actual.add(call);
            }
        }
        assertEquals(expected, actual,
                method + " changed: prose must reach masking unchanged; only source-mapped projections may precede matching");
    }

    private static void assertProseAssignments(String source, String method, List<String> expected) {
        List<String> actual = Pattern.compile("\\b(?:content|summary|replay|masked|serialized)\\s*\\+?=(?!=)[^;]+;")
                .matcher(compact(methodBody(source, method))).results().map(result -> result.group()).toList();
        assertEquals(expected, actual, method + " must not reconstruct prose before or after shared preprocessing");
    }

    private static void assertReturns(String source, String method, List<String> expected) {
        List<String> actual = Pattern.compile("\\breturn\\s+([^;]+);")
                .matcher(compact(methodBody(source, method))).results().map(result -> result.group(1)).toList();
        assertEquals(expected, actual, method + " must return original authorized prose unchanged");
    }

    private static String methodBody(String source, String name) {
        Matcher declaration = Pattern.compile("(?:public|private)\\s+(?:static\\s+)?[\\w<>]+\\s+"
                + Pattern.quote(name) + "\\s*\\(").matcher(source);
        assertTrue(declaration.find(), "Method source missing: " + name);
        int start = source.indexOf('{', declaration.end());
        int end = start + 1;
        int depth = 1;
        while (depth > 0 && end < source.length()) {
            char current = source.charAt(end++);
            if (current == '{') {
                depth++;
            } else if (current == '}') {
                depth--;
            }
        }
        assertEquals(0, depth, "Unbalanced method: " + name);
        return source.substring(start + 1, end - 1);
    }

    private static String code(String source) {
        return NON_CODE.matcher(source).replaceAll(" ");
    }

    private static String compact(String source) {
        return source.replaceAll("\\s+", " ");
    }

    private static String source(String name) throws IOException {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        if (!Files.exists(root.resolve("backend"))) {
            Path parent = root.getParent();
            if (parent != null) {
                root = parent;
            }
        }
        return Files.readString(root.resolve("backend/src/main/java/ooo/klae/connex/backend/ai/" + name + ".java"));
    }
}
