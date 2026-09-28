package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.ai.provider.AiProviderCapabilities;

/**
 * Pins the assistant tool-call key grammar byte for byte and keeps it in one place.
 *
 * <p>The durable {@code ai_chat_tool_call} key is how a row is tied back to its turn and step, so
 * the rendering, the turn-prefix scan and every reader must agree. The oracle here is the grammar
 * as the readers accepted it before the key had one home: an anchored
 * {@code turn-T-step-S[-call-K]} with positive, unpadded numbers, the step bounded by the loop's
 * backstop and the ordinal by the per-step call ceiling, and an unreadably large number refused.
 */
class AiAssistantToolCallKeyTest {
    private static final Path MAIN_SOURCES = Path.of("src/main/java");
    private static final String KEY_SOURCE = "AiAssistantToolCallKey.java";
    private static final Pattern LEGACY_KEY = Pattern.compile(
            "^turn-([1-9][0-9]*)-step-([1-9][0-9]*)(?:-call-([1-9][0-9]*))?$");
    private static final Pattern GRAMMAR_LITERAL = Pattern.compile(
            "\"(?:[^\"\\\\\\n]|\\\\.)*(?:(?<![A-Za-z])turn-|-step-)(?:[^\"\\\\\\n]|\\\\.)*\""
                    + "|\"-call-");

    @Test
    void theSoleCallOfAStepKeepsTheExactUnsuffixedKey() {
        assertEquals(
                "turn-19-step-1",
                new AiAssistantToolCallKey(19, 1, AiAssistantToolCallRef.SOLE_CALL).value());
        assertEquals(
                "turn-7-step-" + AiChatAgentLoopService.HARD_MAX_STEPS,
                new AiAssistantToolCallKey(
                        7, AiChatAgentLoopService.HARD_MAX_STEPS,
                        AiAssistantToolCallRef.SOLE_CALL).value());
        assertEquals("turn-19-step-2-call-3", new AiAssistantToolCallKey(19, 2, 3).value());
    }

    @Test
    void everyRenderableKeyIsByteIdenticalAndRoundTrips() {
        for (int turnId : List.of(1, 19, Integer.MAX_VALUE)) {
            for (int step = 1; step <= AiChatAgentLoopService.HARD_MAX_STEPS; step++) {
                for (int ordinal = 0;
                        ordinal <= AiProviderCapabilities.MAX_PARALLEL_TOOL_CALLS;
                        ordinal++) {
                    AiAssistantToolCallKey key = new AiAssistantToolCallKey(turnId, step, ordinal);
                    String expected = "turn-" + turnId + "-step-" + step
                            + (ordinal == 0 ? "" : "-call-" + ordinal);

                    assertEquals(expected, key.value());
                    assertEquals(Optional.of(key), AiAssistantToolCallKey.parse(expected));
                    assertTrue(expected.startsWith(AiAssistantToolCallKey.turnPrefix(turnId)));
                }
            }
        }
    }

    @Test
    void renderingRefusesAPositionNoStepCouldHaveProduced() {
        List<int[]> refused = List.of(
                new int[] {0, 1, 0},
                new int[] {-1, 1, 0},
                new int[] {19, 0, 0},
                new int[] {19, AiChatAgentLoopService.HARD_MAX_STEPS + 1, 0});
        for (int[] position : refused) {
            IllegalArgumentException refusal = assertThrows(
                    IllegalArgumentException.class,
                    () -> new AiAssistantToolCallKey(position[0], position[1], position[2]));
            assertEquals("Assistant tool turn and step must be positive", refusal.getMessage());
        }
        for (int ordinal : List.of(-1, AiProviderCapabilities.MAX_PARALLEL_TOOL_CALLS + 1)) {
            IllegalArgumentException refusal = assertThrows(
                    IllegalArgumentException.class,
                    () -> new AiAssistantToolCallKey(19, 1, ordinal));
            assertEquals(
                    "Assistant tool call ordinal must be between 0 and "
                            + AiProviderCapabilities.MAX_PARALLEL_TOOL_CALLS,
                    refusal.getMessage());
        }
    }

    @Test
    void parsingRefusesEveryKeyTheGrammarDoesNotProduce() {
        List<String> refused = new ArrayList<>(List.of(
                "",
                "turn-19",
                "turn-19-step-",
                "turn-19-step-2-call-",
                "turn-19-step-2-call-0",
                "turn-19-step-2-call-" + (AiProviderCapabilities.MAX_PARALLEL_TOOL_CALLS + 1),
                "turn-19-step-" + (AiChatAgentLoopService.HARD_MAX_STEPS + 1),
                "turn-0-step-1",
                "turn-19-step-0",
                "turn--1-step-1",
                "turn-019-step-1",
                "turn-19-step-01",
                "turn-19-step-1-call-01",
                "turn-2147483648-step-1",
                "turn-19-step-99999999999",
                "turn-19-step-1-call-99999999999",
                " turn-19-step-1",
                "turn-19-step-1 ",
                "turn-19-step-1\n",
                "Turn-19-step-1",
                "xturn-19-step-1",
                "turn-19-step-1x",
                "turn-19-step-1-call-2x",
                "turn-19-step-1-row-1",
                "turn-19-step-1-call-1-row-1",
                "turn-19-step-1-call-1-call-2",
                "turn-１-step-1"));

        assertEquals(Optional.empty(), AiAssistantToolCallKey.parse(null));
        for (String key : refused) {
            assertEquals(Optional.empty(), AiAssistantToolCallKey.parse(key), key);
        }
    }

