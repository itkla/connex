package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class ReferenceContentReaderTest {
    private static final List<String> TYPES =
            List.of("user", "person", "deal", "company", "note", "file", "task", "activity");

    static Stream<Arguments> referenceLayouts() {
        return TYPES.stream().flatMap(type -> List.of(
                "[Former](%s:42)",
                "[Former](<%s:42> \"title\")",
                "[Former][m]\n\n[m]: %s:42",
                "[Former][m]\n\n[m]:\n%s:42",
                "[Former][m]\r\r[m]:\r%s:42",
                "[Former][m]\r\n\r\n[m]:\r\n%s:42",
                "[Former][m]\n\n> [m]: <%s:42>",
                "[Former][m]\n\n> > [m]:\n> > %s:42",
                "[Former][m]\n\n- [m]: %s:42",
                "[Former][m]\n\n1. [m]:\n   %s:42",
                "[Former][m]\n\n> - [m]:\n>   %s:42",
                "[Former][m n]\n\n> [m\n> n]: %s:42",
                "[Former][]\n\n[Former]: %s:42",
                "[Former]\n\n[Former]: %s:42",
                "<%s:42>",
                "[Former][m]\n\n[m]: %s\\:42 'a&#10;b'",
                "[Former][m]\n\n[m]: %s&#58;&#52;2",
                "[Former][SS]\n\n[ß]: %s:42",
                "[Former][m n]\n\n[M\tN]: %s:42",
                "| ` | [Former][m] | ` |\n| --- | --- | --- |\n\n[m]: %s:42",
                "| First | Second |\n| --- | --- |\n| ` | [Former][m] |\n| ` | Other |\n\n[m]: %s:42",
                "See [^f].\n\n[^f]: [Former][m]\n\n[m]: %s:42",
                "[Former][m]\n\nSee [^f].\n\n[^f]:\n    [m]:\n    %s:42",
                "~~[Former][m]~~\n\n[m]: %s:42",
                "~[Former][m]~\n\n[m]: %s:42",
                "- [x] [Former][m]\n\n[m]: %s:42",
                "![Former][m]\n\n[m]: %s:42")
                .stream().map(layout -> Arguments.of(type, layout.formatted(type))));
    }

    static Stream<Arguments> tableDisagreements() {
        return TYPES.stream().flatMap(type -> Stream.of(
                "| h |\n| - |\n|\nprefix `\n[Former `]` Member](%s:42 \"title\")\nsuffix `",
                "prefix `\n\nh|i\n--|--|--\n[Former|Member](%s:42 \"title\")\n`")
                .flatMap(layout -> Stream.of("", "> ", "- ").map(prefix -> {
                    String content = layout.formatted(type);
                    if (prefix.equals("> ")) {
                        content = "> " + content.replace("\n", "\n> ");
                    } else if (prefix.equals("- ")) {
                        content = "- " + content.replace("\n", "\n  ");
                    }
                    return Arguments.of(type, content);
                })));
    }

    @ParameterizedTest
    @MethodSource("tableDisagreements")
    void tableDisagreementsNeverLoseAnInternalDestination(String type, String content) {
        var reading = ReferenceContentReader.read(content);
        var target = new ReferenceContentReader.ReferenceTarget(type, 42);

        assertTrue(reading.complete(), content);
        assertTrue(reading.targets().contains(target), content);
        assertTrue(reading.links().stream().anyMatch(link -> link.target().filter(target::equals).isPresent())
                || reading.unexplainedTargets().contains(target), content);
        if (type.equals("user") && reading.unexplainedTargets().contains(target)) {
            assertFalse(ReferenceService.memberReferenceReading(content).complete());
        }
        if (type.equals("note")) {
            assertEquals("a note", ReferenceService.toPlainText(content));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {":", "&colon;", "&#58;", "&#058;", "&#x3a;", "&#X0003A;", "\\:"})
    void censusCoversAllTypesAndAcceptedSeparatorsInLiteralAndCodeContexts(String separator) {
        for (String type : TYPES) {
            String token = type.toUpperCase(java.util.Locale.ROOT) + separator + "42";
            for (String content : List.of(token, "<" + token + ">", "`" + token + "`",
                    "```\n" + token + "\n```", "    " + token, "| " + token + " |")) {
                var reading = ReferenceContentReader.read(content);
                var target = new ReferenceContentReader.ReferenceTarget(type, 42);
                assertTrue(reading.targets().contains(target), content);
                if (reading.links().stream().noneMatch(link -> link.target().filter(target::equals).isPresent())) {
                    assertTrue(reading.unexplainedTargets().contains(target), content);
                    assertEquals(type.equals("note") ? "a note" : "(unavailable reference)",
                            ReferenceService.toPlainText(content));
                    if (type.equals("user")) {
                        assertFalse(ReferenceService.memberReferenceReading(content).complete());
                    }
                }
            }
        }
    }

    @Test
    void censusRunsBeyondTheParserCapAndRequiresSourceCoverageForEachOccurrence() {
        String content = "[Former](user:42) bare user:43";
        var reading = ReferenceContentReader.read(content);
        assertEquals(Set.of(new ReferenceContentReader.ReferenceTarget("user", 43)), reading.unexplainedTargets());
        assertFalse(ReferenceService.memberReferenceReading(content).complete());

        var unrelated = ReferenceContentReader.read(content + "\n\n[unused]: user:43");
        assertEquals(reading.unexplainedTargets(), unrelated.unexplainedTargets());
        assertFalse(ReferenceService.memberReferenceReading(content + "\n\n[unused]: user:43").complete());
        assertTrue(ReferenceContentReader.read("[unused]: user:43").unexplainedTargets().isEmpty());

        var oversized = ReferenceContentReader.read("x".repeat(ReferenceContentReader.MAX_INPUT_LENGTH + 1) + "note:42");
        assertEquals(ReferenceContentReader.Status.INPUT_LIMIT, oversized.status());
        assertTrue(oversized.targets().contains(new ReferenceContentReader.ReferenceTarget("note", 42)));
    }

    @Test
    void sameIdDefinitionsCannotExplainAMissedTableDestination() {
        for (String type : TYPES) {
            String content = "| h |\n| - |\n|\nprefix `\n[Former `]` Member](" + type
                    + ":42 \"title\")\nsuffix `\n\n[unused]: " + type + ":42";
            var reading = ReferenceContentReader.read(content);
            var target = new ReferenceContentReader.ReferenceTarget(type, 42);
            assertTrue(reading.targets().contains(target));
            assertEquals(Set.of(target), reading.unexplainedTargets());
            if (type.equals("user")) {
                assertFalse(ReferenceService.memberReferenceReading(content).complete());
            }
            if (type.equals("note")) {
                assertEquals("a note", ReferenceService.toPlainText(content));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[literal user:42](user:42)", "[Former](user:42 \"literal user:42\")",
            "[user:42]: user:42", "[Former][user:42]\n\n[user:42]: user:42"
    })
    void labelsTitlesAndKeysDoNotExplainTheirOwnLiteralTokens(String content) {
        var reading = ReferenceContentReader.read(content);
        assertTrue(reading.unexplainedTargets().contains(new ReferenceContentReader.ReferenceTarget("user", 42)));
        assertFalse(ReferenceService.memberReferenceReading(content).complete());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "**Former**", "Former `]`", "Former &amp; Member", "Former\\]", "😀 Former"})
    void sourceCoverageFollowsTheDestinationAfterTheCompleteFormattedLabel(String label) {
        for (String content : List.of("[" + label + "](user:42)", "![" + label + "](<user:42> \"title\")")) {
            var reading = ReferenceContentReader.read(content);
            assertTrue(reading.complete(), content);
            assertTrue(reading.unexplainedTargets().isEmpty(), content);
            assertTrue(ReferenceService.memberReferenceReading(content).complete(), content);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"\n", "\r", "\r\n"})
    void aBackslashAtTheEndOfADefinitionLineDoesNotEscapeTheNextLinesClosingBracket(String newline) {
        for (String prefix : List.of("", "> ")) {
            String content = "[Former][a\\" + newline + "]" + newline + newline
                    + prefix + "[a\\" + newline + prefix + "]: user:42";
            var reading = ReferenceContentReader.read(content);
            assertTrue(reading.links().stream().anyMatch(link -> link.target()
                    .filter(target -> target.equals(new ReferenceContentReader.ReferenceTarget("user", 42))).isPresent()),
                    content);
            assertTrue(reading.unexplainedTargets().isEmpty(), content);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"\n", "\r", "\r\n"})
    void aFinalSoftOrHardLineBreakDoesNotHideTheInlineDestination(String newline) {
        for (String prefix : List.of("", "> ")) {
            for (String spaces : List.of("", "  ")) {
                String content = prefix + "[Former" + spaces + newline + prefix + "](user:42)";
                var reading = ReferenceContentReader.read(content);
                assertTrue(reading.links().stream().anyMatch(link -> link.target()
                        .filter(target -> target.equals(new ReferenceContentReader.ReferenceTarget("user", 42))).isPresent()),
                        content);
                assertTrue(reading.unexplainedTargets().isEmpty(), content);
                assertTrue(ReferenceService.memberReferenceReading(content).complete(), content);
            }
        }
    }

    @Test
    void censusAlsoRecognizesEntityEncodedDestinationDigitsWhenBothParsesMissTheLink() {
        for (String type : TYPES) {
            String content = "| h |\n| - |\n|\nprefix `\n[Former `]` Member](" + type
                    + "&#x3a;&#52;&#x32; \"title\")\nsuffix `";
            var reading = ReferenceContentReader.read(content);
            assertTrue(reading.targets().contains(new ReferenceContentReader.ReferenceTarget(type, 42)));
            assertTrue(reading.unexplainedTargets().contains(new ReferenceContentReader.ReferenceTarget(type, 42)));
            if (type.equals("user")) {
                assertFalse(ReferenceService.memberReferenceReading(content).complete());
            }
        }
    }

    @Test
    void entityEncodedTypeLettersCannotHideDestinationsOrLoseTheirOriginalSourceCoverage() {
        for (String type : TYPES) {
            for (String spelling : List.of("&#" + (int) type.charAt(0) + ";" + type.substring(1),
                    type.chars().mapToObj(letter -> "&#" + letter + ";")
                            .collect(java.util.stream.Collectors.joining()),
                    type.chars().mapToObj(letter -> "&#x00" + Integer.toHexString(letter) + ";")
                            .collect(java.util.stream.Collectors.joining()))) {
                var target = new ReferenceContentReader.ReferenceTarget(type, 42);
                String hidden = "| h |\n| - |\n|\nprefix `\n[Former `]` Member](" + spelling
                        + "&#58;&#52;2 \"title\")\nsuffix `";
                var missed = ReferenceContentReader.read(hidden);
                assertTrue(missed.targets().contains(target), hidden);
                assertTrue(missed.unexplainedTargets().contains(target), hidden);
                if (type.equals("user")) {
                    assertFalse(ReferenceService.memberReferenceReading(hidden).complete());
                }
                var resolved = ReferenceContentReader.read("[Former](" + spelling + "&#58;&#52;2)");
                assertTrue(resolved.targets().contains(target), spelling);
                assertTrue(resolved.unexplainedTargets().isEmpty(), spelling);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "2147483648", "999999999999999999999999999999999999"})
    void unrepresentableCensusIdsRemainUnsafeForVisibilityMembersAndSnippets(String id) {
        for (String type : TYPES) {
            String content = "literal " + type + ":" + id;
            var reading = ReferenceContentReader.read(content);
            assertTrue(reading.unparseableTypes().contains(type), content);
            assertEquals(type.equals("note") ? "a note" : "(unavailable reference)",
                    ReferenceService.toPlainText(content), content);
            if (type.equals("user")) {
                assertFalse(ReferenceService.memberReferenceReading(content).complete());
            }
        }
    }

    @Test
    void censusRetainsItsTypeForAdversariallyPaddedNumericTypeEntities() {
        String content = TYPES.stream().map(type -> "&#" + "0".repeat(30_000)
                + (int) type.charAt(0) + ";" + type.substring(1) + ":42")
                .collect(java.util.stream.Collectors.joining(" "));
        assertTimeoutPreemptively(Duration.ofSeconds(20), () -> {
            var reading = ReferenceContentReader.read(content);
            assertTrue(content.length() < ReferenceContentReader.MAX_INPUT_LENGTH);
            assertEquals(TYPES.stream().map(type -> new ReferenceContentReader.ReferenceTarget(type, 42))
                    .collect(java.util.stream.Collectors.toSet()), reading.unexplainedTargets());
            assertFalse(ReferenceService.memberReferenceReading(content).complete());
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "😀"})
    void resolvedOversizedMemberLabelsRefuseAiWithoutChangingLegacyLabelBounds(String character) {
        for (String prefix : List.of("", "!")) {
            String bounded = prefix + "[" + character.repeat(999) + "](user:42)";
            String oversized = prefix + "[" + character.repeat(1_000) + "](user:42)";
            assertTrue(ReferenceService.memberReferenceReading(bounded).complete());
            assertTrue(ReferenceService.memberLabelsFromReaderVisibleContent(bounded).contains(character.repeat(999)));
            assertFalse(ReferenceService.memberReferenceReading(oversized).complete());
            assertTrue(ReferenceService.memberLabelsFromReaderVisibleContent(oversized).isEmpty());
            assertTrue(ReferenceContentReader.read(oversized).targets()
                    .contains(new ReferenceContentReader.ReferenceTarget("user", 42)));
        }
        assertTrue(ReferenceService.memberReferenceReading("[](user:42)").complete());
        assertTrue(ReferenceService.memberReferenceReading("![](user:42)").complete());
    }

    @Test
    void legacyDefinitionSuppressionDoesNotRemoveShortcutLabelsFromIncompleteRichReadings() {
        String content = "[Alice Former][m]\n\n[m]: \r\n> user:42\n\n[m]: user:42";
        assertEquals(List.of("Alice Former"), ReferenceService.memberLabelsFromReaderVisibleContent(content));
        var reading = ReferenceService.memberReferenceReading(content);
        assertFalse(reading.complete());
        var authored = reading.references().stream().filter(reference -> reference.label().equals("Alice Former"))
                .findFirst().orElseThrow();
        var shortcut = reading.references().stream().filter(reference -> reference.label().equals("m"))
                .findFirst().orElseThrow();
        assertSame(authored.memberIds(), shortcut.memberIds());
    }

    @ParameterizedTest
    @ValueSource(strings = {"user:7)", "user:(7"})
    void invalidDefinitionsKeepTheirConservativeLabelsAndOnlyUnexplainedIdsRefuseAi(String destination) {
        String content = "[Alice Former]: " + destination + "\n\n[Alice Former]: user:42";
        var reading = ReferenceService.memberReferenceReading(content);
        assertTrue(reading.references().contains(new ReferenceService.MemberReference("Alice Former", Set.of(42))));
        assertEquals(List.of("Alice Former"), ReferenceService.memberLabelsFromReaderVisibleContent(content));
        assertEquals(destination.equals("user:(7"), reading.complete());
    }

    @ParameterizedTest
    @MethodSource("referenceLayouts")
    void resolvesEveryInternalTypeThroughCommonMarkAndGfm(String type, String content) {
        var reading = ReferenceContentReader.read(content);

        assertEquals(ReferenceContentReader.Status.COMPLETE, reading.status(), content);
        assertTrue(reading.targets().contains(new ReferenceContentReader.ReferenceTarget(type, 42)), content);
        assertTrue(reading.links().stream().anyMatch(link -> link.target()
                .filter(target -> target.type().equals(type) && target.id() == 42).isPresent()), content);
        assertTrue(reading.unexplainedTargets().isEmpty(), content);
        if (type.equals("user") && !content.startsWith("<")) {
            assertTrue(reading.memberReferences().stream().anyMatch(reference ->
                    reference.label().equals("Former") && reference.memberIds().contains(42)), content);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "`[Former](user:42)`", "```\n[Former](user:42)\n```", "    [Former](user:42)",
            "\\[Former](user:42)", "<div>\n[Former][m]\n</div>\n\n[m]: user:42",
            "<a href=\"user:42\">Former</a>"
    })
    void codeEscapesAndHtmlDoNotBecomeResolvedLinks(String content) {
        assertTrue(ReferenceContentReader.read(content).links().isEmpty(), content);
    }

    @Test
    void htmlBlockTerminationRestoresTheCommonMarkLinkReading() {
        var reading = ReferenceContentReader.read("<div>\n\n[Former][m]\n\n[m]: user:42");
        assertEquals(1, reading.links().size());
        assertEquals("Former", reading.links().getFirst().authoredLabel());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[Former][m]\n\nSee [^a\\]b].\n\n[^a\\]b]:\n    [m]:\n    TYPE:42",
            "[Former][m]\n\n> > See [^f].\n> >\n> > [^f]:\n> >     [m]:\n> >     TYPE:42"
    })
    void unsupportedFootnoteLayoutsRetainTargetsAndFailClosed(String layout) {
        for (String type : TYPES) {
            var reading = ReferenceContentReader.read(layout.replace("TYPE", type));

            assertEquals(ReferenceContentReader.Status.UNSUPPORTED_FOOTNOTE, reading.status());
            assertTrue(reading.targets().contains(new ReferenceContentReader.ReferenceTarget(type, 42)));
            if (type.equals("user")) {
                assertTrue(reading.memberReferences().contains(new ReferenceService.MemberReference("Former", Set.of(42))));
            }
        }
    }

    @Test
    void unsupportedFootnoteInterruptionsAreRejectedWithoutDependingOnDefinitionNodes() {
        for (String type : TYPES) {
            String content = "See [^a\\]b].\n\nprefix `\n[^a\\]b]: [Secret](" + type + ":42 \"title\")\n    `";
            var reading = ReferenceContentReader.read(content);

            assertEquals(ReferenceContentReader.Status.UNSUPPORTED_FOOTNOTE, reading.status());
            assertTrue(reading.links().isEmpty());
            if (type.equals("user")) {
                assertTrue(reading.memberReferences().stream().anyMatch(reference -> reference.label().equals("Secret")
                        && reference.memberIds().contains(42)));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"> paragraph\n>\t[Former][m]\n\n[m]: user:42", "- paragraph\n\t[Former][m]\n\n[m]: user:42"})
    void containerIndentationTabsRetainOriginalLabelSpans(String content) {
        var reading = ReferenceContentReader.read(content);

        assertTrue(reading.complete());
        assertEquals("Former", reading.links().getFirst().authoredLabel());
        assertEquals(content.indexOf("Former"), reading.links().getFirst().labelSpans().getFirst().getInputIndex());
    }

    @Test
    void definitionsRetainUnusedAndNonFirstTargetsWithoutChangingParserResolution() {
        var reading = ReferenceContentReader.read("[Former][m]\n\n> [m]: person:7\n> [m]: user:42\n> [unused]: note:8");

        assertEquals("person", reading.links().getFirst().type());
        assertTrue(reading.targets().containsAll(List.of(new ReferenceContentReader.ReferenceTarget("person", 7),
                new ReferenceContentReader.ReferenceTarget("user", 42),
                new ReferenceContentReader.ReferenceTarget("note", 8))));
        assertTrue(reading.memberReferences().contains(new ReferenceService.MemberReference("Former", Set.of(42))));
    }

    @Test
    void encodedDelimitersRetainTheExistingConservativeTargetInventory() {
        for (String type : TYPES) {
            String content = "&#91;Secret&#93;(" + type + ":42)";
            var reading = ReferenceContentReader.read(content);

            assertTrue(reading.complete());
            assertTrue(reading.links().isEmpty());
            assertTrue(reading.targets().contains(new ReferenceContentReader.ReferenceTarget(type, 42)));
        }
        assertEquals("a note", ReferenceService.toPlainText("&#91;Secret&#93;(note:42)"));
    }

    @Test
    void authoredAndRenderedLabelsRetainUtf16SpansWithoutDestinationSyntax() {
        String label = "Alice `]` &amp; Bob";
        String content = "😀 prefix [" + label + "](user:42)";
        var link = ReferenceContentReader.read(content).links().getFirst();

        assertEquals(label, link.authoredLabel());
        assertEquals(label, link.logicalLabel());
        assertEquals("Alice ] & Bob", link.decodedLabel());
        assertEquals(content.indexOf(label), link.labelSpans().getFirst().getInputIndex());
        assertEquals("[" + label + "](user:42)", source(content, link.sourceSpans()));
        assertFalse(link.authoredLabel().contains("user:42"));
    }

    @Test
    void multilineLabelsRetainContainerGapsAndOriginalLineEndings() {
        String content = "> [Alice\r\n> Former][m]\r\n>\r\n> [m]: user:42";
        var link = ReferenceContentReader.read(content).links().getFirst();

        assertEquals("Alice\r\n> Former", link.authoredLabel());
        assertEquals("Alice\nFormer", link.logicalLabel());
        assertEquals("Alice\nFormer", link.decodedLabel());
        assertEquals(2, link.sourceSpans().size());
    }

    @Test
    void escapedTablePipesMapAllSourceSpansBackToOriginalText() {
        String content = "😀 \\| before | [Al\\|ice &amp; Member][m] |\n--- | ---\n\n[m]: user:42";
        var reading = ReferenceContentReader.read(content);
        var link = reading.links().getFirst();

        assertEquals(ReferenceContentReader.Status.COMPLETE, reading.status());
        assertEquals("Al\\|ice &amp; Member", link.authoredLabel());
        assertEquals("Al|ice & Member", link.decodedLabel());
        assertEquals("[Al\\|ice &amp; Member][m]", source(content, link.sourceSpans()));
        assertEquals(content.indexOf("Al\\|ice"), link.labelSpans().getFirst().getInputIndex());
        assertTrue(reading.maskingReferences().contains(
                new ReferenceService.MemberReference("Al|ice & Member", Set.of(42))));
    }

    @Test
    void sourceLabelApisRetainTheirContractWhileAiReadingsAlsoIncludeRenderedLabels() {
        String label = "*Former* `Member`";
        String content = "[" + label + "](user:42)";

        assertEquals(List.of(label), ReferenceService.memberLabelsFromReaderVisibleContent(content));
        assertEquals(List.of(new ReferenceService.MemberReference(label, Set.of(42))),
                ReferenceService.memberReferencesInContent(content));
        assertTrue(ReferenceService.memberReferenceReading(content).references().contains(
                new ReferenceService.MemberReference("Former Member", Set.of(42))));

        var ambiguous = ReferenceService.memberReferenceReading("[*Former*][m]\n\n[m]: user:99\n[m]: user:42");
        var authored = ambiguous.references().stream().filter(reference -> reference.label().equals("*Former*"))
                .findFirst().orElseThrow();
        var rendered = ambiguous.references().stream().filter(reference -> reference.label().equals("Former"))
                .findFirst().orElseThrow();
        assertEquals(Set.of(99, 42), authored.memberIds());
        assertSame(authored.memberIds(), rendered.memberIds());
    }

    @Test
    void footnoteBodySpansPointAtTheOriginalBodyRatherThanTheFootnoteMarker() {
        String content = "See [^f].\n\n[^f]: [Former **Member**][m]\n\n[m]: user:42";
        var link = ReferenceContentReader.read(content).links().getFirst();

        assertEquals("Former **Member**", link.authoredLabel());
        assertEquals("Former Member", link.decodedLabel());
        assertEquals("[Former **Member**][m]", source(content, link.sourceSpans()));
        assertEquals(content.indexOf("Former"), link.labelSpans().getFirst().getInputIndex());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "2147483648", "999999999999999999999999999999999999", "-1", "42x", ""})
    void recognizedInternalDestinationsRetainAnUnsafeSignalForUnrepresentableIds(String id) {
        for (String type : TYPES) {
            var reading = ReferenceContentReader.read("[Former](" + type + ":" + id + ")");
            assertTrue(reading.unparseableTypes().contains(type), type + ":" + id);
        }
    }

    @Test
    void emptyLabelsAndNonMemberLabelBoundsDoNotSuppressTargetDiscovery() {
        for (String label : List.of("", "a".repeat(1_000), "😀".repeat(1_000))) {
            var reading = ReferenceContentReader.read("[" + label + "](note:42)");
            assertTrue(reading.targets().contains(new ReferenceContentReader.ReferenceTarget("note", 42)));
        }
        assertFalse(ReferenceContentReader.read("[" + "😀".repeat(999) + "](user:42)").memberReferences().isEmpty());
        assertTrue(ReferenceContentReader.read("[" + "😀".repeat(1_000) + "](user:42)").memberReferences().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(ints = {262_143, 262_144, 262_145})
    void inputAdmissionUsesUtf16UnitsWithoutTruncatingCompatibilityCoverage(int length) {
        String suffix = "[Former](user:42)";
        String content = "x".repeat(length - suffix.length()) + suffix;
        var reading = ReferenceContentReader.read(content);

        assertEquals(length <= ReferenceContentReader.MAX_INPUT_LENGTH, reading.complete());
        assertTrue(reading.memberReferences().contains(new ReferenceService.MemberReference("Former", Set.of(42))));
        if (!reading.complete()) {
            assertEquals(ReferenceContentReader.Status.INPUT_LIMIT, reading.status());
            assertTrue(reading.links().isEmpty());
        }
    }

    @Test
    void supplementaryCharactersConsumeTwoInputUnitsAndLateLabelsAreStillReadOnIncompleteInput() {
        String content = "😀".repeat(ReferenceContentReader.MAX_INPUT_LENGTH / 2) + "[Late](user:42)";
        var reading = ReferenceContentReader.read(content);

        assertEquals(ReferenceContentReader.Status.INPUT_LIMIT, reading.status());
        assertTrue(reading.links().isEmpty());
        assertTrue(reading.memberReferences().contains(new ReferenceService.MemberReference("Late", Set.of(42))));
    }

    @ParameterizedTest
    @ValueSource(ints = {99, 100, 101})
    void upstreamInlineNestingSaturationIsIncomplete(int depth) {
        for (String nested : List.of("![".repeat(depth) + "x" + "](https://example.com)".repeat(depth),
                "**".repeat(depth) + "x" + "**".repeat(depth),
                "![".repeat(depth - 1) + "[^f]" + "](https://example.com)".repeat(depth - 1) + "\n\n[^f]: footnote")) {
            var reading = ReferenceContentReader.read(nested + " [Former](user:42)");
            assertEquals(depth < 100, reading.complete(), nested);
            assertEquals(depth < 100 ? ReferenceContentReader.Status.COMPLETE : ReferenceContentReader.Status.NESTING_LIMIT,
                    reading.status());
        }
    }

    @Test
    void tableCellsRestartTheUpstreamInlineNestingBudget() {
        String nested = "**".repeat(99) + "x" + "**".repeat(99);

        assertTrue(ReferenceContentReader.read("| " + nested + " |\n| --- | ").complete());
    }

    @ParameterizedTest
    @ValueSource(ints = {99, 100, 101})
    void upstreamBlockNestingSaturationIsIncomplete(int depth) {
        var reading = ReferenceContentReader.read("> ".repeat(depth - 1) + "[Former][m]\n\n[m]: user:42");

        assertEquals(depth < 100, reading.complete());
        assertEquals(depth < 100 ? ReferenceContentReader.Status.COMPLETE : ReferenceContentReader.Status.NESTING_LIMIT,
                reading.status());
    }

    @Test
    void aSaturatedParagraphCannotHideSuppressedBlockStartsAtTheQuoteBoundary() {
        String prefix = "> ".repeat(99);
        String content = prefix + "` stray\n" + prefix + "# Heading\n"
                + prefix + "[Secret `]`][n]\n\n[n]: note:42";

        assertEquals(ReferenceContentReader.Status.NESTING_LIMIT, ReferenceContentReader.read(content).status());
    }

    @ParameterizedTest
    @ValueSource(ints = {256, 257})
    void tableColumnAdmissionIsBoundedWithAndWithoutEdgePipes(int columns) {
        String header = String.join(" | ", java.util.Collections.nCopies(columns, "x"));
        String separator = String.join(" | ", java.util.Collections.nCopies(columns, "---"));
        for (String table : List.of(header + "\n" + separator, "| " + header + " |\n| " + separator + " |")) {
            assertEquals(columns <= ReferenceContentReader.MAX_TABLE_COLUMNS,
                    ReferenceContentReader.read(table).complete());
        }
    }

    @Test
    void prospectiveTableCellsAreBoundedBeforePaddedCellAllocation() {
        String content = "| " + "x | ".repeat(256) + "\n| " + "--- | ".repeat(256) + "\n" + "| x |\n".repeat(40);
        assertEquals(ReferenceContentReader.Status.TABLE_LIMIT, ReferenceContentReader.read(content).status());
    }

    @Test
    void cumulativeBracketWorkSkipsTheQuadraticParserPathWithoutDroppingLateCompatibilityLabels() {
        String suffix = " [Late](user:42)";
        String prefix = "[".repeat(131_000) + "[](u)".repeat(26_000);
        String content = prefix + "x".repeat(ReferenceContentReader.MAX_INPUT_LENGTH - prefix.length() - suffix.length()) + suffix;

        assertTimeoutPreemptively(Duration.ofSeconds(20), () -> {
            var reading = ReferenceContentReader.read(content);
            assertEquals(ReferenceContentReader.Status.BRACKET_LIMIT, reading.status());
            assertTrue(reading.links().isEmpty());
            assertTrue(reading.memberReferences().stream().anyMatch(reference -> reference.label().equals("Late")
                    && reference.memberIds().contains(42)));
        });
        assertEquals(ReferenceContentReader.Status.COMPLETE,
                ReferenceContentReader.read("[".repeat(50_000) + "[Secret](note:42)").status());
    }

    @Test
    void incompleteReadingsIncrementOnlyTheBoundedReasonMetric() {
        var registry = new SimpleMeterRegistry();
        Metrics.addRegistry(registry);
        try {
            double before = registry.counter("connex.reference.content.incomplete", "reason", "input_limit").count();
            ReferenceContentReader.read("x".repeat(ReferenceContentReader.MAX_INPUT_LENGTH + 1));
            assertEquals(before + 1, registry.counter(
                    "connex.reference.content.incomplete", "reason", "input_limit").count());
        } finally {
            Metrics.removeRegistry(registry);
            registry.close();
        }
    }

    @Test
    void adversarialBracketsBackticksHtmlAndNestingNearTheCapStayBounded() {
        String ticks = IntStream.rangeClosed(1, 500).mapToObj(length -> "`".repeat(length) + " ")
                .collect(java.util.stream.Collectors.joining());
        for (String prefix : List.of("[".repeat(200_000), "[".repeat(70_000) + "[A](user:42) ".repeat(8_000),
                ticks, "<!--".repeat(50_000), "[m]: <\n".repeat(37_000),
                "> ".repeat(101) + "**".repeat(101))) {
            String suffix = " [Former](user:42)";
            String content = prefix + "x".repeat(ReferenceContentReader.MAX_INPUT_LENGTH - prefix.length() - suffix.length())
                    + suffix;
            assertTimeoutPreemptively(Duration.ofSeconds(20), () -> {
                var reading = ReferenceContentReader.read(content);
                assertTrue(reading.memberReferences().stream().anyMatch(reference ->
                        reference.label().equals("Former") && reference.memberIds().contains(42)));
                assertEquals(ReferenceContentReader.MAX_INPUT_LENGTH, content.length());
            });
        }
    }

    private static String source(String content, List<org.commonmark.node.SourceSpan> spans) {
        return spans.stream().map(span -> content.substring(span.getInputIndex(), span.getInputIndex() + span.getLength()))
                .collect(java.util.stream.Collectors.joining("\n"));
    }
}
