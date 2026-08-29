package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.ai.provider.AiReasoningMode;

class AiAssistantTextDeltaProjectorTest {
    @Test
    void jsonReactEmitsOnlyDecodedTerminalTextWithUtf16Offsets() {
        List<String> deltas = new ArrayList<>();
        AiAssistantTextDeltaProjector projector = new AiAssistantTextDeltaProjector(
                AiAssistantTextDeltaProjector.Shape.JSON_REACT, deltas::add);

        projector.accept("{\"tool\":null,\"final\":{\"text\":\"Hi ");
        projector.accept("\\uD83D");
        projector.accept("\\uDE00\\nthere\",\"citations\":[]}}");

        assertEquals("Hi 😀\nthere", projector.finish());
        assertEquals("Hi 😀\nthere", String.join("", deltas));
        assertEquals(11, String.join("", deltas).length());
    }

    /**
     * A batch boundary can fall anywhere, including inside a masking placeholder. Releasing the
     * half of it that has arrived would show the member the masked form of a name that is about to
     * resolve, so the fragment is withheld until the placeholder completes.
     */
    @Test
    void aPlaceholderSplitAcrossChunksIsWithheldUntilItCompletes() {
        List<String> deltas = new ArrayList<>();
        AiAssistantTextDeltaProjector projector = new AiAssistantTextDeltaProjector(
                AiAssistantTextDeltaProjector.Shape.JSON_REACT, deltas::add);

        projector.accept("{\"tool\":null,\"final\":{\"text\":\"Call {{P");
        assertEquals("Call ", String.join("", deltas));

        projector.accept("1}} today\",\"citations\":[]}}");

        assertEquals("Call {{P1}} today", projector.finish());
        assertEquals("Call {{P1}} today", String.join("", deltas));
    }

    /**
     * A brace pair the model wrote as ordinary prose must not stall the stream: past the longest
     * fragment that could still become a placeholder it is released as the literal text it is.
     */
    @Test
    void aBraceInOrdinaryProseIsReleasedRatherThanStallingTheStream() {
        List<String> deltas = new ArrayList<>();
        AiAssistantTextDeltaProjector projector = new AiAssistantTextDeltaProjector(
                AiAssistantTextDeltaProjector.Shape.JSON_REACT, deltas::add);

        projector.accept("{\"tool\":null,\"final\":{\"text\":\"Use {{ this literal brace run");

        assertEquals("Use {{ this literal brace run", String.join("", deltas));
    }

    /** A closing brace pair still streams once the terminal text is closed. */
    @Test
    void aTrailingFragmentIsReleasedWhenTheTerminalTextCloses() {
        List<String> deltas = new ArrayList<>();
        AiAssistantTextDeltaProjector projector = new AiAssistantTextDeltaProjector(
                AiAssistantTextDeltaProjector.Shape.JSON_REACT, deltas::add);

        projector.accept("{\"tool\":null,\"final\":{\"text\":\"Ends with {\",\"citations\":[]}}");

        assertEquals("Ends with {", projector.finish());
        assertEquals("Ends with {", String.join("", deltas));
    }

    @Test
    void toolStepNeverProjectsNestedArgumentText() {
        List<String> deltas = new ArrayList<>();
        AiAssistantTextDeltaProjector projector = new AiAssistantTextDeltaProjector(
                AiAssistantTextDeltaProjector.Shape.JSON_REACT, deltas::add);

        projector.accept("{\"tool\":{\"name\":\"search\",\"args\":{\"text\":\"secret\"}},\"final\":null}");

        assertFalse(projector.hasProjectedText());
        assertEquals(List.of(), deltas);
    }

    @Test
    void nativeFinalProjectsTopLevelTextAcrossEscapes() {
        List<String> deltas = new ArrayList<>();
        AiAssistantTextDeltaProjector projector = new AiAssistantTextDeltaProjector(
                AiAssistantTextDeltaProjector.Shape.NATIVE_FINAL, deltas::add);

        projector.accept("{\"text\":\"A\\t");
        projector.accept("B\",\"citations\":[]}");

        assertEquals("A\tB", projector.finish());
        assertEquals("A\tB", String.join("", deltas));
    }

    @Test
    void finalBeforeToolBuffersTextUntilTheTerminalBranchIsConfirmed() {
        List<String> deltas = new ArrayList<>();
        AiAssistantTextDeltaProjector projector = new AiAssistantTextDeltaProjector(
                AiAssistantTextDeltaProjector.Shape.JSON_REACT, deltas::add);

        projector.accept("{\"final\":{\"citations\":[],\"text\":\"answer\","+
                "\"suggestions\":[],\"title\":null},");

        assertEquals(List.of(), deltas);

        projector.accept("\"tool\":null}");

        assertEquals("answer", projector.finish());
        assertEquals(List.of("answer"), deltas);
    }

