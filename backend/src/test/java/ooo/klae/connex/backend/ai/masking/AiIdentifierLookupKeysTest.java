package ooo.klae.connex.backend.ai.masking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class AiIdentifierLookupKeysTest {
    static Stream<Arguments> canonicalPairs() {
        return Stream.of(
                Arguments.of("㍿ Team", "株式会社 team"),
                Arguments.of("株式会社 Team", "㍿ team"),
                Arguments.of("ＡＣＭＥ ｶﾞ", "acme ガ"),
                Arguments.of("Acme\u0007\u2003 Corp", "ACME corp"),
                Arguments.of("Ac\u200Dme\uFE0F Corp", "acme corp"),
                Arguments.of("NIKOς Team", "nikoσ team"),
                Arguments.of("ıİ Team", "ii team"),
                Arguments.of("i\u0307 Team", "I Team"),
                Arguments.of("𠀀𐐀 Team", "𠀀𐐨 team"),
                Arguments.of("O'{{}}Connor", "o'connor"));
    }

    @ParameterizedTest
    @MethodSource("canonicalPairs")
    void storedAndTurnKeysShareCanonicalizationInBothDirections(String name, String spelling) {
        for (List<String> pair : List.of(List.of(name, spelling), List.of(spelling, name))) {
            AiIdentifierLookupKeys keys = AiIdentifierLookupKeys.fromName(pair.getFirst());
            MaskingEngine.MentionScanText turn = MaskingEngine.mentionScanText("Ask " + pair.getLast() + " today.");
            assertTrue(MaskingEngine.containsIdentifierMention(turn, pair.getFirst()));
            assertTrue(turn.literalText().contains(keys.literal()));
            assertFalse(keys.literal().isEmpty());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"John \ud800{\udc00 Smith", "\ud800", "\udc00", "x\ud800y", "\ud800\ud800", "\udc00\ud800"})
    void malformedUtf16IsMarkedBeforeCanonicalizationCanJoinSurrogates(String name) {
        assertEquals(new AiIdentifierLookupKeys(AiIdentifierLookupKeys.UNKEYED, AiIdentifierLookupKeys.UNKEYED),
                AiIdentifierLookupKeys.fromName(name));
    }

    @Test
    void aliasesKeepLiteralAndLabelProjectionsSeparate() {
        AiIdentifierLookupKeys keys = AiIdentifierLookupKeys.fromName("John [O'Connor](person:999)");
        assertEquals("john [o'connor](person:999)", keys.literal());
        assertEquals("john o'connor", keys.label());
        assertTrue(MaskingEngine.mentionScanText("John O'Connor").labelText().contains(keys.label()));
    }

    @Test
    void ineligibleAndExhaustedAliasesHaveExplicitEmptyKeys() {
        for (String name : List.of("", " ", "{{}}", ".", "\u200D")) {
            assertEquals(new AiIdentifierLookupKeys("", ""), AiIdentifierLookupKeys.fromName(name));
        }
        String exhausted = "[".repeat(17) + "John O'Connor" + "](person:1)".repeat(17);
        AiIdentifierLookupKeys keys = AiIdentifierLookupKeys.fromName(exhausted);
        assertFalse(keys.literal().isEmpty());
        assertEquals("", keys.label());
    }

    @Test
    void binaryPrefilterKeepsJavaBoundaryDecisionAuthoritative() {
        AiIdentifierLookupKeys keys = AiIdentifierLookupKeys.fromName("Li");
        MaskingEngine.MentionScanText turn = MaskingEngine.mentionScanText("client");
        assertTrue(turn.literalText().contains(keys.literal()));
        assertFalse(MaskingEngine.containsIdentifierMention(turn, "Li"));
    }

    @Test
    void oversizedAliasesUseAnExplicitFallbackMarkerWithoutChangingJavaAdmission() {
        String name = "ﷺ".repeat(255);
        assertEquals(new AiIdentifierLookupKeys(AiIdentifierLookupKeys.UNKEYED, AiIdentifierLookupKeys.UNKEYED),
                AiIdentifierLookupKeys.fromName(name));
        assertTrue(MaskingEngine.containsIdentifierMention(MaskingEngine.mentionScanText(name), name));
        assertEquals("a".repeat(512), AiIdentifierLookupKeys.fromName("A".repeat(512)).literal());
        assertEquals(AiIdentifierLookupKeys.UNKEYED, AiIdentifierLookupKeys.fromName("A".repeat(513)).literal());
        assertEquals("𠀀".repeat(512), AiIdentifierLookupKeys.fromName("𠀀".repeat(512)).literal());
        assertEquals(AiIdentifierLookupKeys.UNKEYED, AiIdentifierLookupKeys.fromName("𠀀".repeat(513)).literal());
    }
}
