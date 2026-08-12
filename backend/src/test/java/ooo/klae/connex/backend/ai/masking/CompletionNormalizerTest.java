package ooo.klae.connex.backend.ai.masking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CompletionNormalizerTest {

    @Test
    void stripsLeadingThoughtPreambleAndKeepsAnswer() {
        String output = "<thought>* Goal: write a brief\n* Grounded? yes</thought>**Who they are**\nSarif Industries.";

        assertEquals("**Who they are**\nSarif Industries.", CompletionNormalizer.stripReasoning(output));
    }

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
    void failsClosedWhenNativeAndTaggedBoundariesConflict() {
        CompletionNormalizer.CapturedCompletion captured = CompletionNormalizer.captureReasoning(
                "<thinking>tagged reasoning</thinking>{\"final\":\"answer\"}",
                "native reasoning");

        assertTrue(captured.ambiguous());
        assertEquals("", captured.reasoning());
        assertEquals("", captured.answer());
    }

    @Test
    void returnsEmptyWhenEntirelyReasoning() {
        String output = "<thought>* Input: risk factors\n* Narrative and actions live here.</thought>";

        assertEquals("", CompletionNormalizer.stripReasoning(output));
    }

    @Test
    void stripsLeadingWhitespaceBeforePreamble() {
        String output = "\n\n  <thought>reasoning</thought>Answer.";

        assertEquals("Answer.", CompletionNormalizer.stripReasoning(output));
    }

    @Test
    void stripsThinkingVariantCaseInsensitiveWithAttributes() {
        String output = "<THINKING signature=\"abc\">internal reasoning</Thinking>\nFinal answer.";

        assertEquals("Final answer.", CompletionNormalizer.stripReasoning(output));
    }

    @Test
    void stripsThinkVariantEmittedByReasoningModels() {
        String output = "<think>Let me reason about the risk factors first.</think>They are at risk because the champion left.";

        assertEquals("They are at risk because the champion left.", CompletionNormalizer.stripReasoning(output));
    }

    @Test
    void stripsConsecutiveLeadingBlocks() {
        String output = "<thought>first</thought><thinking>second</thinking>Answer.";

        assertEquals("Answer.", CompletionNormalizer.stripReasoning(output));
    }

    @Test
    void stripsNestedLeadingBlockAsWhole() {
        String output = "<thought>outer<thought>inner</thought>still reasoning</thought>Answer";

        assertEquals("Answer", CompletionNormalizer.stripReasoning(output));
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
    void discardsUnterminatedLeadingPreamble() {
        String output = "<thought>reasoning that the model never closed and ran on";

        assertEquals("", CompletionNormalizer.stripReasoning(output));
    }

    @Test
    void discardsLeadingOpeningTagTruncatedBeforeItsBracket() {
        String output = "<thought signature=\"long-token-cut-off";

        assertEquals("", CompletionNormalizer.stripReasoning(output));
    }

    @Test
    void preservesLiteralReasoningTokenInsideAnswer() {
        String output = "The client's first <thought> was to renew early, and finance already approved the budget.";

        assertEquals("The client's first <thought> was to renew early, and finance already approved the budget.",
                CompletionNormalizer.stripReasoning(output));
    }

    @Test
    void doesNotBridgeLiteralTokenToLaterBlockWhenNotLeading() {
        String output = "Note says the user typed <thought> in the support ticket. "
                + "<thinking>internal recheck of ARR</thinking> Deal is on track.";

        assertEquals(output, CompletionNormalizer.stripReasoning(output));
    }

    @Test
    void failsClosedOnUnbalancedLeadingCloseTag() {
        String output = "<thought></thought></thought>reasoning here";

        assertEquals("", CompletionNormalizer.stripReasoning(output));
    }

    @Test
    void failsClosedWhenReasoningSelfReferencesItsCloseTag() {
        String output = "<thought>I must not emit </thought> tags</thought>Answer.";

        assertEquals("", CompletionNormalizer.stripReasoning(output));
    }

    @Test
    void preservesBalancedLiteralReasoningBlockInsideAnswer() {
        String output = "<thought>reasoning</thought>Answer mentioning <thought>a literal block</thought> verbatim.";

        assertEquals("Answer mentioning <thought>a literal block</thought> verbatim.",
                CompletionNormalizer.stripReasoning(output));
    }

    @Test
    void stripsPreambleAfterNonAsciiLeadingWhitespace() {
        String output = " <thought>reasoning</thought>Answer.";

        assertEquals("Answer.", CompletionNormalizer.stripReasoning(output));
    }

    @Test
    void leavesNonReasoningOutputUntouchedApartFromTrim() {
        String output = "  Plain answer with no reasoning tags.  ";

        assertEquals("Plain answer with no reasoning tags.", CompletionNormalizer.stripReasoning(output));
    }

    @Test
    void returnsEmptyForNullAndBlank() {
        assertEquals("", CompletionNormalizer.stripReasoning(null));
        assertEquals("", CompletionNormalizer.stripReasoning("   \n  "));
    }

    @Test
    void detectsReasoningProtocolTagsAnywhereInText() {
        assertTrue(CompletionNormalizer.containsReasoningTag(
                "{\"text\":\"<thinking>private</thinking>\"}"));
        assertFalse(CompletionNormalizer.containsReasoningTag("Plain answer"));
    }
}