    @Test
    void knownFinalMembersMayPrecedeStreamedText() {
        List<String> deltas = new ArrayList<>();
        AiAssistantTextDeltaProjector projector = new AiAssistantTextDeltaProjector(
                AiAssistantTextDeltaProjector.Shape.JSON_REACT, deltas::add);

        projector.accept("{\"tool\":null,\"final\":{\"citations\":[],\"title\":null,"+
                "\"text\":\"ordered");
        projector.accept(" safely\",\"suggestions\":[]}}");

        assertEquals("ordered safely", projector.finish());
        assertEquals("ordered safely", String.join("", deltas));
    }

    @Test
    void nativeKnownMembersMayPrecedeStreamedText() {
        List<String> deltas = new ArrayList<>();
        AiAssistantTextDeltaProjector projector = new AiAssistantTextDeltaProjector(
                AiAssistantTextDeltaProjector.Shape.NATIVE_FINAL, deltas::add);

        projector.accept("{\"title\":null,\"citations\":[],\"text\":\"native");
        projector.accept(" answer\",\"suggestions\":[]}");

        assertEquals("native answer", projector.finish());
        assertEquals("native answer", String.join("", deltas));
    }

    @Test
    void nestedEscapedAndDuplicateDecoysNeverProject() {
        for (String raw : List.of(
                "{\"decoy\":{\"tool\":null,\"final\":{\"text\":\"leak\"}},"
                        + "\"tool\":null,\"final\":null}",
                "{\"note\":\"\\\"tool\\\":null,\\\"final\\\":{\\\"text\\\":\\\"leak\\\"}\","
                        + "\"tool\":null,\"final\":null}",
                "{\"tool\":null,\"tool\":null,\"final\":{\"text\":\"leak\"}}")) {
            List<String> deltas = new ArrayList<>();
            AiAssistantTextDeltaProjector projector = new AiAssistantTextDeltaProjector(
                    AiAssistantTextDeltaProjector.Shape.JSON_REACT, deltas::add);

            projector.accept(raw);

            assertFalse(projector.hasProjectedText());
            assertEquals(List.of(), deltas);
            assertThrows(AiAssistantLoopException.class, projector::finish);
        }
    }

    @Test
    void nativeNestedTextNeverProjects() {
        List<String> deltas = new ArrayList<>();
        AiAssistantTextDeltaProjector projector = new AiAssistantTextDeltaProjector(
                AiAssistantTextDeltaProjector.Shape.NATIVE_FINAL, deltas::add);

        projector.accept("{\"wrapper\":{\"text\":\"leak\"},\"text\":\"answer\"}");

        assertFalse(projector.hasProjectedText());
        assertThrows(AiAssistantLoopException.class, projector::finish);
    }

    @Test
    void taggedReasoningSplitAcrossOpeningAndClosingChunksIsNeverProjected() {
        List<String> deltas = new ArrayList<>();
        AiAssistantTextDeltaProjector projector = new AiAssistantTextDeltaProjector(
                AiAssistantTextDeltaProjector.Shape.JSON_REACT,
                AiReasoningMode.TAGGED,
                deltas::add);

        projector.accept("\n<thi");
        projector.accept("nking>private plan</think");
        projector.accept("ing>\n{\"tool\":null,\"final\":{\"text\":\"Safe");
        projector.accept(" answer\",\"citations\":[],\"suggestions\":[],\"title\":null}}");

        assertEquals("Safe answer", projector.finish());
        assertEquals("Safe answer", String.join("", deltas));
    }

    @Test
    void taggedModeWithoutReasoningStreamsOrdinaryContent() {
        List<String> deltas = new ArrayList<>();
        AiAssistantTextDeltaProjector projector = new AiAssistantTextDeltaProjector(
                AiAssistantTextDeltaProjector.Shape.NATIVE_FINAL,
                AiReasoningMode.TAGGED,
                deltas::add);

        projector.accept("{\"text\":\"Ordinary answer\",\"citations\":[],");
        projector.accept("\"suggestions\":[],\"title\":null}");

        assertEquals("Ordinary answer", projector.finish());
        assertEquals("Ordinary answer", String.join("", deltas));
    }

    @Test
    void unterminatedTaggedReasoningNeverProjects() {
        List<String> deltas = new ArrayList<>();
        AiAssistantTextDeltaProjector projector = new AiAssistantTextDeltaProjector(
                AiAssistantTextDeltaProjector.Shape.JSON_REACT,
                AiReasoningMode.TAGGED,
                deltas::add);

        projector.accept("<thinking>private plan without a closing tag");

        assertFalse(projector.hasProjectedText());
        assertEquals(List.of(), deltas);
        assertThrows(AiAssistantLoopException.class, projector::finish);
    }
}
