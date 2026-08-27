package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

class AiChatRecordLinkRewriterTest {

    private final Map<String, AiChatResourceRegistry.ResourceRef> resources = Map.of(
            "r1", new AiChatResourceRegistry.ResourceRef("deal", 41),
            "r2", new AiChatResourceRegistry.ResourceRef("person", 7));

    @Test
    void citedHandleLinksResolveToDurableRecordTargets() {
        assertEquals(
                "See [the renewal](deal:41) today.",
                AiChatRecordLinkRewriter.rewrite(
                        "See [the renewal](record:r1) today.", resources, Set.of("r1")));
    }

    /**
     * A link can enter the text after the citation check ran — a demasked identifier value may
     * itself contain link syntax — so an uncited handle must never mint a live record reference.
     */
    @Test
    void anUncitedHandleDegradesToItsBareLabel() {
        assertEquals(
                "See the renewal today.",
                AiChatRecordLinkRewriter.rewrite(
                        "See [the renewal](record:r1) today.", resources, Set.of("r2")));
    }

    @Test
    void anUnknownHandleDegradesToItsBareLabel() {
        assertEquals(
                "Ask Mina about it.",
                AiChatRecordLinkRewriter.rewrite(
                        "Ask [Mina](record:r9) about it.", resources, Set.of("r9")));
    }

    /**
     * Replaying a persisted answer must never carry durable {@code kind:id} targets — raw record
     * ids — back into a provider prompt, and must not reintroduce handle-form links either.
     */
    @Test
    void replayStripReducesEveryRecordLinkFormToItsLabel() {
        assertEquals(
                "See the renewal and Mina and the draft.",
                AiChatRecordLinkRewriter.stripDurableLinks(
                        "See [the renewal](deal:41) and [Mina](person:7)"
                                + " and [the draft](record:r3)."));
    }

    @Test
    void ordinaryMarkdownLinksSurviveBothPasses() {
        String text = "Read [the guide](https://example.test/guide).";
        assertEquals(text, AiChatRecordLinkRewriter.rewrite(text, resources, Set.of()));
        assertEquals(text, AiChatRecordLinkRewriter.stripDurableLinks(text));
    }
}
