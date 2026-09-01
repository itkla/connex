package ooo.klae.connex.backend.architecture;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

import ooo.klae.connex.backend.controllers.AiAssistantController;

/**
 * Guards the assistant-session privacy audit structurally, so a new or refactored route cannot
 * silently disclose another member's session without an accountability record.
 *
 * <p>Three rules close the loop. Every HTTP handler must be classified as disclosing or exempt when
 * it is introduced; every shared authorization choke point must retain its exact audit call; and
 * every accessible-scope disclosing handler must name a route in the behavioural matrix that proves
 * at runtime that the row is actually written.
 */
class AiSessionReadAuditArchTest {
    private static final Path SOURCE_ROOT = Path.of("src/main/java");
    private static final Path BEHAVIOURAL_TEST = Path.of(
            "src/test/java/ooo/klae/connex/backend/services/AiAssistantServiceTest.java");

    private static final Map<String, String> DISCLOSING_ACCESSIBLE = Map.ofEntries(
            Map.entry("page", "page"),
            Map.entry("invitations", "invitations"),
            Map.entry("get", "get"),
            Map.entry("listAttachments", "attachments"),
            Map.entry("listToolCalls", "toolCalls"),
            Map.entry("getToolCall", "toolCall"));

    private static final Map<String, String> DISCLOSING_UNIT_COVERED = Map.of(
            "startTurn",
            "src/test/java/ooo/klae/connex/backend/ai/assistant/"
                    + "AiChatTurnPersistenceServiceTest.java");

    private static final Set<String> DISCLOSING_RETAINED = Set.of(
            "pageRetained",
            "getRetained",
            "listRetainedToolCalls",
            "getRetainedToolCall");

    private static final Map<String, String> EXEMPT = Map.ofEntries(
            Map.entry("create", "creates only the caller's own session"),
            Map.entry("update", "changes only a caller-owned session"),
            Map.entry("setShared", "changes only a caller-owned session"),
            Map.entry("invite", "changes only a caller-owned session relationship"),
            Map.entry("join", "accepts an invitation the owner addressed to the caller, and "
                    + "returns only metadata the audited invitation list already recorded"),
            Map.entry("getTurn", "a polling surface whose partial answer text is a view of the "
                    + "transcript already recorded at the session read; auditing it under the "
                    + "membership, session and turn locks it holds would invert lock order "
                    + "against membership mutation"),
            Map.entry("participants", "membership metadata only, carrying no transcript, "
                    + "attachment or tool-call content, and refreshed on every realtime frame"),
            Map.entry("presence", "liveness and typing state only, polled on a four-second "
                    + "heartbeat; recording it would bury the trail rather than document a "
                    + "disclosure"),
            Map.entry("touchPresence", "the same four-second liveness heartbeat, writing only "
                    + "ephemeral presence"),
            Map.entry("leave", "acts only on the caller's participant relationship"),
            Map.entry("removeParticipant", "changes only a caller-owned session relationship"),
            Map.entry("leavePresence", "removes only the caller's ephemeral presence"),
            Map.entry("archive", "archives only a caller-owned session"),
            Map.entry("uploadAttachment", "returns only the attachment the caller uploaded"),
            Map.entry("deleteAttachment", "returns no session content"),
            Map.entry("appendMessage", "returns only the message the caller authored"),
            Map.entry("previewScope", "reads no assistant session or transcript"),
            Map.entry("cancelTurn", "returns no session content"),
            Map.entry("approveToolCall", "acts on the caller-authorized approval relationship"),
            Map.entry("rejectToolCall", "acts on the caller-authorized approval relationship"),
            Map.entry("undoToolCall", "acts on the caller-authorized undo relationship"));

    private static final Map<String, Map<String, String>> CHOKE_POINTS = Map.of(
            "ooo/klae/connex/backend/services/AiAssistantService.java",
            Map.of(
                    "get", "sessionReadAudit.recordAccessible(",
                    "page", "sessionReadAudit.recordAccessible(",
                    "pageInvitations", "sessionReadAudit.recordAccessible("),
            "ooo/klae/connex/backend/ai/assistant/AiAssistantToolCallReadService.java",
            Map.of("requireReadableSession", "sessionReadAudit.recordAccessible("),
            "ooo/klae/connex/backend/ai/assistant/AiChatAttachmentService.java",
            Map.of("requireAccessibleSession", "sessionReadAudit.recordAccessible("),
            "ooo/klae/connex/backend/ai/assistant/AiChatTurnPersistenceService.java",
            Map.of(
                    "queue#AiChatQueryScope scope", "auditAgentLoopRead(",
                    "auditAgentLoopRead", "sessionReadAudit.recordAccessible("));

    @Test
    void everyAssistantHandlerHasExactlyOneDisclosureClassification() {
        List<String> violations = new ArrayList<>();
        for (Method method : AiAssistantController.class.getDeclaredMethods()) {
            if (!isHandler(method)) {
                continue;
            }
            boolean disclosing = DISCLOSING_ACCESSIBLE.containsKey(method.getName())
                    || DISCLOSING_UNIT_COVERED.containsKey(method.getName())
                    || DISCLOSING_RETAINED.contains(method.getName());
            boolean exempt = EXEMPT.containsKey(method.getName());
            if (disclosing == exempt) {
                violations.add(method.getName());
            }
        }
        assertTrue(violations.isEmpty(),
                "Assistant handlers must appear in exactly one of DISCLOSING or EXEMPT: "
                        + violations);
    }

