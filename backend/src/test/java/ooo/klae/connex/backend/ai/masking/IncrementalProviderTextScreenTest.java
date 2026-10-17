package ooo.klae.connex.backend.ai.masking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

class IncrementalProviderTextScreenTest {
    @Test
    void fixedPointLimitAndRawShortCircuitMatchTheOracle() {
        var context = context(true);
        for (int depth : List.of(15, 16, 17, 18)) {
            String text = "[".repeat(depth) + "safe" + "](person:x)".repeat(depth);
            var screen = new IncrementalProviderTextScreen(context);
            if (depth <= 16) {
                assertEquals(context.containsUnexposedTokenInProviderText(text), screen.append(text));
            } else {
                assertThrows(MaskingLeakException.class, () -> context.containsUnexposedTokenInProviderText(text));
                assertThrows(MaskingLeakException.class, () -> screen.append(text));
                String escaped = text.replace("[", "\\u005b");
                String rawRefusal = "{{P99}} " + escaped;
                assertTrue(context.containsUnexposedTokenInProviderText(rawRefusal));
                assertTrue(new IncrementalProviderTextScreen(context).append(rawRefusal));
            }
        }
    }
    private static MaskingContext context(boolean exposed) {
        var context = new MaskingContext();
        for (EntityKind kind : EntityKind.values()) {
            for (int number = 1; number <= 12; number++) {
                context.tokenFor(kind, kind.name() + " display " + number);
            }
        }
        if (exposed) {
            context.exposeDispatchedPayload("{{P1}} {{P12}} {{C1}} {{D1}} {{E1}} {{H1}} {{M1}}");
        }
        return context;
    }

    private static List<String> corpus() {
        var forms = new ArrayList<>(List.of(
                "plain text", "{{P12}}", "{{ \t\r\n\f\u000bP12 \n }}", "{{p12}}", "{{P0}}", "{{P01}}",
                "{P12}", "{{ P 12 }}", "{{P12 } }", "{{P12\u2028}}", "{{P12\u2029}}", "{{P12\b}}",
                "{{X1}}", "{{P999999999999999999999999}}", "{{{P12}}}", "{{{{P12}}}}", "{{ {{P12}}",
                "{{P1}} then {{P12}} and {{C1}}", "{{P12}} {{P99}}", "{{\u00a0P12}}", "｛｛Ｐ１２｝｝",
                "{{\ud835\udc4312}}", "{{P12}}\u0301", "\u0600{{P12}}", "{{P\u030112}}", "{{P1\u03012}}",
                "\\{\\{P12\\}\\}", "\\u007b\\u007bP12\\u007d\\u007d",
                "\\u005cu007b\\u007bP12\\u007d\\u007d", "{{\\nP12\\t}}", "{{\\\\nP12\\\\t}}",
                "{{\\u０００aP12}}", "{{\\u005c\\u006eP12}}", "\\uD835\\uDC43", "\\u00zz{{P12}}",
                "[safe](person:{{P99}})", "[safe](person:{{P99}}", "[{{P12}}](person:1)",
                "{{[P12](person:1)}}", "{{[P](company:1)12}}", "{{P12}}[](deal:1)",
                "[prefix {{P12}} suffix](record:x)", "[[{{P12}}](person:1)](record:x)",
                "{{P[12](person:x)}}", "{{[P12](person:x)}}", "[x](unknown:{{P12}})",
                "[x]broken[{{P12}}](deal:x)", "[x](person:[{{P12}}](deal:x))",
                "{{P12}}[](person:x)[](deal:y)", "[x](person:a){{P12}}[](deal:y)",
                "{{P12}}\u200d", "{{P12}}\ufe0f", "\ud804\udcbd{{P12}}",
                "[{{P12}}](person:\\u0029)", "[safe](person:{{P99}}\\u0029",
                "[safe](person:\\u005cu007b\\u007bP99}})"));
        for (EntityKind kind : EntityKind.values()) {
            forms.add("{{" + kind.tokenPrefix() + "1}}");
        }
        return forms;
    }

    @Test
    void everySplitAndEveryPrefixAgreeWithTheCompleteOracle() {
        for (boolean exposed : List.of(false, true)) {
            var context = context(exposed);
            for (String text : corpus()) {
                for (int split = 0; split <= text.length(); split++) {
                    var screen = new IncrementalProviderTextScreen(context);
                    String prefix = text.substring(0, split);
                    assertEquals(context.containsUnexposedTokenInProviderText(prefix), screen.append(prefix),
                            () -> "prefix " + text + " at " + prefix.length() + "; exposed=" + exposed);
                    assertEquals(context.containsUnexposedTokenInProviderText(text), screen.append(text.substring(split)),
                            () -> "complete " + text + "; exposed=" + exposed);
                }
                var fragmented = new IncrementalProviderTextScreen(context);
                for (int end = 1; end <= text.length(); end++) {
                    assertEquals(context.containsUnexposedTokenInProviderText(text.substring(0, end)),
                            fragmented.append(text.substring(end - 1, end)), text + " at " + end);
                }
            }
        }
    }

