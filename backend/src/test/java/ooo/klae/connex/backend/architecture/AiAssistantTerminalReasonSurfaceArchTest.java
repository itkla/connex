package ooo.klae.connex.backend.architecture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.ai.assistant.AiChatTurnTerminalCoordinator;

/**
 * Binds the assistant's stable terminal-reason vocabulary to the client that has to state it.
 *
 * <p>The frontend's own enumerating test compares two client-side structures — its classifier and
 * a hand-maintained ledger — so it stays green when the server gains a reason nobody classified.
 * The member then reads the generic failure copy for a reason the server deliberately made
 * specific. This is the executable edge between the two vocabularies, and it lives on the backend
 * because that is where a new reason is added.
 *
 * <p>The direction is deliberately one-way. The client classifies reasons the server never emits —
 * {@code request_failed} and {@code reconciliation_failed} are client-side transport outcomes — so
 * an extra key here is correct, while a missing one is a member-visible defect.
 *
 * <p>{@code cancelled} is excluded with evidence rather than by omission: it is written as a
 * terminal reason by {@code AiChatMapper.cancelTurn}, but the client classifies a reason only for a
 * {@code failed} or {@code timed_out} phase ({@code askConnexSurface.ts}, {@code askConnexRecovery}
 * and {@code askConnexPhase}), and a cancelled turn carries its own label instead.
 */
class AiAssistantTerminalReasonSurfaceArchTest {

    private static final Path SURFACE = Path.of("frontend", "app", "lib", "askConnexSurface.ts");
    private static final Pattern TERMINAL_KINDS = Pattern.compile(
            "const TERMINAL_KINDS[^{]*\\{(.*?)\\n\\};", Pattern.DOTALL);
    private static final Pattern REASON_KEY = Pattern.compile(
            "(?m)^\\s{4}([a-z0-9_]+):\\s*\\{");

    @Test
    void everyStableTerminalReasonTheServerEmitsIsClassifiedByTheClient() throws Exception {
        Set<String> classified = clientClassifiedReasons();
        Set<String> missing = new TreeSet<>(serverStableReasons());
        missing.removeAll(classified);

        assertTrue(
                missing.isEmpty(),
                "These terminal reasons are emitted by AiChatTurnTerminalCoordinator but absent"
                        + " from TERMINAL_KINDS in " + SURFACE + ", so a member would be shown the"
                        + " generic failure copy for them: " + missing);
    }

    @Test
    void theClientVocabularyIsReadableAtAllSoThisGuardCannotPassVacuously() throws Exception {
        Set<String> classified = clientClassifiedReasons();

        assertFalse(classified.isEmpty(), "No terminal reason was parsed from " + SURFACE);
        assertTrue(
                classified.contains("internal_error"),
                "Parsing " + SURFACE + " did not find the reason every failure falls back to");
    }

    private static Set<String> serverStableReasons() throws ReflectiveOperationException {
        Set<String> reasons = new TreeSet<>();
        reasons.addAll(reasonSet("FAILED_REASONS"));
        reasons.addAll(reasonSet("TIMED_OUT_REASONS"));
        return reasons;
    }

    @SuppressWarnings("unchecked")
    private static Set<String> reasonSet(String fieldName) throws ReflectiveOperationException {
        Field field = AiChatTurnTerminalCoordinator.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (Set<String>) field.get(null);
    }

    private static Set<String> clientClassifiedReasons() throws IOException {
        String source = Files.readString(
                repositoryRoot().resolve(SURFACE), StandardCharsets.UTF_8);
        Matcher table = TERMINAL_KINDS.matcher(source);
        assertTrue(table.find(), "Could not locate TERMINAL_KINDS in " + SURFACE);
        Set<String> reasons = new TreeSet<>();
        Matcher keys = REASON_KEY.matcher(table.group(1));
        while (keys.find()) {
            reasons.add(keys.group(1));
        }
        return reasons;
    }

    private static Path repositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null && !Files.exists(current.resolve("backend/settings.gradle"))) {
            current = current.getParent();
        }
        return Objects.requireNonNull(current, "Could not locate the repository root");
    }
}