    @Test
    void everyAdministrativeReadChokePointStillRecords() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, Map<String, String>> sourceEntry : CHOKE_POINTS.entrySet()) {
            String source = Files.readString(
                    SOURCE_ROOT.resolve(sourceEntry.getKey()), StandardCharsets.UTF_8);
            for (Map.Entry<String, String> methodEntry : sourceEntry.getValue().entrySet()) {
                String key = methodEntry.getKey();
                int hash = key.indexOf('#');
                String methodName = hash < 0 ? key : key.substring(0, hash);
                String discriminator = hash < 0 ? "" : key.substring(hash + 1);
                String body = methodBody(source, methodName, discriminator);
                if (!body.contains(methodEntry.getValue())) {
                    violations.add(key);
                }
            }
        }
        assertTrue(violations.isEmpty(),
                "Assistant administrative-read choke points missing their audit call: "
                        + violations);
    }

    @Test
    void everyAccessibleDisclosingHandlerIsExercisedByTheBehaviouralRouteMatrix()
            throws IOException {
        String source = Files.readString(BEHAVIOURAL_TEST, StandardCharsets.UTF_8);
        int start = source.indexOf("@ValueSource(strings = {");
        assertTrue(start >= 0, "Behavioural route matrix @ValueSource is missing");
        int end = source.indexOf("})", start);
        assertTrue(end > start, "Behavioural route matrix @ValueSource is not terminated");
        String matrix = source.substring(start, end);
        List<String> missing = DISCLOSING_ACCESSIBLE.values().stream()
                .distinct()
                .filter(route -> !matrix.contains('"' + route + '"'))
                .sorted()
                .toList();
        assertTrue(missing.isEmpty(),
                "Disclosing assistant routes absent from the behavioural audit matrix: "
                        + missing);
    }

    @Test
    void everyUnitCoveredDisclosingHandlerIsAssertedByItsNamedTest() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, String> entry : DISCLOSING_UNIT_COVERED.entrySet()) {
            String source = Files.readString(
                    Path.of(entry.getValue()), StandardCharsets.UTF_8);
            if (!source.contains("recordAccessible")) {
                violations.add(entry.getKey());
            }
        }
        assertTrue(violations.isEmpty(),
                "Disclosing assistant routes whose named test asserts no audit record: "
                        + violations);
    }

    private static boolean isHandler(Method method) {
        return Modifier.isPublic(method.getModifiers())
                && (method.isAnnotationPresent(GetMapping.class)
                    || method.isAnnotationPresent(PostMapping.class)
                    || method.isAnnotationPresent(PutMapping.class)
                    || method.isAnnotationPresent(PatchMapping.class)
                    || method.isAnnotationPresent(DeleteMapping.class));
    }

    private static String methodBody(
            String source, String methodName, String signatureDiscriminator) {
        String[] lines = source.split("\\R", -1);
        int offset = 0;
        int signature = -1;
        for (String line : lines) {
            String trimmed = line.trim();
            if ((trimmed.startsWith("public ")
                    || trimmed.startsWith("private ")
                    || trimmed.startsWith("protected "))
                    && trimmed.matches(".*\\b" + methodName + "\\s*\\(.*")
                    && (signatureDiscriminator.isEmpty()
                        || signatureOf(source, offset).contains(signatureDiscriminator))) {
                signature = offset;
                break;
            }
            offset += line.length() + 1;
        }
        if (signature < 0) {
            throw new AssertionError("Could not find method " + methodName);
        }
        int openingBrace = source.indexOf('{', signature);
        if (openingBrace < 0) {
            throw new AssertionError("Could not find method body for " + methodName);
        }
        int closingBrace = matchingBrace(source, openingBrace);
        if (closingBrace < 0) {
            throw new AssertionError("Could not match method body for " + methodName);
        }
        return source.substring(openingBrace + 1, closingBrace);
    }

    private static String signatureOf(String source, int signatureStart) {
        int openingBrace = source.indexOf('{', signatureStart);
        return openingBrace < 0 ? "" : source.substring(signatureStart, openingBrace);
    }

    private static int matchingBrace(String source, int openingBrace) {
        int depth = 0;
        boolean quoted = false;
        boolean character = false;
        boolean escaped = false;
        boolean lineComment = false;
        boolean blockComment = false;
        for (int index = openingBrace; index < source.length(); index++) {
            char current = source.charAt(index);
            char next = index + 1 < source.length() ? source.charAt(index + 1) : '\0';
            if (lineComment) {
                if (current == '\n') {
                    lineComment = false;
                }
                continue;
            }
            if (blockComment) {
                if (current == '*' && next == '/') {
                    blockComment = false;
                    index++;
                }
                continue;
            }
            if (!quoted && !character && current == '/' && next == '/') {
                lineComment = true;
                index++;
                continue;
            }
            if (!quoted && !character && current == '/' && next == '*') {
                blockComment = true;
                index++;
                continue;
            }
            if ((quoted || character) && current == '\\' && !escaped) {
                escaped = true;
                continue;
            }
            if (current == '"' && !character && !escaped) {
                quoted = !quoted;
                continue;
            }
            if (current == '\'' && !quoted && !escaped) {
                character = !character;
                continue;
            }
            if (escaped) {
                escaped = false;
                continue;
            }
            if (quoted || character) {
                continue;
            }
            if (current == '{') {
                depth++;
            } else if (current == '}' && --depth == 0) {
                return index;
            }
        }
        return -1;
    }
}
