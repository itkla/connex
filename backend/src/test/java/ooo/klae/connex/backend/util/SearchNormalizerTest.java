package ooo.klae.connex.backend.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class SearchNormalizerTest {
    @Test
    void foldsWidthKanaAndUnicodeSpacingOnBothSides() {
        assertEquals("山田太郎", SearchNormalizer.name(" 山田\u3000太郎 "));
        assertEquals("やまだたろう", SearchNormalizer.name("ﾔﾏﾀﾞ ﾀﾛｳ"));
        assertEquals("やまだたろう", SearchNormalizer.name("ヤマダ\u00a0タロウ"));
        assertEquals("やまだたろう", SearchNormalizer.name("やまだたろう"));
        assertEquals("abc123", SearchNormalizer.name("ＡＢＣ １２３"));
        assertEquals(SearchNormalizer.name("わ\u3099"), SearchNormalizer.name("ヷ"));
    }

    @Test
    void preservesIdentitySignificantPunctuationAndOrdering() {
        assertEquals("o'connor", SearchNormalizer.name("O'Connor"));
        assertEquals("leejordan", SearchNormalizer.name("Lee Jordan"));
        assertEquals("jordanlee", SearchNormalizer.name("Jordan Lee"));
        assertEquals("山田 太郎", CanonicalNameNormalizer.normalize("山田 太郎").orElseThrow());
    }

    @Test
    void findsDomesticInternationalFormattedAndPartialPhoneNumbers() {
        for (String form : new String[] {"+81-90-9684-6208", "090-9684-6208", "09096846208",
                "+81 90 9684 6208", "０９０（９６８４）６２０８", "+81(0)90-9684-6208"}) {
            assertEquals("819096846208", SearchNormalizer.phone(form), form);
        }
        assertEquals("96846208", SearchNormalizer.phone("9684 6208"));
        assertEquals("9096846208", SearchNormalizer.phone("9096846208"));
        assertEquals("12025550123", SearchNormalizer.phone("+1 (202) 555-0123"));
    }

    @Test
    void literalWildcardsCannotBecomeUnrestrictedPatterns() {
        assertEquals("%a\\%\\_\\\\b%", SearchNormalizer.namePattern(LikePattern.containing("Ａ % _ \\ Ｂ")));
        assertNull(SearchNormalizer.phonePattern(LikePattern.containing("%")));
        assertNull(SearchNormalizer.phonePattern(LikePattern.containing("call 09011112222")));
        assertNull(SearchNormalizer.namePattern(LikePattern.containing("\u3000")));
        assertEquals("", SearchNormalizer.phone(null));
    }
}