    /**
     * Compares the parser against the grammar the two readers each held their own copy of.
     *
     * <p>The corpus is every short sequence of the grammar's own fragments and of the near misses
     * around it, plus seeded random splices of the same alphabet, so the one parser is shown to
     * accept and reject exactly what both readers did, malformed keys included.
     */
    @Test
    void parsingAcceptsAndRefusesExactlyWhatTheReadersDidBefore() {
        List<String> alphabet = List.of(
                "turn-", "-step-", "-call-", "-row-", "0", "1", "4", "5", "9", "19", "64", "65",
                "01", "2147483647", "2147483648", "-", " ", "x");
        List<String> corpus = new ArrayList<>();
        corpus.add("");
        for (String first : alphabet) {
            for (String second : alphabet) {
                for (String third : alphabet) {
                    corpus.add(first + second + third);
                    corpus.add("turn-" + first + "-step-" + second + third);
                    corpus.add("turn-" + first + "-step-" + second + "-call-" + third);
                }
            }
        }
        Random random = new Random(1_787L);
        for (int sample = 0; sample < 20_000; sample++) {
            StringBuilder key = new StringBuilder();
            int parts = 1 + random.nextInt(7);
            for (int part = 0; part < parts; part++) {
                key.append(alphabet.get(random.nextInt(alphabet.size())));
            }
            corpus.add(key.toString());
        }

        int accepted = 0;
        for (String key : corpus) {
            Optional<Integer> legacyTurn = legacyTurn(key);
            Optional<AiAssistantToolCallKey> parsed = AiAssistantToolCallKey.parse(key);
            assertEquals(legacyTurn, parsed.map(AiAssistantToolCallKey::turnId), key);
            assertEquals(legacyStep(key, 19), parsed
                    .filter(candidate -> candidate.turnId() == 19)
                    .map(AiAssistantToolCallKey::stepNumber)
                    .orElse(AiChatAgentLoopService.HARD_MAX_STEPS), key);
            if (parsed.isPresent()) {
                accepted++;
                assertEquals(key, parsed.get().value());
            }
        }
        assertTrue(accepted >= 200, "the corpus must exercise the accepting side too");
    }

    @Test
    void theTurnPrefixMatchesOnlyItsOwnTurnsKeys() {
        assertEquals("turn-7-step-", AiAssistantToolCallKey.turnPrefix(7));
        assertTrue(new AiAssistantToolCallKey(7, 2, 3).value()
                .startsWith(AiAssistantToolCallKey.turnPrefix(7)));
        assertFalse(new AiAssistantToolCallKey(70, 2, 0).value()
                .startsWith(AiAssistantToolCallKey.turnPrefix(7)));
    }

    /**
     * Keeps the grammar from growing a second copy.
     *
     * <p>Two readers once held their own regular expression and the producer a third rendering, so
     * a new shape had to be taught to each separately and a reader that missed it dropped the row
     * silently. Any production string literal spelling the grammar outside the key class is that
     * divergence starting again.
     */
    @Test
    void noOtherProductionSourceSpellsTheKeyGrammar() throws IOException {
        List<String> violations = new ArrayList<>();
        try (Stream<Path> sources = Files.walk(MAIN_SOURCES)) {
            for (Path source : sources.filter(path -> path.toString().endsWith(".java")).toList()) {
                if (source.getFileName().toString().equals(KEY_SOURCE)) {
                    continue;
                }
                Matcher literal = GRAMMAR_LITERAL.matcher(
                        Files.readString(source, StandardCharsets.UTF_8));
                while (literal.find()) {
                    violations.add(source + ": " + literal.group());
                }
            }
        }
        assertEquals(List.of(), violations);
    }

    private static Optional<Integer> legacyTurn(String key) {
        Matcher matcher = LEGACY_KEY.matcher(key);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        try {
            int turnId = Integer.parseInt(matcher.group(1));
            int stepNumber = Integer.parseInt(matcher.group(2));
            String callOrdinal = matcher.group(3);
            if (stepNumber > AiChatAgentLoopService.HARD_MAX_STEPS
                    || (callOrdinal != null
                            && Integer.parseInt(callOrdinal)
                                    > AiProviderCapabilities.MAX_PARALLEL_TOOL_CALLS)) {
                return Optional.empty();
            }
            return Optional.of(turnId);
        } catch (NumberFormatException exception) {
            return Optional.empty();
        }
    }

    private static int legacyStep(String key, int turnId) {
        Matcher matcher = LEGACY_KEY.matcher(key);
        if (!matcher.matches()) {
            return AiChatAgentLoopService.HARD_MAX_STEPS;
        }
        try {
            if (Integer.parseInt(matcher.group(1)) != turnId) {
                return AiChatAgentLoopService.HARD_MAX_STEPS;
            }
            String ordinal = matcher.group(3);
            if (ordinal != null
                    && Integer.parseInt(ordinal)
                            > AiProviderCapabilities.MAX_PARALLEL_TOOL_CALLS) {
                return AiChatAgentLoopService.HARD_MAX_STEPS;
            }
            return Math.min(
                    Integer.parseInt(matcher.group(2)), AiChatAgentLoopService.HARD_MAX_STEPS);
        } catch (NumberFormatException exception) {
            return AiChatAgentLoopService.HARD_MAX_STEPS;
        }
    }
}
