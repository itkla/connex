package ooo.klae.connex.backend.ai.masking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class CompletionNormalizerTest {

    @Test
    void capturesTaggedReasoningSeparatelyFromAnswer() {
        CompletionNormalizer.CapturedCompletion captured = CompletionNormalizer.captureReasoning(
                "<thinking>Compare the two relationships.</thinking>{\"final\":\"Ada is warmer.\"}",
                "");

        assertEquals("Compare the two relationships.", captured.reasoning());
        assertEquals("{\"final\":\"Ada is warmer.\"}", captured.answer());
        assertFalse(captured.ambiguous());
    }

    @Test
    void capturesNativeReasoningChannelWithoutChangingAnswer() {
        CompletionNormalizer.CapturedCompletion captured = CompletionNormalizer.captureReasoning(
                "{\"final\":\"Ada is warmer.\"}",
                "Compare the two relationships.");

        assertEquals("Compare the two relationships.", captured.reasoning());
        assertEquals("{\"final\":\"Ada is warmer.\"}", captured.answer());
        assertFalse(captured.ambiguous());
    }

    @Test
    void ambiguousNativeAndTaggedReasoningKeepsTheSeparatelyBoundedAnswer() {
        CompletionNormalizer.CapturedCompletion captured = CompletionNormalizer.captureReasoning(
                "<thinking>tagged reasoning</thinking>{\"final\":\"answer\"}",
                "native reasoning");

        assertTrue(captured.ambiguous());
        assertEquals("", captured.reasoning());
        assertEquals("{\"final\":\"answer\"}", captured.answer());
    }

    @Test
    void failsClosedOnMismatchedNestedReasoningTags() {
        CompletionNormalizer.CapturedCompletion captured = CompletionNormalizer.captureReasoning(
                "<thinking><think>plan</thinking></think>{\"ok\":true}", "");

        assertEquals("", captured.answer());
        assertEquals("", captured.reasoning());
        assertTrue(captured.ambiguous());
    }

    @Test
    void detectsReasoningProtocolTagsAnywhereInText() {
        assertTrue(CompletionNormalizer.containsReasoningTag(
                "{\"text\":\"<thinking>private</thinking>\"}"));
        assertFalse(CompletionNormalizer.containsReasoningTag("Plain answer"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("stripReasoningCases")
    void stripReasoning(String name, String input, String expected) {
        assertEquals(expected, CompletionNormalizer.stripReasoning(input));
    }

    private static Stream<Arguments> stripReasoningCases() {
        return Stream.of(
                Arguments.of("stripsLeadingThoughtPreambleAndKeepsAnswer",
                        "<thought>* Goal: write a brief\n* Grounded? yes</thought>**Who they are**\nSarif Industries.",
                        "**Who they are**\nSarif Industries."),
                Arguments.of("returnsEmptyWhenEntirelyReasoning",
                        "<thought>* Input: risk factors\n* Narrative and actions live here.</thought>",
                        ""),
                Arguments.of("stripsLeadingWhitespaceBeforePreamble",
                        "\n\n  <thought>reasoning</thought>Answer.",
                        "Answer."),
                Arguments.of("stripsThinkingVariantCaseInsensitiveWithAttributes",
                        "<THINKING signature=\"abc\">internal reasoning</Thinking>\nFinal answer.",
                        "Final answer."),
                Arguments.of("stripsThinkVariantEmittedByReasoningModels",
                        "<think>Let me reason about the risk factors first.</think>They are at risk because the champion left.",
                        "They are at risk because the champion left."),
                Arguments.of("stripsConsecutiveLeadingBlocks",
                        "<thought>first</thought><thinking>second</thinking>Answer.",
                        "Answer."),
                Arguments.of("stripsNestedLeadingBlockAsWhole",
                        "<thought>outer<thought>inner</thought>still reasoning</thought>Answer",
                        "Answer"),
                Arguments.of("discardsUnterminatedLeadingPreamble",
                        "<thought>reasoning that the model never closed and ran on",
                        ""),
                Arguments.of("discardsLeadingOpeningTagTruncatedBeforeItsBracket",
                        "<thought signature=\"long-token-cut-off",
                        ""),
                Arguments.of("preservesLiteralReasoningTokenInsideAnswer",
                        "The client's first <thought> was to renew early, and finance already approved the budget.",
                        "The client's first <thought> was to renew early, and finance already approved the budget."),
                Arguments.of("doesNotBridgeLiteralTokenToLaterBlockWhenNotLeading",
                        "Note says the user typed <thought> in the support ticket. "
                                + "<thinking>internal recheck of ARR</thinking> Deal is on track.",
                        "Note says the user typed <thought> in the support ticket. "
                                + "<thinking>internal recheck of ARR</thinking> Deal is on track."),
                Arguments.of("failsClosedOnUnbalancedLeadingCloseTag",
                        "<thought></thought></thought>reasoning here",
                        ""),
                Arguments.of("failsClosedWhenReasoningSelfReferencesItsCloseTag",
                        "<thought>I must not emit </thought> tags</thought>Answer.",
                        ""),
                Arguments.of("preservesBalancedLiteralReasoningBlockInsideAnswer",
                        "<thought>reasoning</thought>Answer mentioning <thought>a literal block</thought> verbatim.",
                        "Answer mentioning <thought>a literal block</thought> verbatim."),
                Arguments.of("stripsPreambleAfterNonAsciiLeadingWhitespace",
                        " <thought>reasoning</thought>Answer.",
                        "Answer."),
                Arguments.of("leavesNonReasoningOutputUntouchedApartFromTrim",
                        "  Plain answer with no reasoning tags.  ",
                        "Plain answer with no reasoning tags."),
                Arguments.of("returnsEmptyForNull",
                        null,
                        ""),
                Arguments.of("returnsEmptyForBlank",
                        "   \n  ",
                        ""));
    }
}
