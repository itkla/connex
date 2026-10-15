package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class ReferenceServicePlainTextTest {

    @Test
    void toPlainText_redactsReferenceStyleNoteLinksAndDefinitions() {
        String content = "Discuss [Secret][n], [Collapsed][], and [Shortcut].\n\n"
            + "[n]: note:123 \"title\"\n"
            + "[Collapsed]: <note:124>\n"
            + "[Shortcut]: note:125";

        String plainText = ReferenceService.toPlainText(content);

        assertEquals("a note", plainText);
    }

    @Test
    void toPlainText_preservesOrdinaryReferenceStyleLinks() {
        String content = "Read [Docs][guide] or [Guide].\n\n"
            + "[guide]: https://example.com/docs\n"
            + "[Guide]: /guide";

        assertEquals(content, ReferenceService.toPlainText(content));
    }

    @Test
    void toPlainText_conservativelyRedactsAnyReferenceDefinitionWithANoteTarget() {
        String content = "Read [Docs][guide].\n\n"
            + "[guide]: https://example.com/docs\n"
            + "[guide]: note:123";

        String plainText = ReferenceService.toPlainText(content);

        assertEquals("a note", plainText);
    }

    @Test
    void toPlainText_redactsMultilineEscapedAndImageReferenceVariants() {
        assertEquals("a note", ReferenceService.toPlainText(
            "[Secret][n]\n\n[n]:\n  note:123"));
        assertEquals("a note", ReferenceService.toPlainText(
            "[Secret \\] Note][n]\n\n[n]: note:123"));
        assertEquals("a note", ReferenceService.toPlainText(
            "![Secret][n]\n\n[n]: note:123"));
    }

    @Test
    void toPlainText_redactsEscapedAndEntityEncodedNoteTargets() {
        assertEquals("a note", ReferenceService.toPlainText(
            "[Secret][n]\n\n[n]: note\\:123"));
        assertEquals("a note", ReferenceService.toPlainText(
            "[Secret][n]\n\n[n]: note&#58;123"));
        assertEquals("a note", ReferenceService.toPlainText(
            "[Secret][n]\n\n[n]: note:&#49;23"));
        assertEquals("a note", ReferenceService.toPlainText(
            "[Secret][n]\n\n[n]: note&colon;123"));
    }

    @Test
    void toPlainText_redactsLongLabelledNoteDefinitionsInLinearTime() {
        String content = "[" + "a".repeat(50_000) + "]: note:123";

        assertTimeoutPreemptively(Duration.ofSeconds(5),
            () -> assertEquals("a note", ReferenceService.toPlainText(content)));
    }

    @Test
    void toPlainText_preservesLongLabelledNonNoteDefinitionsInLinearTime() {
        String content = "[" + "a".repeat(50_000) + "]: https://example.com/docs";

        assertTimeoutPreemptively(Duration.ofSeconds(5),
            () -> assertEquals(content, ReferenceService.toPlainText(content)));
    }

    @Test
    void toPlainText_preservesUnterminatedLabelsInLinearTime() {
        String content = "[" + "a".repeat(50_000);

        assertTimeoutPreemptively(Duration.ofSeconds(5),
            () -> assertEquals(content, ReferenceService.toPlainText(content)));
    }

    @Test
    void toPlainText_scansLongIndentRunsInLinearTime() {
        String content = "[n]:" + "\t".repeat(50_000) + "x";

        assertTimeoutPreemptively(Duration.ofSeconds(5),
            () -> assertEquals(content, ReferenceService.toPlainText(content)));
    }

    @Test
    void toPlainText_redactsNoteDefinitionsWithTabbedSeparatorsAndDescriptions() {
        assertEquals("a note", ReferenceService.toPlainText("[n]:\t\tnote:123"));
        assertEquals("a note", ReferenceService.toPlainText("[n]:  \t note:123\t\ttrailing"));
        assertEquals("a note", ReferenceService.toPlainText("[n]:\n\t\t note:88 x"));
        assertEquals("a note", ReferenceService.toPlainText("\t[tab]: note:2"));
    }

    @Test
    void toPlainText_preservesNoteLikeProseOutsideMarkdownDestinations() {
        String content = "Ship release-note:123 after reviewing note:456 in plain prose";

        assertEquals(content, ReferenceService.toPlainText(content));
    }

    @Test
    void toPlainText_redactsOversizedNoteDestinationIds() {
        assertEquals("a note", ReferenceService.toPlainText(
            "[Invalid](note:999999999999999999999999999999999999)"));
    }

    @Test
    void toPlainText_redactsEmptyLabelNoteDestinations() {
        assertEquals("a note", ReferenceService.toPlainText("[](note:123)"));
    }

    @Test
    void toPlainText_redactsNoteDestinationsAfterBalancedNestedLabels() {
        assertEquals("a note", ReferenceService.toPlainText(
            "[Roadmap [private appendix]](note:123)"));
        assertEquals("a note", ReferenceService.toPlainText(
            "![Diagram [private appendix]](<note:456> \"preview\")"));
    }

    @Test
    void toPlainText_redactsNoteDestinationsAfterCodeSpanClosingBrackets() {
        assertEquals("a note", ReferenceService.toPlainText(
            "[Secret `]` appendix](note:123)"));
        assertEquals("a note", ReferenceService.toPlainText(
            "[Secret ``]` [appendix]`` details](note:456)"));
    }

    @Test
    void toPlainText_preservesCodeSpanBracketsNearNoteLikeProse() {
        String content = "Use `]` in prose before reviewing note:123";

        assertEquals(content, ReferenceService.toPlainText(content));
    }

    @Test
    void toPlainText_scansHostileUnmatchedLabelsInLinearTime() {
        String noteLink = "[Secret](note:123)";
        String content = "[".repeat(50_000 - noteLink.length()) + noteLink;

        assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
            assertEquals("a note", ReferenceService.toPlainText(content)));
    }
    @Test
    void memberCollectorCoversCommonMarkReadingsConservatively() {
        for (var reading : MemberReferenceReadings.all()) {
            org.junit.jupiter.api.Assertions.assertTrue(
                    ReferenceService.memberLabelsFromReaderVisibleContent(reading.content()).contains(reading.label()),
                    reading.rule() + ": " + reading.name());
        }
    }

    @Test
    void escapedDelimitersRemainLiteralInEveryMemberReading() {
        for (String content : java.util.List.of("\\[Alice](user:42)", "[Alice\\](user:42)",
                "[Alice]\\(user:42)", "[Alice][m]\n\n\\[m]: user:42")) {
            assertEquals(java.util.List.of(), ReferenceService.memberLabelsFromReaderVisibleContent(content), content);
        }
        for (String escape : java.util.List.of("\\[", "\\]", "\\(", "\\)", "\\`")) {
            for (String label : java.util.List.of(escape + "Alice", "Alice " + escape + " Former", "Alice" + escape)) {
                for (String reading : java.util.List.of("[" + label + "](user:42)",
                        "[" + label + "][m]\n\n[m]: user:42", "[" + label + "][]\n\n[" + label + "]: user:42",
                        "[" + label + "]\n\n[" + label + "]: user:42")) {
                    org.junit.jupiter.api.Assertions.assertTrue(
                            ReferenceService.memberLabelsFromReaderVisibleContent(reading).contains(label), reading);
                }
            }
        }
    }

    @Test
    void paragraphInterruptionMatchesCommonMarkForEveryCoveredBlockType() {
        for (var reading : MemberReferenceReadings.paragraphInterruptions()) {
            assertEquals(!reading.definitionAllowed(),
                    ReferenceService.memberLabelsFromReaderVisibleContent(reading.content()).contains("Alice Former"),
                    reading.name());
        }
    }

    @Test
    void validDefinitionsKeepTheirKeysOutOfMemberCandidates() {
        for (String remainder : java.util.List.of("user:42", "<user:42>", "<>", "<url with spaces>",
                "url(with(nested)parentheses)", "url\\(with\\)escapes", "url \"title\"", "url 'title'", "url (title)",
                "url \"escaped \\\"quote\"", "url \"a&#10;b\"", "url \"first\nsecond\"", "url\n\"title\" trailing",
                "\nuser:42", "\r<user:42>", "\r\n> user:42")) {
            String content = "[Alice Former][m]\n\n[m]: " + remainder + "\n\n[m]: user:42";
            assertEquals(java.util.List.of("Alice Former"), ReferenceService.memberLabelsFromReaderVisibleContent(content),
                    remainder);
        }
    }

    @Test
    void paragraphBoundariesAndConsecutiveDefinitionsKeepValidKeysOutOfCandidates() {
        for (String prefix : java.util.List.of("", "Hello\n\n", "Hello\n---\n", "Hello\n===\n", "# Heading\n",
                "> ", "- ", "1. ")) {
            String content = prefix + "[m]: user:42\n[n]: user:43\n\n[Public Label][m] and [Pending Label][n]";
            assertEquals(java.util.List.of("Public Label", "Pending Label"),
                    ReferenceService.memberLabelsFromReaderVisibleContent(content), prefix);
        }
        for (String prefix : java.util.List.of("1. Hello\n2. ", "1) Hello\n2) ", "> 1. Hello\n> 2. ", "001. Hello\n2. ", "1. First\n\n   Second paragraph\n2. ",
                "> 1. First\n>\n>    Second paragraph\n> 2. ", "1. First\n   # Heading\n   Second paragraph\n2. ")) {
            String content = prefix + "[m]: user:42\n\n[Public Label][m]";
            assertEquals(java.util.List.of("Public Label"), ReferenceService.memberLabelsFromReaderVisibleContent(content), prefix);
        }
        for (String definitions : java.util.List.of("[m]: user:42\n[n]: user:43", "> [m]: user:42\n> [n]: user:43",
                "- [m]: user:42\n  [n]: user:43", "[m]:\nuser:42\n[n]: user:43",
                "[m\n n]: user:42\n[k]: user:43", "[m]: user:42 \"first\nsecond\"\n[n]: user:43")) {
            assertEquals(java.util.List.of(), ReferenceService.memberLabelsFromReaderVisibleContent(definitions), definitions);
        }
    }

    @Test
    void nestedContainersFollowedByBlankLinesRemainLinear() {
        String content = "* ".repeat(25_000) + "paragraph\n" + "\n".repeat(25_000);
        assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
                assertEquals(java.util.List.of(), ReferenceService.memberLabelsFromReaderVisibleContent(content)));
    }

    @Test
    void deeplyNestedAndOversizedMemberLabelsRemainBoundedBeforeCopyingOrNormalization() {
        String nested = "[".repeat(25_000) + "]".repeat(25_000);
        String oversized = "[" + "a".repeat(50_000) + "](user:42)";
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            assertEquals(java.util.List.of(), ReferenceService.memberLabelsFromReaderVisibleContent(nested));
            assertEquals(java.util.List.of(), ReferenceService.memberLabelsFromReaderVisibleContent(oversized));
        });
    }

    @Test
    void canonicalCodeSpanLabelsStayLinearOnAdversarialBackticksAndBrackets() {
        for (String attack : java.util.List.of("[`]`".repeat(25_000), "[".repeat(50_000) + "`".repeat(50_000),
                "`[".repeat(50_000), "[".repeat(50_000) + "]".repeat(50_000),
                "\\`[\\]".repeat(20_000), "\\".repeat(100_000))) {
            String content = attack + "\n<script>\n` stray\n</script>\n[Alice `]` Former](user:42)";
            assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
                    org.junit.jupiter.api.Assertions.assertTrue(ReferenceService.memberLabelsFromReaderVisibleContent(content)
                            .contains("Alice `]` Former")));
        }
    }

    @Test
    void memberLabelLimitCountsUnicodeCharactersAcrossEveryReading() {
        for (String character : java.util.List.of("a", "😀")) {
            String accepted = character.repeat(999);
            String rejected = character.repeat(1_000);
            assertEquals(java.util.List.of(accepted),
                    ReferenceService.memberLabelsFromReaderVisibleContent("[" + accepted + "](user:42)"));
            assertEquals(java.util.List.of(),
                    ReferenceService.memberLabelsFromReaderVisibleContent("[" + rejected + "](user:42)"));
            assertEquals(java.util.List.of(accepted), ReferenceService.memberLabelsFromReaderVisibleContent(
                    "[" + accepted + "][m]\n\n[m]: user:42"));
            assertEquals(java.util.List.of(), ReferenceService.memberLabelsFromReaderVisibleContent(
                    "[Public Label][" + rejected + "]\n\n[" + rejected + "]: user:42"));
            String canonicalPrefix = "<script>\n` stray\n</script>\n[";
            String codeLabel = "`]`" + character.repeat(996);
            assertEquals(java.util.List.of(codeLabel), ReferenceService.memberLabelsFromReaderVisibleContent(
                    canonicalPrefix + codeLabel + "](user:42)"));
            assertEquals(java.util.List.of(), ReferenceService.memberLabelsFromReaderVisibleContent(
                    canonicalPrefix + codeLabel + character + "](user:42)"));
        }
    }

    @Test
    void memberRuleRunsFinishInLinearTimeWithOrWithoutATrailingLetter() {
        for (String marker : java.util.List.of("-", "=", "*", "_")) {
            for (String suffix : java.util.List.of("", "x")) {
                String line = marker.repeat(100_000) + suffix;
                String content = "` stray\n" + line + "\n[Alice `]` Former](user:42)";
                assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
                    assertEquals(suffix.isEmpty(), ReferenceService.memberRuleLine(line, 0, line.length()));
                    org.junit.jupiter.api.Assertions.assertTrue(
                            ReferenceService.memberLabelsFromReaderVisibleContent(content).contains("Alice `]` Former"));
                }, marker + suffix);
            }
        }
    }

    @Test
    void memberRuleRunsReadEachSourceCharacterAtMostOnce() {
        for (String marker : java.util.List.of("-", "=", "*", "_")) {
            for (String suffix : java.util.List.of("", "x")) {
                String line = marker.repeat(100_000) + suffix;
                var reads = new java.util.concurrent.atomic.AtomicInteger();
                CharSequence counted = new CharSequence() {
                    @Override
                    public int length() {
                        return line.length();
                    }

                    @Override
                    public char charAt(int index) {
                        org.junit.jupiter.api.Assertions.assertTrue(reads.incrementAndGet() <= line.length() + 1,
                                "Boundary detection rescanned the source");
                        return line.charAt(index);
                    }

                    @Override
                    public CharSequence subSequence(int start, int end) {
                        throw new AssertionError("Boundary detection must scan the original source");
                    }
                };
                assertEquals(suffix.isEmpty(), ReferenceService.memberRuleLine(counted, 0, counted.length()));
                assertEquals(line.length() + 1, reads.get());
            }
        }
    }

}