    @Test
    void composedProjectionEditsAgreeWithTheOracle() {
        var random = new Random(2056);
        var pieces = List.of("{", "}", "[", "]", "(person:", "(record:", ")", "P", "1", "2", " ",
                "\\", "u007b", "u005c", "n", "\u0301", "\u0600", "｛", "｝", "\r", "\n",
                "{{P12}}", "{{P99}}", "[](person:x)", "[safe](person:", "\\u0029", "\\u007b", "\\u007d");
        var context = context(true);
        for (int sample = 0; sample < 500; sample++) {
            StringBuilder text = new StringBuilder();
            var screen = new IncrementalProviderTextScreen(context);
            for (int fragment = 0; fragment < 30; fragment++) {
                String delta = pieces.get(random.nextInt(pieces.size()));
                text.append(delta);
                assertEquals(context.containsUnexposedTokenInProviderText(text.toString()), screen.append(delta),
                        text::toString);
            }
        }
    }

    @Test
    void fiftyThousandTinyDeltasCostLinearWorkWithBoundedState() {
        for (String text : List.of("x".repeat(50_000), "{{" + " ".repeat(15_000) + "P12}}",
                "{{P" + "1".repeat(15_000) + "}}", "{{[P12](person:" + "x".repeat(50_000) + ")}}",
                "\\u005c".repeat(2_000) + "u007b\\u007bP12}}")) {
            var screen = new IncrementalProviderTextScreen(context(false));
            boolean refused = false;
            for (int offset = 0; offset < text.length(); offset++) {
                refused = screen.append(text.substring(offset, offset + 1));
            }
            assertEquals(context(false).containsUnexposedTokenInProviderText(text), refused);
            assertEquals(text.length(), screen.inputCharacters());
            if (text.equals("x".repeat(50_000))) {
                assertEquals(44L * text.length(), screen.scannedCharacters());
                assertTrue(screen.retainedSnapshots() < 200);
            }
            assertTrue(screen.scannedCharacters() < 100L * text.length(),
                    () -> "visits=" + screen.scannedCharacters() + "; input=" + text.length());
        }
    }

    @Test
    void overlongOpenCandidatesFailClosedIncludingEscapedAndUnicodeForms() {
        for (String opening : List.of("{{", "｛｛", "\\u007b\\u007b", "\\{\\{")) {
            var screen = new IncrementalProviderTextScreen(context(true));
            assertFalse(screen.append(opening));
            assertFalse(screen.append(" ".repeat(16_000)));
            assertTrue(screen.append(" ".repeat(385)));
        }
        var nestedEscape = new IncrementalProviderTextScreen(context(true));
        assertTrue(nestedEscape.append("\\u003".repeat(4_000)));
    }

    @Test
    void overlappingBraceStartsRetainOnlyTheNewestPotentialPlaceholder() {
        var context = context(true);
        for (String brace : List.of("{", "｛", "\\{", "\\u007b")) {
            String prefix = brace.repeat(17_000);
            var screen = new IncrementalProviderTextScreen(context);
            for (int index = 0; index < prefix.length(); index++) {
                assertFalse(screen.append(prefix.substring(index, index + 1)));
            }
            assertEquals(context.containsUnexposedTokenInProviderText(prefix + "P1}}"), screen.append("P1}}"));
            assertTrue(screen.retainedSnapshots() < 200);
        }
    }

    @Test
    void longPlainNestedLabelsKeepBoundedStateAndLinearWork() {
        for (int depth : List.of(1, 8, 16)) {
            var context = context(true);
            var screen = new IncrementalProviderTextScreen(context);
            String opening = "[".repeat(depth);
            assertFalse(screen.append(opening));
            for (int index = 0; index < 50_000; index++) {
                assertFalse(screen.append(index % 2 == 0 ? "x" : "y"));
            }
            assertTrue(screen.retainedSnapshots() < 20_000, "depth=" + depth + "; snapshots=" + screen.retainedSnapshots());
            String closing = "](person:x)".repeat(depth);
            assertEquals(context.containsUnexposedTokenInProviderText(opening + "xy".repeat(25_000) + closing),
                    screen.append(closing));
            assertTrue(screen.scannedCharacters() < 1_000L * screen.inputCharacters(),
                    "depth=" + depth + "; work=" + screen.scannedCharacters());
        }
    }

    @Test
    void deepLinkProjectionHasBoundedWorkPerCharacter() {
        String text = "[".repeat(16) + "x".repeat(1_000) + "](person:x)".repeat(16);
        var context = context(true);
        var screen = new IncrementalProviderTextScreen(context);
        for (int index = 0; index < text.length(); index++) {
            screen.append(text.substring(index, index + 1));
        }
        assertEquals(context.containsUnexposedTokenInProviderText(text), screen.append(""));
        assertTrue(screen.scannedCharacters() < 1_000L * text.length());
    }

    @Test
    void completedLinkRetractsItsSpeculativeRefusalAndGraphemeExtensionRevokesLiteralness() {
        var context = context(true);
        var link = new IncrementalProviderTextScreen(context);
        assertTrue(link.append("[safe](person:{{P99}}"));
        assertFalse(link.append(")"));
        var token = new IncrementalProviderTextScreen(context);
        assertFalse(token.append("{{P12}}"));
        assertTrue(token.append("\u0301"));
        assertFalse(new IncrementalProviderTextScreen(context).append("{{\r\nP12}}"));
    }
}
