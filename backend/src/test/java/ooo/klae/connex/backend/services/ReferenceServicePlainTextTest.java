package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Set;

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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("memberReferenceReadings")
    void memberReferenceReaderMatchesEveryCoveredLabelReadingAndRetainsItsId(MemberReferenceReadings.Reading reading) {
        var references = ReferenceService.memberReferencesInContent(reading.content());
        assertEquals(ReferenceService.memberLabelsFromReaderVisibleContent(reading.content()),
                references.stream().map(ReferenceService.MemberReference::label).distinct().toList(), reading.name());
        org.junit.jupiter.api.Assertions.assertTrue(
                references.stream().anyMatch(reference -> reference.label().equals(reading.label())
                        && reference.memberIds().contains(42)), reading.name());
    }

    static java.util.stream.Stream<MemberReferenceReadings.Reading> memberReferenceReadings() {
        return MemberReferenceReadings.all().stream();
    }

    @Test
    void memberReferenceReaderRetainsEachTargetForAuthoredAndDecodedLabels() {
        String content = "[Alice &amp; Bob](user:42) [Alice &amp; Bob](user&colon;43) "
                + "[Pending &lt;Name&gt;][pending] [Again](user\\:42)\n\n> [pending]:\n> user:44";
        assertEquals(java.util.List.of(
                new ReferenceService.MemberReference("Alice & Bob", Set.of(42)),
                new ReferenceService.MemberReference("Alice & Bob", Set.of(43)),
                new ReferenceService.MemberReference("Pending <Name>", Set.of(44)),
                new ReferenceService.MemberReference("Again", Set.of(42)),
                new ReferenceService.MemberReference("Alice &amp; Bob", Set.of(42)),
                new ReferenceService.MemberReference("Alice &amp; Bob", Set.of(43)),
                new ReferenceService.MemberReference("Pending &lt;Name&gt;", Set.of(44))),
                ReferenceService.memberReferencesInContent(content));
    }

    @Test
    void memberReferenceReaderRetainsAllAmbiguousUserTargetsWithoutPromotingOtherTypes() {
        String content = "[Alias][m]\n\n[m]: person:999\n[m]: user:99\n[m]: user:42\n[m]: user:43";
        assertEquals(java.util.List.of(
                new ReferenceService.MemberReference("Alias", Set.of(99, 42, 43))),
                ReferenceService.memberReferencesInContent(content));
        for (String type : java.util.List.of("person", "company", "deal", "note", "file", "task", "activity")) {
            for (String contentWithoutMembers : java.util.List.of("[Other](" + type + ":42)",
                    "[Other][m]\n\n[m]: " + type + ":42", "[Other]\n\n[Other]: " + type + ":42")) {
                assertEquals(java.util.List.of(), ReferenceService.memberReferencesInContent(contentWithoutMembers));
            }
        }
        for (String invalid : java.util.List.of("[Alias](user:0)", "[Alias](user:2147483648)", "[ ](user:42)")) {
            assertEquals(java.util.List.of(), ReferenceService.memberReferencesInContent(invalid));
        }
        assertEquals(java.util.List.of(), ReferenceService.memberReferencesInContent(null));
    }

    @Test
    void repeatedMemberLabelsDoNotMultiplyDuplicateDefinitionTargetsDuringParsing() {
        String content = "[m] ".repeat(1_400) + "\n\n" + java.util.stream.IntStream.range(100_000, 100_600)
                .mapToObj(id -> "[m]: user:" + id).collect(java.util.stream.Collectors.joining("\n"));
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            assertEquals(java.util.List.of("m"), ReferenceService.memberLabelsFromReaderVisibleContent(content));
            var references = ReferenceService.memberReferencesInContent(content);
            assertEquals(List.of(new ReferenceService.MemberReference("m", java.util.stream.IntStream
                    .range(100_000, 100_600).boxed().collect(java.util.stream.Collectors.toSet()))), references);
        });
    }

    @Test
    void distinctMemberLabelsShareAmbiguousTargetsWithoutCartesianExpansion() {
        String content = java.util.stream.IntStream.range(0, 600).mapToObj(index -> "[L" + index + "][m]")
                .collect(java.util.stream.Collectors.joining(" ")) + "\n\n"
                + java.util.stream.IntStream.range(1_000, 1_600).mapToObj(id -> "[m]: user:" + id)
                        .collect(java.util.stream.Collectors.joining("\n"));
        assertTrue(content.length() <= 16_000);
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            var references = ReferenceService.memberReferencesInContent(content);
            assertEquals(600, references.size());
            assertEquals(600, references.stream().map(ReferenceService.MemberReference::label).distinct().count());
            Set<Integer> targets = references.getFirst().memberIds();
            assertEquals(java.util.stream.IntStream.range(1_000, 1_600).boxed()
                    .collect(java.util.stream.Collectors.toSet()), targets);
            for (var reference : references) {
                assertSame(targets, reference.memberIds());
            }
            assertThrows(UnsupportedOperationException.class, () -> targets.add(42));
        });
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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("commonMarkInlineExamples")
    void commonMarkEscapesAndCodeSpansRecoverTheWholeMemberLabel(MemberReferenceReadings.InlineExample example) {
        String label = "Alice " + example.markdown() + " Former";
        org.junit.jupiter.api.Assertions.assertTrue(
                ReferenceService.memberLabelsFromReaderVisibleContent("[" + label + "](user:42)").contains(label),
                "CommonMark 0.31.2 example " + example.number());
    }

    static java.util.stream.Stream<MemberReferenceReadings.InlineExample> commonMarkInlineExamples() {
        return MemberReferenceReadings.commonMarkInlineExamples().stream();
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
                "\\`[\\]".repeat(20_000), "\\".repeat(100_000), "`\\`[".repeat(25_000))) {
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
