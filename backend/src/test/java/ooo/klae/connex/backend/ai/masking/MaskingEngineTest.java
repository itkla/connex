package ooo.klae.connex.backend.ai.masking;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.text.Normalizer;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import ooo.klae.connex.backend.ai.AiPrivacyMode;

import tools.jackson.databind.ObjectMapper;

class MaskingEngineTest {
    private static final String REDACTION = Matcher.quoteReplacement(MaskingEngine.REDACTED);
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern LEGACY_EMAIL =
            Pattern.compile("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}");
    private static final Pattern LEGACY_URL =
            Pattern.compile("(?:https?://|www\\.)\\S+", Pattern.CASE_INSENSITIVE);
    private static final Pattern LEGACY_PHONE =
            Pattern.compile("(?<![\\p{L}\\p{N}])(?:[+() .-]*[0-9]){7,}(?![\\p{L}\\p{N}])");
    private static final Pattern LEGACY_DIGIT_RUN = Pattern.compile("(?<![0-9])[0-9]{9,}(?![0-9])");
    private static final Pattern ADJACENT_REDACTIONS =
            Pattern.compile("(?:" + Pattern.quote(MaskingEngine.REDACTED) + ")+");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void plainSeededNameIsMaskedBeforeAnyLinkCleanupOrTruncation() {
        String name = "vjqvkzjkqj kqkkvvzvqv";
        MaskingContext context = new MaskingContext();
        String token = MaskingEngine.maskField(EntityKind.PERSON, name, context);

        assertTrue(MaskingEngine.containsIdentifierMention(name, name));
        assertTrue(context.identifierDictionary().contains(name));
        assertEquals(token, MaskingEngine.maskFreeText(name, context));
        assertEquals("Ask " + token + " today.",
                MaskingEngine.maskConversationalFreeText("Ask " + name + " today.", context));
        assertEquals(MaskingEngine.REDACTED,
                MaskingEngine.screenFreeTextBeforeTruncation("x".repeat(498) + name, context));
    }

    /** Includes transient allocation so repeatedly rebuilding otherwise small maps also fails. */
    @Test
    void halfMebibyteMentionProjectionStaysWithinALinearAllocationBudget() {
        String note = "A routine business note. ".repeat(24_000).substring(0, 512 * 1024);
        for (int warmup = 0; warmup < 3; warmup++) {
            MaskingEngine.mentionScanText(note);
        }
        if (!(ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean allocations)) {
            throw new AssertionError("The Java 26 test runtime must expose thread allocation accounting");
        }
        assertTrue(allocations.isThreadAllocatedMemorySupported());
        allocations.setThreadAllocatedMemoryEnabled(true);
        long threadId = Thread.currentThread().threadId();
        long before = allocations.getThreadAllocatedBytes(threadId);
        MaskingEngine.MentionScanText scan = MaskingEngine.mentionScanText(note);
        long allocated = allocations.getThreadAllocatedBytes(threadId) - before;

        assertTrue(allocated <= 32L * note.length(), "Projection allocated " + allocated + " bytes");
        assertTrue(MaskingEngine.containsIdentifierMention(scan, "routine business"));
    }

    @Test
    void halfMebibyteScreeningSharesPrimitiveOffsetsAcrossAllConsumers() {
        String name = "vjqvkzjkqj kqkkvvzvqv";
        String prefix = "x".repeat(512 * 1024 - name.length());
        String note = prefix + name;
        MaskingEngine.ScreenText input = MaskingEngine.screenText(note);
        Set<int[]> maps = Collections.newSetFromMap(new IdentityHashMap<>());
        for (CanonicalText.Projection projection : List.of(input.prepared(), input.literal(), input.labels())) {
            maps.add(projection.starts());
            maps.add(projection.ends());
            assertEquals(0, projection.sourceStart(0));
            assertEquals(note.length(), projection.sourceEnd(projection.value().length()));
        }
        assertTrue(maps.stream().mapToLong(offsets -> offsets.length).sum() <= 4L * note.length());
        MaskingContext context = new MaskingContext();
        MaskingEngine.maskField(EntityKind.PERSON, name, context);
        assertEquals(MaskingEngine.REDACTED, MaskingEngine.screenFreeTextBeforeTruncation(note, context));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Johnathan [Smith]|Johnathan [Smith](person:1)",
            "Johnathan [Smith]|Johnathan [Smith](record:r9)",
            "[Johnathan] [Smith]|[Johnathan] [Smith](person:1)",
            "[Johnathan] [Smith]|[Johnathan](person:1) [Smith]",
            "Johnathan Smith]|[Johnathan Smith](person:1)"
    })
    void bracketBearingIdentifiersSurviveDestructiveLinkPreparation(String name, String spelling) {
        MaskingContext context = new MaskingContext();
        String token = MaskingEngine.maskField(EntityKind.PERSON, name, context);

        assertTrue(MaskingEngine.containsIdentifierMention(spelling, name));
        assertEquals(token, MaskingEngine.maskConversationalFreeText(spelling, context));
        assertEquals("Ask " + token, MaskingEngine.maskFreeText("Ask " + spelling, context));
        String screened = MaskingEngine.screenFreeTextBeforeTruncation("x".repeat(498) + spelling, context);
        assertEquals("[redacted]", screened);
        assertThrows(MaskingLeakException.class,
                () -> OutboundLeakScan.assertNoLeakStrict(spelling, context, objectMapper));
        assertThrows(MaskingLeakException.class,
                () -> OutboundLeakScan.assertNoLeakStrict("Johnathan Smith", context, objectMapper));
    }

    @Test
    void bracketAliasesDoNotMergeTokenIdentityOrPoisonTrustedStaticText() {
        MaskingContext context = new MaskingContext();
        assertEquals("{{P1}}", MaskingEngine.maskField(EntityKind.PERSON, "Johnathan [Smith]", context));
        assertEquals("{{P2}}", MaskingEngine.maskField(EntityKind.PERSON, "Johnathan Smith", context));
        assertEquals("{{P1}}", MaskingEngine.maskFreeText("Johnathan [Smith]", context));
        assertEquals("{{P2}}", MaskingEngine.maskFreeText("Johnathan Smith", context));
        MaskingEngine.maskField(EntityKind.COMPANY, "[what]", context);
        context.addTrustedStaticText("what is each one waiting on?");

        assertTrue(MaskingEngine.trustedStaticTextContainsIdentifier("what is each one waiting on?", "[what]"));
        assertEquals("what is each one waiting on?",
                MaskingEngine.maskConversationalFreeText("what is each one waiting on?", context));
        assertDoesNotThrow(() -> OutboundLeakScan.assertNoLeak("what is each one waiting on?", context, objectMapper));
        assertThrows(MaskingLeakException.class,
                () -> OutboundLeakScan.assertNoLeakStrict("what is each one waiting on?", context, objectMapper));
    }

    @Test
    void literalNamesOutrankAliasesAndDemaskToTheirOwnIdentityInEitherRegistrationOrder() {
        for (List<String> names : List.of(List.of("Johnathan [Smith]", "Johnathan Smith"),
                List.of("Johnathan Smith", "Johnathan [Smith]"))) {
            MaskingContext context = new MaskingContext();
            for (String name : names) {
                MaskingEngine.maskField(EntityKind.PERSON, name, context);
            }
            for (String name : names) {
                String masked = MaskingEngine.maskFreeText(name, context);
                assertEquals(context.tokenFor(EntityKind.PERSON, name), masked);
                assertEquals(name, Demasker.demask(masked, context).text());
            }
        }
    }

    @Test
    void genuinelyCollidingLiteralIdentifiersRedactInEitherRegistrationOrder() {
        for (List<String> names : List.of(List.of("Johnathan Smith", "JOHNATHAN SMITH"),
                List.of("JOHNATHAN SMITH", "Johnathan Smith"))) {
            MaskingContext context = new MaskingContext();
            names.forEach(name -> MaskingEngine.maskField(EntityKind.PERSON, name, context));
            for (String name : names) {
                assertEquals(MaskingEngine.REDACTED, MaskingEngine.maskFreeText(name, context));
            }
        }
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "John {Smith}|John Smith",
            "John \uFF33mith|John Smith",
            "JOHN SMITH|John Smith",
            "Cafe\u0301 Smith|Caf\u00e9 Smith",
            "John  Smith|John Smith",
            "' John Smith '|John Smith",
            "John \u200DSmith|John Smith"
    })
    void structuredIdentifiersRoundTripExactlyDespiteMatchingCollisionsInEitherRegistrationOrder(
            String first, String second) {
        for (List<String> names : List.of(List.of(first, second), List.of(second, first))) {
            MaskingContext context = new MaskingContext();
            String firstToken = MaskingEngine.maskField(EntityKind.PERSON, names.getFirst(), context);
            String secondToken = MaskingEngine.maskField(EntityKind.PERSON, names.getLast(), context);

            assertNotEquals(firstToken, secondToken);
            assertEquals(List.of(Map.entry(firstToken, names.getFirst()), Map.entry(secondToken, names.getLast())),
                    context.tokenBindings());
            for (String name : names) {
                String token = MaskingEngine.maskField(EntityKind.PERSON, name, context);
                assertEquals(name.equals(names.getFirst()) ? firstToken : secondToken, token);
                assertEquals(new Demasker.DemaskResult(name, 0), Demasker.demask(token, context));
                assertTrue(MaskingEngine.containsIdentifierMention(CanonicalText.canonical(first), name));
            }
            assertEquals(MaskingEngine.REDACTED, MaskingEngine.maskFreeText(CanonicalText.canonical(first), context));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"Johnathan [Smith](person:", "Johnathan [Smith](person:1"})
    void originalPartialTargetsRemainCoveredWhenTheTextCompletesTheLink(String name) {
        MaskingContext context = new MaskingContext();
        MaskingEngine.maskField(EntityKind.PERSON, name, context);
        String text = "Johnathan [Smith](person:1)";
        assertTrue(MaskingEngine.containsIdentifierMention(text, name));
        String screened = MaskingEngine.screenFreeTextBeforeTruncation("x".repeat(498) + text, context);
        assertFalse(screened.contains("John"));
        assertFalse(screened.contains("Smit"));
        assertThrows(MaskingLeakException.class,
                () -> OutboundLeakScan.assertNoLeakStrict(text, context, objectMapper));
    }

    @Test
    void replayAlsoMasksNamesCreatedByRemappingWhilePreservingIssuedTokens() {
        MaskingContext context = new MaskingContext();
        MaskingEngine.maskField(EntityKind.COMPANY, "r2 Logistics", context);
        MaskingEngine.maskField(EntityKind.PERSON, "Johnathan Smith", context);
        String prose = "Johnathan Smith says r1 Logistics needs follow-up; r1 is next.";
        assertEquals("{{C1}} needs follow-up; r2 is next.",
                MaskingEngine.maskConversationalFreeText("ｒ１ Logistics needs follow-up; ｒ１ is next.",
                        context, Map.of("r1", "r2")));
        for (String masked : List.of(
                MaskingEngine.maskConversationalFreeText(prose, context, Map.of("r1", "r2")),
                MaskingEngine.maskFreeText(prose, context, Map.of("r1", "r2")))) {
            assertEquals("{{P1}} says {{C1}} needs follow-up; r2 is next.", masked);
            OutboundLeakScan.assertNoLeakStrict(masked, context, objectMapper);
        }
    }

    @Test
    void replayReferencesDoNotRewriteSeededNamesInEitherDisclosureMode() {
        for (AiPrivacyMode mode : AiPrivacyMode.values()) {
            MaskingContext context = new MaskingContext(mode);
            context.tokenFor(EntityKind.COMPANY, "r1 Logistics");
            String name = mode == AiPrivacyMode.MASKED ? "{{C1}}" : "r1 Logistics";
            assertEquals(name + " says r2 needs follow-up; r3 is next.",
                    MaskingEngine.maskConversationalFreeText(
                            "r1 Logistics says r1 needs follow-up; r2 is next.", context,
                            Map.of("r1", "r2", "r2", "r3")));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"John [O'Connor](person:1)", "[John](record:r1) O'Connor",
            "John [[O'Connor](person:1)](record:r2)", "John \uFF3BO'Connor\uFF3D\uFF08person:1\uFF09"})
    void linkedSpellingsShareRegistrationAdmissionScreeningAndLeakScanning(String spelling) {
        for (String stored : List.of("John O'Connor", spelling)) {
            MaskingContext context = new MaskingContext();
            String token = MaskingEngine.maskField(EntityKind.PERSON, stored, context);
            assertTrue(MaskingEngine.containsIdentifierMention(spelling, stored));
            assertTrue(MaskingEngine.containsIdentifierMention("John O'Connor", stored));
            assertEquals(token, MaskingEngine.maskConversationalFreeText(spelling, context));
            assertEquals("Ask " + token, MaskingEngine.maskFreeText("Ask " + spelling, context));
            assertEquals("Ask [redacted]", MaskingEngine.screenFreeTextBeforeTruncation("Ask " + spelling, context));
            assertThrows(MaskingLeakException.class,
                    () -> OutboundLeakScan.assertNoLeakStrict(spelling, context, objectMapper));
            assertThrows(MaskingLeakException.class,
                    () -> OutboundLeakScan.assertNoLeakStrict("John O'Connor", context, objectMapper));
        }
    }

    @Test
    void linkCleanupCannotForgeIssuedTokensOrRetainPartiallyMaskedTargets() {
        MaskingContext context = new MaskingContext();
        String token = MaskingEngine.maskField(EntityKind.PERSON, "John", context);
        assertEquals("{{P1}}", token);
        for (String forged : List.of("{[](person:1){P1}[](person:1)}", "\u0000P1\u0000")) {
            String masked = MaskingEngine.maskFreeText(forged, context);
            assertFalse(masked.contains(token));
            assertFalse(Demasker.demask(masked, context).text().contains("John"));
        }
        for (String target : List.of("41", "1234567", "1234567890")) {
            assertEquals("{{P1}}", MaskingEngine.maskFreeText("[John](person:" + target + ")", context));
        }
        MaskingEngine.maskField(EntityKind.PERSON, "Smith", context);
        assertEquals("{{P1}} {{P2}}", MaskingEngine.maskFreeTextPreservingIssuedPlaceholders(
                "[{{P1}} Smith](person:41)", context));
        for (String text : List.of("[John Smith](person:41)", "[John ](person:41)")) {
            String masked = MaskingEngine.maskFreeText(text, context);
            assertFalse(masked.contains("person:"));
            assertFalse(masked.contains("41"));
            assertTrue(masked.contains(token));
        }
    }

    @Test
    void compatibilityHangulCompositionMapsAcrossOriginalGraphemeBoundaries() {
        String source = "\u3131\u314F Smith";
        MaskingContext context = new MaskingContext();
        String token = MaskingEngine.maskField(EntityKind.PERSON, "\uAC00 Smith", context);
        assertTrue(MaskingEngine.containsIdentifierMention(source, "\uAC00 Smith"));
        assertEquals(token, MaskingEngine.maskFreeText(source, context));
        assertEquals(MaskingEngine.REDACTED,
                MaskingEngine.screenFreeTextBeforeTruncation("x".repeat(498) + source, context));
    }

    @Test
    void mappedLinkCleanupCannotReconstructContactDataOrSpecialCareText() {
        MaskingContext context = new MaskingContext();
        for (String text : List.of("john[](person:1)@example.com", "https://exa[](record:r1)mple.com/private",
                "123[](person:1)4567")) {
            assertEquals(MaskingEngine.REDACTED, MaskingEngine.maskFreeText(text, context));
            assertEquals(MaskingEngine.REDACTED, MaskingEngine.screenFreeTextBeforeTruncation(text, context));
            assertEquals(MaskingEngine.REDACTED,
                    MaskingEngine.maskFreeTextPreservingIssuedPlaceholders(text, context));
        }
        assertEquals(MaskingEngine.OMITTED_BY_POLICY,
                MaskingEngine.maskFreeTextPreservingIssuedPlaceholders("diagn[](person:1)osis", context));
    }

    @Test
    void conversationPreprocessingConvergesBeforeAnyMaskingBoundary() {
        for (String spelling : List.of("John [[O'Connor](person:1)](record:r2)",
                "John [O'Con\u200Bnor](person:1)", "John [O'{{}}Connor](person:1)",
                "[John][](person:1)(record:r2) O'Connor")) {
            String prepared = ConversationText.preprocess(spelling);
            assertEquals("John O'Connor", prepared);
            assertEquals(prepared, ConversationText.preprocess(prepared));
        }
        assertEquals("Caf\u00e9 Smith", ConversationText.preprocess("[Cafe](person:1)\u0301 Smith"));
        String ordinary = "/brief @owner **quoted** \"text\" {code} [label](ordinary:target) r1";
        assertEquals(ordinary, ConversationText.preprocess(ordinary));
    }

    @Test
    void sixteenLinkLayersConvergeButSeventeenCannotReachAnyMaskingBoundary() {
        String name = "Johnathan Smith";
        MaskingContext context = new MaskingContext();
        String token = MaskingEngine.maskField(EntityKind.PERSON, name, context);
        String converged = "Johnathan " + "[".repeat(16) + "Sm" + "](person:1)".repeat(16) + "ith";
        assertEquals(name, ConversationText.preprocess(converged));
        assertTrue(MaskingEngine.containsIdentifierMention(converged, name));
        assertEquals(token, MaskingEngine.maskConversationalFreeText(converged, context));
        assertEquals(MaskingEngine.REDACTED,
                MaskingEngine.screenFreeTextBeforeTruncation("x".repeat(498) + converged, context));

        String exhausted = "Johnathan " + "[".repeat(17) + "Sm" + "](person:1)".repeat(17) + "ith";
        assertEquals(MaskingEngine.OMITTED_BY_POLICY, ConversationText.preprocess(exhausted));
        assertThrows(MaskingLeakException.class, () -> MaskingEngine.mentionScanText(exhausted));
        assertFalse(MaskingEngine.containsIdentifierMention(name, exhausted));
        assertThrows(MaskingLeakException.class,
                () -> ConversationText.linkSyntax(CanonicalText.project(exhausted)));
        for (MaskingContext dictionary : List.of(context, new MaskingContext())) {
            assertThrows(MaskingLeakException.class, () -> MaskingEngine.maskFreeText(exhausted, dictionary));
            assertThrows(MaskingLeakException.class,
                    () -> MaskingEngine.maskConversationalFreeText(exhausted, dictionary));
            assertThrows(MaskingLeakException.class,
                    () -> MaskingEngine.screenFreeTextBeforeTruncation("x".repeat(498) + exhausted, dictionary));
            assertThrows(MaskingLeakException.class,
                    () -> MaskingEngine.maskFreeTextPreservingIssuedPlaceholders(exhausted, dictionary));
            assertThrows(MaskingLeakException.class,
                    () -> OutboundLeakScan.assertNoLeakStrict(exhausted, dictionary, objectMapper));
        }
    }

    @Test
    void exhaustedStoredIdentifiersRedactStructuredFieldsAndRetainLiteralCoverage() {
        String raw = "[".repeat(17) + "John O'Connor" + "](person:1)".repeat(17);
        String literalMention = "[Visible](person:" + raw + ")";
        for (AiPrivacyMode mode : AiPrivacyMode.values()) {
            for (EntityKind kind : List.of(EntityKind.PERSON, EntityKind.COMPANY, EntityKind.DEAL)) {
                MaskingContext context = new MaskingContext(mode);

                assertEquals(MaskingEngine.REDACTED, MaskingEngine.maskField(kind, raw, context));
                assertEquals(MaskingEngine.REDACTED, MaskingEngine.maskField(kind, raw, context));
                assertEquals(Set.of(raw), context.identifierDictionary());
                MaskingContext.IdentifierEntry entry = context.identifierEntries().getFirst();
                assertEquals(CanonicalText.canonical(raw), entry.canonicalValue());
                assertEquals("", entry.labelValue());
                assertEquals(MaskingEngine.REDACTED, MaskingEngine.maskTemporal(raw, context));
                assertEquals("An unrelated turn", MaskingEngine.maskConversationalFreeText("An unrelated turn", context));
                assertEquals("Routine activity", MaskingEngine.screenFreeTextBeforeTruncation("Routine activity", context));
                assertTrue(context.isSeededIdentifierValue(raw));
                context.addTrustedStaticText("Ordinary server instructions");
                assertFalse(context.isTrustedTextCollision(raw));
                assertFalse(MaskingEngine.trustedStaticTextContainsIdentifier("Ordinary server instructions", raw));
                OutboundLeakScan.assertNoLeak("An unrelated turn", context, objectMapper);

                assertDoesNotThrow(() -> MaskingEngine.mentionScanText(literalMention));
                assertTrue(MaskingEngine.containsIdentifierMention(literalMention, raw));
                assertThrows(MaskingLeakException.class,
                        () -> OutboundLeakScan.assertNoLeak(literalMention, context, objectMapper));
                if (mode == AiPrivacyMode.MASKED) {
                    String masked = MaskingEngine.maskConversationalFreeText(literalMention, context);
                    assertFalse(masked.contains("John"));
                    assertFalse(masked.contains("Connor"));
                    OutboundLeakScan.assertNoLeakStrict(masked, context, objectMapper);
                }

                assertThrows(MaskingLeakException.class, () -> MaskingEngine.mentionScanText(raw));
                assertThrows(MaskingLeakException.class, () -> MaskingEngine.maskFreeText(raw, context));
                assertThrows(MaskingLeakException.class, () -> MaskingEngine.maskConversationalFreeText(raw, context));
                assertThrows(MaskingLeakException.class,
                        () -> MaskingEngine.screenFreeTextBeforeTruncation("x".repeat(498) + raw, context));
                assertThrows(MaskingLeakException.class,
                        () -> MaskingEngine.maskFreeTextPreservingIssuedPlaceholders(raw, context));
                assertThrows(MaskingLeakException.class,
                        () -> OutboundLeakScan.assertNoLeakStrict(raw, context, objectMapper));
            }
        }
    }

    @Test
    void unsafeStoredIdentifierDiagnosticIsBoundedAndEmittedOncePerSeededValue() {
        org.slf4j.Logger logger = LoggerFactory.getLogger(MaskingContext.class);
        if (!(logger instanceof Logger logback)) {
            throw new IllegalStateException("Logback is required to capture masking diagnostics");
        }
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logback.addAppender(appender);
        try {
            String raw = "[".repeat(17) + "John O'Connor" + "](person:1)".repeat(17);
            MaskingContext context = new MaskingContext();
            for (int repeat = 0; repeat < 3; repeat++) {
                MaskingEngine.maskField(EntityKind.PERSON, raw, context);
                MaskingEngine.maskTemporal(raw, context);
            }

            assertEquals(1, appender.list.size());
            assertEquals("AI stored identifier omitted: non-converging label projection; kind=PERSON",
                    appender.list.getFirst().getFormattedMessage());
        } finally {
            logback.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void finalCleanupRefusesAnyStructuralRewriteBeyondTheConvergedProjection() {
        Random random = new Random(1660);
        for (int sample = 0; sample < 200; sample++) {
            int depth = sample % 25;
            String target = List.of("person:1", "company:2", "deal:3", "record:r4").get(random.nextInt(4));
            String text = "Johnathan " + "[".repeat(depth) + "Sm" + ("](" + target + ")").repeat(depth) + "ith";
            if (sample % 2 == 0) {
                text = text.replace('[', '\uFF3B').replace(']', '\uFF3D');
            }
            String original = text;
            if (depth > 16) {
                assertThrows(MaskingLeakException.class, () -> CanonicalText.projectLabels(original));
            } else {
                String projected = CanonicalText.projectLabels(original).value();
                assertEquals("johnathan smith", projected);
                assertEquals(projected, ConversationText.finishMasked(projected));
                assertEquals(projected, CanonicalText.projectLabels(projected).value());
            }
        }
        for (String unsafe : List.of("Johnathan [Sm](person:1)ith", "Johnathan {{}}Smith")) {
            assertThrows(MaskingLeakException.class, () -> ConversationText.finishMasked(unsafe));
        }
        assertEquals("{{P1}}", ConversationText.finishMasked("\u0000P1\u0000"));
    }

    @Test
    void malformedOrDeeplyNestedLinksHaveBoundedPreprocessingCost() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            for (String spelling : List.of("[".repeat(50_000), "[x](person:".repeat(5_000),
                    "[".repeat(5_000) + "John O'Connor" + "](person:1)".repeat(5_000))) {
                String prepared = ConversationText.preprocess(spelling);
                assertEquals(prepared, ConversationText.preprocess(prepared));
                if (spelling.endsWith(")")) {
                    assertEquals(MaskingEngine.OMITTED_BY_POLICY, prepared);
                }
            }
        });
    }

    @Test
    void emailScreeningHandlesLongNonMatchesAndAdversarialSuffixesWithinDeadline() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            MaskingContext context = new MaskingContext();
            for (String text : List.of(
                    "A".repeat(50_000),
                    "A".repeat(50_000) + "@example.c",
                    "local@" + "A".repeat(50_000) + ".c",
                    "local@" + "a.".repeat(25_000) + "c")) {
                assertEquals(text, MaskingEngine.maskFreeText(text, context));
                assertEquals(text, MaskingEngine.screenFreeTextBeforeTruncation(text, context));
            }
            String token = MaskingEngine.maskField(EntityKind.PERSON, "Li", context);
            String split = "A".repeat(25_000) + token + "A".repeat(25_000) + "@example.c";
            assertEquals(split, MaskingEngine.maskFreeTextPreservingIssuedPlaceholders(split, context));
            assertEquals(MaskingEngine.REDACTED,
                    MaskingEngine.maskFreeTextPreservingIssuedPlaceholders(
                            "A".repeat(25_000) + token + "A".repeat(25_000) + "@example.com", context));
        });
    }

    @Test
    void linearEmailScanPreservesGreedyRegexBehaviour() {
        MaskingContext context = new MaskingContext();
        for (String text : List.of(
                "name.surname+tag%box@sub-domain.example.COM",
                "first@example.com, second@other.test.",
                "a@b.cd.ef-g; a@b.cd.e; a@b.cd_ef",
                "a@.com a@..com a@b.c a@b.co1",
                "a@b.cd@e.fg a@b@c.de @example.com",
                "a+b@c.de+f@g.hi !a@b.cd!")) {
            assertRedactsLikeLegacy(legacyRedactContactData(text),
                    MaskingEngine.maskFreeText(text, context), text);
        }
        for (String text : List.of(
                "\u00e9@example.com n\u00e4me@example.com user@ex\u00e4mple.com",
                "\u0663\u0663@example.com \u5341@example.com \ud835\udd38@example.com",
                "a\u3007b@example.com a\u1369b@example.com a\u2164b@example.com")) {
            assertRedactsLikeLegacy(legacyRedactContactData(text),
                    MaskingEngine.maskFreeText(text, context), text);
        }
        Random random = new Random(1682);
        int[] alphabet = "abCD.-_+%@ !\u00e9\u0663\u3007\u1369\u2164\u00b2\ud835\udd38\ud801\udc00"
                .codePoints().toArray();
        for (int sample = 0; sample < 2_000; sample++) {
            String text = randomText(random, alphabet, 80);
            assertRedactsLikeLegacy(legacyRedactContactData(text),
                    MaskingEngine.maskFreeText(text, context), text);
        }
    }

    @Test
    void separatorRunScreeningStaysWithinDeadlineAndStillFindsPhoneRuns() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            MaskingContext context = new MaskingContext();
            MaskingEngine.maskField(EntityKind.COMPANY, "Acme Corp", context);
            MaskingEngine.maskField(EntityKind.PERSON, "Kenji Sato", context);
            for (String filler : List.of("A", ".", " ", "-", "(", "(+ .-)")) {
                String text = "note " + filler.repeat(50_000 / filler.length()) + " end";
                assertEquals(text, MaskingEngine.maskFreeText(text, context));
                assertEquals(text, MaskingEngine.screenFreeTextBeforeTruncation(text, context));
            }
            String trailingNumber = "note " + ".".repeat(50_000) + "1234567 end";
            assertEquals("note " + MaskingEngine.REDACTED + " end",
                    MaskingEngine.maskFreeText(trailingNumber, context));
            String leadingNumber = "note 1234567" + "-".repeat(50_000) + " end";
            assertEquals("note " + MaskingEngine.REDACTED + "-".repeat(50_000) + " end",
                    MaskingEngine.maskFreeText(leadingNumber, context));
        });
    }

    @Test
    void linearPhoneScanPreservesGreedyRegexBehaviour() {
        MaskingContext context = new MaskingContext();
        for (String text : List.of(
                "call +1 (415) 555-0199 today",
                "call 1234567a and 12345678a and 1234567.",
                "ref a1234567 and a1.234567 and a 1234567",
                "1234567 8901234567890 end",
                "(((((((1234567))))))) 12.34.56.78",
                "0000000000000 and 123-456-7 and 12-34-56")) {
            assertRedactsLikeLegacy(legacyRedactContactData(text),
                    MaskingEngine.maskFreeText(text, context), text);
        }
        for (String text : List.of(
                "\u00e91234567\u00e9 and \u06631234567\u0663",
                "\u30071234567\u3007 and \u13691234567\u1369",
                "\ud801\udc001234567\ud801\udc00 and \u53411234567\u5341",
                "\ud801\udc0012345678 end and \u534112345678 end",
                "\u21641234567\u2164 and \u00b21234567\u00b2 and \ud835\udd381234567\ud835\udd38")) {
            assertRedactsLikeLegacy(legacyRedactContactData(text),
                    MaskingEngine.maskFreeText(text, context), text);
        }
        Random random = new Random(1682);
        int[] alphabet = "0123456789+() .-aZ\u00e9\u0663\u3007\u1369\u2164\u00b2\ud835\udd38\ud801\udc00"
                .codePoints().toArray();
        for (int sample = 0; sample < 2_000; sample++) {
            String candidate = randomText(random, alphabet, 60);
            assertRedactsLikeLegacy(legacyRedactContactData(candidate),
                    MaskingEngine.maskFreeText(candidate, context), candidate);
        }
    }

    @Test
    void theDictionaryExcludesOnlyDegenerateValuesTheOutboundScanAlsoIgnores() {
        MaskingContext context = new MaskingContext();
        String dotToken = MaskingEngine.maskField(EntityKind.COMPANY, ".", context);
        String dashToken = MaskingEngine.maskField(EntityKind.COMPANY, "-", context);
        String quarterToken = MaskingEngine.maskField(EntityKind.DEAL, "Q3", context);
        String forestToken = MaskingEngine.maskField(EntityKind.PERSON, "\u6797", context);
        String symbolToken = MaskingEngine.maskField(EntityKind.COMPANY, "###-###", context);
        String note = "Ready. Set. Go - now. Q3 renewal.";

        assertEquals(Set.of("Q3", "\u6797", "###-###"), context.identifierDictionary());
        assertFalse(dotToken.isBlank());
        assertFalse(dashToken.isBlank());
        assertEquals("Ready. Set. Go - now. " + quarterToken + " renewal.",
                MaskingEngine.maskFreeText(note, context));
        assertEquals(1_000,
                MaskingEngine.screenFreeTextBeforeTruncation(".".repeat(1_000), context).length());

        String maskedSurname = MaskingEngine.maskFreeText("\u6797\u3055\u3093\u306b\u9023\u7d61", context);
        assertFalse(maskedSurname.contains("\u6797"));
        assertTrue(maskedSurname.contains(forestToken));

        String maskedSymbolName = MaskingEngine.maskFreeText("the ###-### account", context);
        assertFalse(maskedSymbolName.contains("###-###"));
        assertTrue(maskedSymbolName.contains(symbolToken));
        assertDoesNotThrow(() ->
                OutboundLeakScan.assertNoLeakStrict(maskedSymbolName, context, objectMapper));
        assertThrows(MaskingLeakException.class, () -> OutboundLeakScan.assertNoLeakStrict(
                "{\"q\":\"the ###-### account\"}", context, objectMapper));
    }

    @Test
    void controlOnlyNamesRetainRawScanCoverageWithoutReplacementMatches() {
        for (String name : List.of("\u0080".repeat(4), "\u0085".repeat(4), "\u0080---")) {
            MaskingContext context = new MaskingContext();
            String token = MaskingEngine.maskField(EntityKind.PERSON, name, context);

            assertFalse(MaskingContext.isDictionaryEligible(name));
            assertTrue(context.identifierDictionary().contains(name));
            assertFalse(MaskingEngine.containsIdentifierMention("plain note", name));
            assertEquals("plain note", MaskingEngine.maskFreeText("plain note", context));
            assertEquals("plain note", MaskingEngine.screenFreeTextBeforeTruncation("plain note", context));
            assertEquals("plain " + token + " note",
                    MaskingEngine.maskFreeTextPreservingIssuedPlaceholders("plain " + token + " note", context));
            assertThrows(MaskingLeakException.class,
                    () -> OutboundLeakScan.assertNoLeakStrict("raw " + name + " value", context, objectMapper));
        }
    }

    @Test
    void distinctControlOnlyNamesDoNotMultiplyUnrelatedText() {
        MaskingContext context = new MaskingContext();
        for (int codePoint = 0x80; codePoint <= 0x85; codePoint++) {
            MaskingEngine.maskField(EntityKind.PERSON, Character.toString(codePoint).repeat(4), context);
        }

        assertEquals(6, context.identifierDictionary().size());
        assertEquals("plain note", MaskingEngine.screenFreeTextBeforeTruncation("plain note", context));
        assertEquals("plain note", MaskingEngine.maskFreeText("plain note", context));
    }

    @Test
    void controlOnlyNamesScreenFiftyThousandCharactersWithoutExpansionWithinDeadline() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            MaskingContext context = new MaskingContext();
            for (int length = 4; length <= 8; length++) {
                String name = "\u0085".repeat(length);
                MaskingEngine.maskField(EntityKind.PERSON, name, context);
                assertFalse(MaskingContext.isDictionaryEligible(name));
            }
            MaskingEngine.maskField(EntityKind.COMPANY, "###-###", context);
            String text = "x".repeat(50_000);

            assertEquals(text, MaskingEngine.screenFreeTextBeforeTruncation(text, context));
            assertEquals(text, MaskingEngine.maskFreeText(text, context));
            assertThrows(MaskingLeakException.class,
                    () -> OutboundLeakScan.assertNoLeakStrict("raw ###-### value", context, objectMapper));
        });
    }

    @Test
    void unmatchedBracesDoNotProtectIdentifiersBeforeTruncation() {
        MaskingContext context = new MaskingContext();
        MaskingEngine.maskField(EntityKind.PERSON, "Johnathan Smith", context);
        for (String text : List.of(
                "x".repeat(498) + "Johnathan Smith}",
                "x".repeat(498) + "{Johnathan Smith",
                "{" + "x".repeat(498) + "Johnathan Smith}")) {
            String screened = MaskingEngine.screenFreeTextBeforeTruncation(text, context);
            String capped = screened.substring(0, Math.min(512, screened.length()));

            assertFalse(screened.contains("Johnathan"));
            assertFalse(capped.contains("Johnathan"));
            assertDoesNotThrow(() -> OutboundLeakScan.assertNoLeakStrict(capped, context, objectMapper));
        }
    }

    @Test
    void onlyCompleteIssuedPlaceholdersSurviveIdentifierReplacement() {
        MaskingContext context = new MaskingContext();
        String person = MaskingEngine.maskField(EntityKind.PERSON, "Johnathan Smith", context);
        String tokenLikeName = MaskingEngine.maskField(EntityKind.PERSON, "P1", context);

        assertEquals(person + " and " + tokenLikeName,
                MaskingEngine.maskFreeText("Johnathan Smith and P1", context));
        assertEquals(person + " and " + tokenLikeName,
                MaskingEngine.maskFreeTextPreservingIssuedPlaceholders(person + " and P1", context));
        assertEquals("{" + tokenLikeName + "}",
                MaskingEngine.maskFreeTextPreservingIssuedPlaceholders("{P1}", context));
        assertEquals(MaskingEngine.REDACTED,
                MaskingEngine.maskFreeTextPreservingIssuedPlaceholders(
                        "Johnathan " + person + "Smith}", context));
        MaskingEngine.maskField(EntityKind.COMPANY, "P9999", context);
        assertFalse(MaskingEngine.maskFreeTextPreservingIssuedPlaceholders("{{P9999}}", context)
                .contains("P9999"));
    }

    @Test
    void canonicalLengthPrecedencePreventsShortNamesFromFragmentingLongNames() {
        MaskingContext context = new MaskingContext();
        MaskingEngine.maskField(EntityKind.PERSON, "\u0085".repeat(20) + "Smith", context);
        String person = MaskingEngine.maskField(EntityKind.PERSON, "Johnathan Smith", context);

        assertEquals(person, MaskingEngine.maskFreeText("Johnathan Smith", context));
        assertEquals(MaskingEngine.REDACTED,
                MaskingEngine.screenFreeTextBeforeTruncation("Johnathan Smith", context));
        String screened = MaskingEngine.screenFreeTextBeforeTruncation(
                "x".repeat(498) + "Johnathan Smith", context);
        String capped = screened.substring(0, Math.min(512, screened.length()));
        assertEquals(MaskingEngine.REDACTED, screened);
        assertFalse(capped.contains("Johnathan"));
        assertDoesNotThrow(() -> OutboundLeakScan.assertNoLeakStrict(capped, context, objectMapper));
    }

    @Test
    void simpleFoldedSurnameOverlapUsesCompleteSourceSpansBeforeTruncation() {
        String surname = "i".repeat(11);
        String name = "Johnathan " + "\u0130".repeat(11);
        for (List<String> names : List.of(List.of(surname, name), List.of(name, surname))) {
            MaskingContext context = new MaskingContext();
            names.forEach(value -> MaskingEngine.maskField(EntityKind.PERSON, value, context));
            String person = MaskingEngine.maskField(EntityKind.PERSON, name, context);

            assertEquals(person, MaskingEngine.maskFreeText(name, context));
            assertMergedRegionCannotReachPromptFragments("x".repeat(498) + name, context);
        }
    }

    @Test
    void partiallyOverlappingIdentifiersRedactTheirWholeUnionInEitherSeedOrder() {
        for (List<String> names : List.of(
                List.of("Johnathan Smith", "Smith International"),
                List.of("Smith International", "Johnathan Smith"))) {
            MaskingContext context = new MaskingContext();
            names.forEach(value -> MaskingEngine.maskField(EntityKind.PERSON, value, context));

            assertMergedRegionCannotReachPromptFragments("Johnathan Smith International", context);
            assertMergedRegionCannotReachPromptFragments(
                    "x".repeat(498) + "Johnathan Smith International", context);
            assertMergedRegionCannotReachPromptFragments(
                    "x".repeat(498) + "Johnathan Smith InternationalSuffix", context);
        }
    }

    @Test
    void contactDataAndIdentifierOverlapsRedactTheirWholeUnionBeforeTruncation() {
        for (List<String> example : List.of(
                List.of("Johnathan Smith", "Johnathan Smith@example.com"),
                List.of("Johnathan www", "Johnathan www.example.com"),
                List.of("Johnathan 1234567890", "Johnathan 1234567890"),
                List.of("Johnathan A1234567890B", "Johnathan A1234567890B"))) {
            MaskingContext context = new MaskingContext();
            MaskingEngine.maskField(EntityKind.PERSON, example.getFirst(), context);

            assertMergedRegionCannotReachPromptFragments(example.getLast(), context);
            assertMergedRegionCannotReachPromptFragments("x".repeat(498) + example.getLast(), context);
        }
    }

    @Test
    void adjacentSensitiveRegionsAreMergedForReplacementAndPlaceholderCrossing() {
        MaskingContext context = new MaskingContext();
        String first = MaskingEngine.maskField(EntityKind.PERSON, "\u6797", context);
        MaskingEngine.maskField(EntityKind.PERSON, "\u5c71", context);

        assertEquals(MaskingEngine.REDACTED, MaskingEngine.maskFreeText("\u6797\u5c71", context));
        assertEquals(MaskingEngine.REDACTED,
                MaskingEngine.maskFreeTextPreservingIssuedPlaceholders("\u6797" + first + "\u5c71", context));
        assertEquals(MaskingEngine.REDACTED,
                MaskingEngine.maskFreeTextPreservingIssuedPlaceholders("\u6797" + first + "\u6797", context));
    }

    @Test
    void overlappingOccurrencesOfOneIdentifierCannotLeaveATruncatedSuffix() {
        MaskingContext context = new MaskingContext();
        String token = MaskingEngine.maskField(EntityKind.PERSON, "Johnathan Johnathan", context);
        String prefix = "x".repeat(488) + " ";
        String text = prefix + "Johnathan Johnathan Johnathan";
        String screened = MaskingEngine.screenFreeTextBeforeTruncation(text, context);
        String capped = screened.substring(0, Math.min(512, screened.length()));

        assertEquals(prefix + MaskingEngine.REDACTED, screened);
        assertEquals(prefix + token, MaskingEngine.maskFreeText(text, context));
        assertFalse(capped.contains("Johnathan"));
        assertDoesNotThrow(() -> OutboundLeakScan.assertNoLeakStrict(capped, context, objectMapper));
        assertEquals(MaskingEngine.REDACTED, MaskingEngine.maskFreeTextPreservingIssuedPlaceholders(
                "Johnathan Johnathan" + token + " Johnathan", context));
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            String repeated = "Johnathan ".repeat(5_000).stripTrailing();
            assertEquals(token, MaskingEngine.maskFreeText(repeated, context));
            assertEquals(MaskingEngine.REDACTED,
                    MaskingEngine.screenFreeTextBeforeTruncation(repeated, context));
        });
    }

    private void assertMergedRegionCannotReachPromptFragments(String text, MaskingContext context) {
        String screened = MaskingEngine.screenFreeTextBeforeTruncation(text, context);
        String capped = screened.substring(0, Math.min(512, screened.length()));

        assertEquals(MaskingEngine.REDACTED, screened);
        assertFalse(capped.contains("Johnathan"));
        assertEquals(MaskingEngine.REDACTED, MaskingEngine.maskFreeText(text, context));
        assertEquals(MaskingEngine.REDACTED, MaskingEngine.maskConversationalFreeText(text, context));
        assertDoesNotThrow(() -> OutboundLeakScan.assertNoLeakStrict(capped, context, objectMapper));
    }

    @Test
    void expansionMatchingCoalescesAdjacentEntityOccurrences() {
        MaskingContext context = new MaskingContext();
        String token = MaskingEngine.maskField(EntityKind.PERSON, "\u6797\u6797", context);

        assertEquals(token, MaskingEngine.maskFreeText("\u6797\u6797\u6797", context));
        assertEquals(token, MaskingEngine.maskFreeText("\u6797\u6797\u6797\u6797", context));
        String longer = MaskingEngine.maskField(EntityKind.PERSON, "\u6797\u5c71\u5ddd\u7530", context);
        assertEquals(longer,
                MaskingEngine.maskFreeText("\u6797\u5c71\u5ddd\u7530\u6797\u5c71\u5ddd\u7530", context));
    }

    @Test
    void simpleUnicodeCaseVariantsShareRegistrationAdmissionReplacementAndOutboundScanning() {
        for (Map.Entry<String, String> variant : Map.of(
                "Ipek Smith", "\u0130pek Smith",
                "IRMA Smith", "\u0131rma smith").entrySet()) {
            for (List<String> pair : List.of(List.of(variant.getKey(), variant.getValue()),
                    List.of(variant.getValue(), variant.getKey()))) {
                String name = pair.getFirst();
                String spelling = pair.getLast();
                String text = "Ask " + spelling + " today.";
                MaskingContext context = new MaskingContext();
                String token = MaskingEngine.maskField(EntityKind.PERSON, name, context);

                MaskingContext collisionContext = new MaskingContext();
                assertNotEquals(MaskingEngine.maskField(EntityKind.PERSON, name, collisionContext),
                        MaskingEngine.maskField(EntityKind.PERSON, spelling, collisionContext));
                assertEquals("Ask " + MaskingEngine.REDACTED + " today.",
                        MaskingEngine.maskFreeText(text, collisionContext));
                assertTrue(MaskingEngine.containsIdentifierMention(text, name));
                assertFalse(MaskingEngine.containsIdentifierMention("Ask " + spelling + "suffix", name));
                assertTrue(MaskingEngine.containsIdentifierMention("Ask " + spelling + "\u0130", name));
                assertEquals("Ask " + token + " today.", MaskingEngine.maskFreeText(text, context));
                assertEquals("Ask " + token + " today.", MaskingEngine.maskConversationalFreeText(text, context));
                assertEquals("Ask " + MaskingEngine.REDACTED + " today.",
                        MaskingEngine.screenFreeTextBeforeTruncation(text, context));
                assertThrows(MaskingLeakException.class,
                        () -> OutboundLeakScan.assertNoLeakStrict(text, context, objectMapper));
                assertDoesNotThrow(() -> OutboundLeakScan.assertNoLeakStrict(
                        MaskingEngine.maskFreeText(text, context), context, objectMapper));
                String repeated = "\u0130 \ud801\udc00 " + spelling + " / " + spelling + "!";
                assertEquals("\u0130 \ud801\udc00 " + token + " / " + token + "!",
                        MaskingEngine.maskFreeText(repeated, context));
                assertEquals(MaskingEngine.REDACTED,
                        MaskingEngine.maskFreeTextPreservingIssuedPlaceholders(
                                spelling.substring(0, 4) + token + spelling.substring(4), context));
            }
        }
        assertEquals("i i i i \u03c3 \u03c3 \u03c3 \u00df k",
                CanonicalText.canonical("I i \u0130 \u0131 \u03a3 \u03c2 \u03c3 \u00df \u212a"));
    }

    @Test
    void simpleCaseFoldRetainsCompleteSurrogatePairSourceOffsets() {
        MaskingContext context = new MaskingContext();
        String token = MaskingEngine.maskField(EntityKind.PERSON, "\ud801\udc00 Smith", context);
        String text = "\u0130 \ud83d\ude00 Ask \ud801\udc28 smith / \ud801\udc00 Smith!";

        assertTrue(MaskingEngine.containsIdentifierMention(text, "\ud801\udc00 Smith"));
        assertEquals("\u0130 \ud83d\ude00 Ask " + token + " / " + token + "!",
                MaskingEngine.maskFreeText(text, context));
        assertEquals("\u0130 \ud83d\ude00 Ask " + MaskingEngine.REDACTED + " / " + MaskingEngine.REDACTED + "!",
                MaskingEngine.screenFreeTextBeforeTruncation(text, context));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "\u0130pek Smith|i\u0307pek Smith",
            "i\u0307pek Smith|\u0130pek Smith",
            "Ipek Smith|i\u0307pek Smith",
            "i\u0307pek Smith|Ipek Smith"
    })
    void decomposedDottedIMentionsShareRegistrationAdmissionReplacementAndOutboundScanning(
            String name, String spelling) {
        MaskingContext context = new MaskingContext();
        String token = MaskingEngine.maskField(EntityKind.PERSON, name, context);
        String text = "Ask " + spelling + " today.";

        assertEquals("ipek smith", CanonicalText.canonical(name));
        assertEquals("ipek smith", CanonicalText.canonical(spelling));
        assertTrue(MaskingEngine.containsIdentifierMention(text, name));
        assertFalse(MaskingEngine.containsIdentifierMention("Ask " + spelling + "suffix", name));
        assertEquals("Ask " + token + " today.", MaskingEngine.maskFreeText(text, context));
        assertEquals("Ask " + token + " today.", MaskingEngine.maskConversationalFreeText(text, context));
        assertEquals("Ask " + MaskingEngine.REDACTED + " today.",
                MaskingEngine.screenFreeTextBeforeTruncation(text, context));
        assertThrows(MaskingLeakException.class,
                () -> OutboundLeakScan.assertNoLeakStrict(text, context, objectMapper));
        assertDoesNotThrow(() -> OutboundLeakScan.assertNoLeakStrict(
                MaskingEngine.maskFreeText(text, context), context, objectMapper));
        String note = "x".repeat(513 - spelling.length()) + spelling;
        String screened = MaskingEngine.screenFreeTextBeforeTruncation(note, context);
        assertEquals(MaskingEngine.REDACTED, screened);
        assertDoesNotThrow(() -> OutboundLeakScan.assertNoLeakStrict(
                screened.substring(0, Math.min(512, screened.length())), context, objectMapper));
        String spellingToken = MaskingEngine.maskField(EntityKind.PERSON, spelling, context);
        assertNotEquals(token, spellingToken);
        assertEquals(name, Demasker.demask(token, context).text());
        assertEquals(spelling, Demasker.demask(spellingToken, context).text());
    }

    @Test
    void deletedDottedIMarksRemainInsideSourceSpansWithoutShiftingLaterSurrogates() {
        MaskingContext context = new MaskingContext();
        String token = MaskingEngine.maskField(EntityKind.PERSON, "Smith I", context);
        String supplementaryToken = MaskingEngine.maskField(EntityKind.PERSON, "\ud801\udc00 Jones", context);
        String text = "\ud83d\ude00 i\u0307 / Smith i\u0307\u0307 / \ud801\udc28 Jones!";
        String prepared = CanonicalText.prepare(text);
        CanonicalText.Projection projection = CanonicalText.project(prepared);
        int start = projection.value().indexOf("smith i");

        assertEquals("Smith i\u0307\u0307", prepared.substring(
                projection.sourceStart(start), projection.sourceEnd(start + "smith i".length())));
        assertTrue(MaskingEngine.containsIdentifierMention(text, "Smith I"));
        assertFalse(MaskingEngine.containsIdentifierMention("Smith i\u0307suffix", "Smith I"));
        assertEquals("\ud83d\ude00 i\u0307 / " + token + " / " + supplementaryToken + "!",
                MaskingEngine.maskFreeText(text, context));
        assertEquals("\ud83d\ude00 i\u0307 / " + MaskingEngine.REDACTED + " / " + MaskingEngine.REDACTED + "!",
                MaskingEngine.screenFreeTextBeforeTruncation(text, context));
        assertEquals("\u0307 j\u0307 i", CanonicalText.canonical("\u0307 j\u0307 i\u0307"));
    }

    @Test
    void simpleUnicodeCaseVariantsCannotSurviveAFiveHundredTwelveCharacterFieldCap() {
        for (Map.Entry<String, String> variant : Map.of(
                "Ipek Smith", "\u0130pek Smith", "IRMA Smith", "\u0131rma smith").entrySet()) {
            MaskingContext context = new MaskingContext();
            MaskingEngine.maskField(EntityKind.PERSON, variant.getKey(), context);
            String text = "x".repeat(503) + variant.getValue();
            String screened = MaskingEngine.screenFreeTextBeforeTruncation(text, context);

            assertEquals(MaskingEngine.REDACTED, screened);
            assertEquals(MaskingEngine.REDACTED, MaskingEngine.maskFreeText(text, context));
            assertDoesNotThrow(() -> OutboundLeakScan.assertNoLeakStrict(
                    screened.substring(0, Math.min(512, screened.length())), context, objectMapper));
            String embedded = "prefix" + variant.getValue() + "suffix / prefix" + variant.getKey() + "suffix";
            assertEquals(MaskingEngine.REDACTED + " / " + MaskingEngine.REDACTED,
                    MaskingEngine.screenFreeTextBeforeTruncation(embedded, context));
            assertEquals(MaskingEngine.REDACTED + " / " + MaskingEngine.REDACTED,
                    MaskingEngine.maskFreeText(embedded, context));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"\u03a3", "\u0130"})
    void longUnicodeNoteScreeningStaysWithinThePerRequestDeadline(String filler) {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            MaskingContext context = new MaskingContext();
            MaskingEngine.maskField(EntityKind.PERSON, "Ipek Smith", context);
            String note = filler.repeat(50_000);

            assertEquals(note, MaskingEngine.screenFreeTextBeforeTruncation(note, context));
            String screened = MaskingEngine.screenFreeTextBeforeTruncation(note + " Ask \u0130pek Smith", context);
            assertEquals(note + " Ask " + MaskingEngine.REDACTED, screened);
            assertDoesNotThrow(() -> OutboundLeakScan.assertNoLeakStrict(screened, context, objectMapper));
        });
    }

    @Test
    void identifierResidualScreeningStaysWithinDeadlineOnLongUncontrolledText() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            MaskingContext context = new MaskingContext();
            String acme = MaskingEngine.maskField(EntityKind.COMPANY, "Acme Corp", context);
            MaskingEngine.maskField(EntityKind.COMPANY, "Northwind", context);
            MaskingEngine.maskField(EntityKind.PERSON, "Kenji Sato", context);
            MaskingEngine.maskField(EntityKind.COMPANY, "AAAA Corp", context);
            for (String filler : List.of("A", "Acme", "Acme ", "_", "Kenji ")) {
                String note = "Note " + filler.repeat(50_000 / filler.length()) + " end";
                String masked = MaskingEngine.maskFreeText(note, context);
                assertFalse(containsIgnoreCase(masked, "Northwind"));
                assertDoesNotThrow(() ->
                        OutboundLeakScan.assertNoLeakStrict(masked, context, objectMapper));
                assertDoesNotThrow(() -> OutboundLeakScan.assertNoLeakStrict(
                        MaskingEngine.screenFreeTextBeforeTruncation(note, context),
                        context, objectMapper));
            }
            String mention = "Note " + "A".repeat(50_000) + " about Acme Corp end";
            assertTrue(MaskingEngine.maskFreeText(mention, context).contains(acme));
            String embedded = "Note " + "A".repeat(25_000) + "NorthwindX" + "A".repeat(25_000) + " end";
            String maskedEmbedded = MaskingEngine.maskFreeText(embedded, context);
            assertEquals("Note " + MaskingEngine.REDACTED + " end", maskedEmbedded);
            assertDoesNotThrow(() ->
                    OutboundLeakScan.assertNoLeakStrict(maskedEmbedded, context, objectMapper));
        });
    }

    /**
     * The greedy oracle works on literal text, so the exact comparison uses a brace-free alphabet;
     * brace-bearing text is asserted against the invariant the braces were added for, namely that
     * no arrangement of them can leave the identifier's canonical form in the screened output.
     */
    @Test
    void linearIdentifierResidualScanMatchesGreedyOracleWithoutBraceProtection() {
        int[] textPoints = "aAbB_ .-\u00e9\u5341\u00df\u212a".codePoints().toArray();
        int[] bracedPoints = "aAbB_ .-{}\u00e9\u5341\u00df\u212a".codePoints().toArray();
        int[] valuePoints = "aAbB_ .-\u00e9\u5341\u00df\u212a".codePoints().toArray();
        Random random = new Random(1658);
        int compared = 0;
        for (int sample = 0; sample < 4_000; sample++) {
            String value = WHITESPACE.matcher(
                    randomText(random, valuePoints, 4 + random.nextInt(5)).trim()).replaceAll(" ");
            String scanNormalized = nfkc(value).toLowerCase(Locale.ROOT);
            if (scanNormalized.length() < 4
                    || scanNormalized.codePoints().noneMatch(Character::isLetterOrDigit)
                    || MaskingEngine.REDACTED.contains(scanNormalized)
                    || MaskingEngine.OMITTED_BY_POLICY.contains(scanNormalized)) {
                continue;
            }
            String text = randomText(random, textPoints, 1 + random.nextInt(40));
            String braced = randomText(random, bracedPoints, 1 + random.nextInt(40));
            if (text.isBlank()) {
                continue;
            }
            MaskingContext context = new MaskingContext();
            MaskingEngine.maskField(EntityKind.COMPANY, value, context);

            assertEquals(greedyScreenWithResidual(text, value),
                    MaskingEngine.screenFreeTextBeforeTruncation(text, context),
                    "text=" + text + " value=" + value);
            assertFalse(MaskingEngine.normalizeIdentifierValue(
                            MaskingEngine.screenFreeTextBeforeTruncation(braced, context))
                    .contains(MaskingEngine.normalizeIdentifierValue(value)),
                    "braced=" + braced + " value=" + value);
            compared++;
        }
        assertTrue(compared > 2_000, "compared=" + compared);
    }

    @Test
    void anIdentifierCarryingAControlCharacterFoldsLikeTheTextItMustMatch() {
        MaskingContext context = new MaskingContext();
        String name = "John\u0007O'Connor";
        String turn = "Ask John O'Connor about it";
        String token = MaskingEngine.maskField(EntityKind.PERSON, name, context);
        String masked = MaskingEngine.maskFreeText(turn, context);

        assertTrue(MaskingEngine.containsIdentifierMention(turn, name));
        assertEquals("Ask " + token + " about it", masked);
        assertEquals("Ask " + MaskingEngine.REDACTED + " about it",
                MaskingEngine.screenFreeTextBeforeTruncation(turn, context));
    }

    @Test
    void oneMentionScanTextGatesEveryCandidateExactlyAsTheSingleTurnGateWould() {
        String turn = "Ask \u0007John O'Connor\u2028and  \u5341\u4e00 about Acme, Inc.";
        MaskingEngine.MentionScanText scanText = MaskingEngine.mentionScanText(turn);

        for (String candidate : List.of(
                "John O'Connor", "john o'connor", "Acme, Inc.", "\u5341\u4e00", "onnor", "Nope", ".")) {
            assertEquals(MaskingEngine.containsIdentifierMention(turn, candidate),
                    MaskingEngine.containsIdentifierMention(scanText, candidate), candidate);
        }
        assertTrue(MaskingEngine.containsIdentifierMention(scanText, "John O'Connor"));
        assertTrue(MaskingEngine.containsIdentifierMention(scanText, "\u5341\u4e00"));
        assertFalse(MaskingEngine.containsIdentifierMention(scanText, "onnor"));
        assertFalse(MaskingEngine.containsIdentifierMention(scanText, "."));
        assertEquals(scanText.normalizedText().length(), scanText.foldedText().length());
    }

    @Test
    void aNameTouchingAnAccentedLatinLetterIsAdmittedAndRedacted() {
        MaskingContext context = new MaskingContext();
        String text = "Ask Smith\u00e9 about it";
        MaskingEngine.maskField(EntityKind.PERSON, "Smith", context);
        String masked = MaskingEngine.maskFreeText(text, context);

        assertTrue(MaskingEngine.containsIdentifierMention(text, "Smith"));
        assertFalse(masked.contains("Smith"));
        assertTrue(masked.contains(MaskingEngine.REDACTED));
        assertDoesNotThrow(() -> OutboundLeakScan.assertNoLeakStrict(masked, context, objectMapper));
    }

    @Test
    void punctuationIdentifiersRetainReplacerAndLeakScannerParity() {
        for (String name : List.of("John O'Connor", "Anne-Marie Smith", "Acme, Inc.", "J. R. Smith")) {
            MaskingContext context = new MaskingContext();
            String token = MaskingEngine.maskField(EntityKind.PERSON, name, context);
            assertEquals("Ask " + token + "?", MaskingEngine.maskFreeText("Ask " + name + "?", context));
            for (String text : List.of("Ask " + name + "?", "prefix" + name + "suffix")) {
                String masked = MaskingEngine.maskFreeText(text, context);
                assertDoesNotThrow(() -> OutboundLeakScan.assertNoLeakStrict(masked, context, objectMapper));
                String screened = MaskingEngine.screenFreeTextBeforeTruncation(text, context);
                assertDoesNotThrow(() -> OutboundLeakScan.assertNoLeakStrict(screened, context, objectMapper));
            }
        }
    }

    @Test
    void masksStructuredFieldsAndFreeTextWithoutOutboundLeaks() throws Exception {
        MaskingContext ctx = new MaskingContext();
        String ann = MaskingEngine.maskField(EntityKind.PERSON, "Ann", ctx);
        String annSmith = MaskingEngine.maskField(EntityKind.PERSON, "Ann Smith", ctx);
        String company = MaskingEngine.maskField(EntityKind.COMPANY, "Acme (Japan) [R&D]", ctx);
        String japaneseName = MaskingEngine.maskField(EntityKind.PERSON, "山田太郎", ctx);
        String email = MaskingEngine.maskField(EntityKind.EMAIL, "ann.smith+vip@example.com", ctx);
        String phone = MaskingEngine.maskField(EntityKind.PHONE, "+81-3-1234-5678", ctx);

        assertEquals("{{P1}}", ann);
        assertEquals("{{P2}}", annSmith);
        assertEquals("{{C1}}", company);
        assertEquals("{{P3}}", japaneseName);
        assertEquals("{{E1}}", email);
        assertEquals("{{H1}}", phone);

        String note = """
                Ann Smith met ANN at Acme (Japan) [R&D].
                Follow up with 山田太郎 via ann.smith+vip@example.com or +81-3-1234-5678.
                """;
        String maskedNote = MaskingEngine.maskFreeText(note, ctx);

        assertFalse(containsIgnoreCase(maskedNote, "Ann Smith"));
        assertFalse(containsIgnoreCase(maskedNote, "Ann"));
        assertFalse(containsIgnoreCase(maskedNote, "Acme (Japan) [R&D]"));
        assertFalse(maskedNote.contains("山田太郎"));
        assertFalse(containsIgnoreCase(maskedNote, "ann.smith+vip@example.com"));
        assertFalse(maskedNote.contains("+81-3-1234-5678"));
        assertEquals("[omitted by policy]", MaskingEngine.maskFreeText("The contact discussed a diagnosis.", ctx));
        assertEquals("[omitted by policy]",
                MaskingEngine.maskFreeText("He has a criminal\r\nrecord on file.", ctx));
        assertEquals("[omitted by policy]",
                MaskingEngine.maskFreeText("See the medical\t history summary.", ctx));

        String serialized = objectMapper.writeValueAsString(Map.of(
                "tokens", List.of(ann, annSmith, company, japaneseName, email, phone),
                "note", maskedNote,
                "warmth", "hot",
                "stage", "renewal"));

        assertDoesNotThrow(() -> OutboundLeakScan.assertNoLeak(serialized, ctx, objectMapper));
        for (String rawValue : ctx.identifierDictionary()) {
            assertFalse(containsIgnoreCase(serialized, rawValue), rawValue);
        }
    }

    @Test
    void leakScanThrowsWhenSerializedPayloadContainsRawIdentifier() throws Exception {
        MaskingContext ctx = new MaskingContext();
        MaskingEngine.maskField(EntityKind.PERSON, "Ann Smith", ctx);
        MaskingEngine.maskField(EntityKind.COMPANY, "Acme Holdings", ctx);
        String serialized = objectMapper.writeValueAsString(Map.of(
                "message", "Please contact Ann Smith at Acme Holdings. Ann Smith owns the account."));

        MaskingLeakException exception = assertThrows(
                MaskingLeakException.class,
                () -> OutboundLeakScan.assertNoLeak(serialized, ctx, objectMapper));

        assertEquals(Set.of(EntityKind.PERSON, EntityKind.COMPANY), exception.leakedKinds());
        assertEquals(2, exception.leakedCount());
        assertFalse(exception.getMessage().contains("Ann Smith"));
        assertFalse(exception.getMessage().contains("Acme Holdings"));
    }

    @Test
    void leakScanThrowsWhenJsonEscapingHidesRawIdentifiers() throws Exception {
        MaskingContext quoted = new MaskingContext();
        MaskingEngine.maskField(EntityKind.PERSON, "Bob \"The Buyer\"", quoted);
        String quotedPayload = objectMapper.writeValueAsString(Map.of("message", "Ask Bob \"The Buyer\" today"));

        MaskingContext slashed = new MaskingContext();
        MaskingEngine.maskField(EntityKind.COMPANY, "Acme\\North", slashed);
        String slashedPayload = objectMapper.writeValueAsString(Map.of("message", "Acme\\North is ready"));

        assertThrows(MaskingLeakException.class,
                () -> OutboundLeakScan.assertNoLeak(quotedPayload, quoted, objectMapper));
        assertThrows(MaskingLeakException.class,
                () -> OutboundLeakScan.assertNoLeak(slashedPayload, slashed, objectMapper));

        MaskingContext keyed = new MaskingContext();
        MaskingEngine.maskField(EntityKind.PERSON, "Bob Smith", keyed);
        String keyedPayload = "{\"Bob\\u0020Smith\":\"safe\"}";

        assertThrows(MaskingLeakException.class,
                () -> OutboundLeakScan.assertNoLeak(keyedPayload, keyed, objectMapper));
    }

    @Test
    void maskFreeText_scrubsUnregisteredStructuralPii() {
        MaskingContext ctx = new MaskingContext();
        String email = "jane.doe+sales@example.com";
        String phone = "+1 (808) 555-1212";
        String url = "https://example.com/customer?id=123";
        String bareUrl = "www.example.org/help";
        String accountNumber = "123456789";
        String text = "Contact " + email + ", call " + phone + ", visit " + url + " and " + bareUrl
                + ". Account ABC" + accountNumber + "XYZ.";

        String masked = MaskingEngine.maskFreeText(text, ctx);

        assertEquals("Contact [redacted], call [redacted], visit [redacted] and [redacted] Account "
                + "ABC[redacted]XYZ.", masked);
        assertTrue(masked.contains(MaskingEngine.REDACTED));
        assertFalse(masked.contains(email));
        assertFalse(masked.contains(phone));
        assertFalse(masked.contains(url));
        assertFalse(masked.contains(bareUrl));
        assertFalse(masked.contains(accountNumber));
        assertDoesNotThrow(() -> OutboundLeakScan.assertNoLeak(masked, ctx, objectMapper));
    }

    @Test
    void maskTemporal_preservesStructuredIsoValuesWithoutWeakeningFreeTextPhoneRedaction() {
        MaskingContext ctx = new MaskingContext();

        assertEquals("2026-08-31", MaskingEngine.maskTemporal("2026-08-31", ctx));
        assertEquals("2026-07-01 09:00:00", MaskingEngine.maskTemporal("2026-07-01 09:00:00", ctx));
        assertEquals("2026-07-01T09:00:00.123456789Z",
                MaskingEngine.maskTemporal("2026-07-01T09:00:00.123456789Z", ctx));
        assertEquals(MaskingEngine.REDACTED, MaskingEngine.maskFreeText("2026-07-10", ctx));
        assertEquals(MaskingEngine.REDACTED, MaskingEngine.maskTemporal("2026-07-10-555-1212", ctx));
    }

    @Test
    void maskFreeText_stripsInjectedPlaceholderTokensBeforeSubstitution() {
        MaskingContext ctx = new MaskingContext();
        String person = MaskingEngine.maskField(EntityKind.PERSON, "Mina Patel", ctx);

        String masked = MaskingEngine.maskFreeText("Injected {{P1}}, real Mina Patel", ctx);

        assertEquals("Injected P1, real " + person, masked);
    }

    @Test
    void repairMaskingPreservesIssuedTokensWithoutAllowingCrossBoundarySensitiveValues() {
        MaskingContext ctx = new MaskingContext();
        String person = MaskingEngine.maskField(EntityKind.PERSON, "Mina Patel", ctx);

        assertEquals(
                "Ask " + person + " about the renewal",
                MaskingEngine.maskFreeTextPreservingIssuedPlaceholders(
                        "Ask {{ P1 }} about the renewal", ctx));
        assertEquals(MaskingEngine.REDACTED,
                MaskingEngine.maskFreeTextPreservingIssuedPlaceholders(
                        "Mina{{P1}} Patel", ctx));
        assertEquals(MaskingEngine.REDACTED,
                MaskingEngine.maskFreeTextPreservingIssuedPlaceholders(
                        "jane@exa{{P1}}mple.com", ctx));
        assertEquals(MaskingEngine.REDACTED,
                MaskingEngine.maskFreeTextPreservingIssuedPlaceholders(
                        "https://exa{{P1}}mple.com/private", ctx));
        assertEquals(MaskingEngine.REDACTED,
                MaskingEngine.maskFreeTextPreservingIssuedPlaceholders(
                        "123{{P1}}456789", ctx));
        assertEquals(MaskingEngine.REDACTED,
                MaskingEngine.maskFreeTextPreservingIssuedPlaceholders(
                        "+1 415{{P1}} 555 0100", ctx));
        assertEquals(MaskingEngine.OMITTED_BY_POLICY,
                MaskingEngine.maskFreeTextPreservingIssuedPlaceholders(
                        "diag{{P1}}nosis", ctx));
        assertEquals(MaskingEngine.OMITTED_BY_POLICY,
                MaskingEngine.maskFreeTextPreservingIssuedPlaceholders(
                        "medical {{P1}} history", ctx));
        assertEquals(MaskingEngine.OMITTED_BY_POLICY,
                MaskingEngine.maskFreeTextPreservingIssuedPlaceholders(
                        "labor {{P1}} union", ctx));
    }

    @Test
    void maskFreeText_screensSpecialCareHiddenByInjectedDelimiters() {
        MaskingContext ctx = new MaskingContext();
        for (String delimiters : List.of("{{}}", "{}", "{", "}")) {
            String text = "The contact discussed a diagn" + delimiters + "osis.";
            assertEquals(MaskingEngine.OMITTED_BY_POLICY, MaskingEngine.maskFreeText(text, ctx));
            assertEquals(MaskingEngine.OMITTED_BY_POLICY, MaskingEngine.maskConversationalFreeText(text, ctx));
            assertEquals(MaskingEngine.OMITTED_BY_POLICY, MaskingEngine.screenFreeTextBeforeTruncation(text, ctx));
            assertEquals(MaskingEngine.OMITTED_BY_POLICY,
                    MaskingEngine.maskFreeTextPreservingIssuedPlaceholders(text, ctx));
        }
    }

    @Test
    void contactDetectorsRedactSevenAndEightDigitSuffixesAfterAnEmail() {
        for (String suffix : List.of("8085551", "80855512")) {
            String text = "alice@example.com" + suffix;
            MaskingContext context = new MaskingContext();

            assertEquals(MaskingEngine.REDACTED, MaskingEngine.maskFreeText(text, context));
            assertEquals(MaskingEngine.REDACTED, MaskingEngine.maskConversationalFreeText(text, context));
            assertEquals(MaskingEngine.REDACTED, MaskingEngine.screenFreeTextBeforeTruncation(text, context));
            assertEquals(MaskingEngine.REDACTED, MaskingEngine.maskFreeText(text,
                    new MaskingContext(AiPrivacyMode.UNMASKED)));
            assertEquals(MaskingEngine.REDACTED,
                    MaskingEngine.maskFreeText("https://alice@example.com/private/" + suffix, context));
        }
    }

    @Test
    void mentionAdmissionSanitizesDelimitersBeforeComparingIdentifiers() {
        for (String delimiters : List.of("{{}}", "\uFF5B\uFF5B\uFF5D\uFF5D")) {
            String text = "What is happening with John O'" + delimiters + "Connor?";
            MaskingEngine.MentionScanText scanText = MaskingEngine.mentionScanText(text);

            assertEquals("what is happening with john o'connor?", scanText.normalizedText());
            assertTrue(MaskingEngine.containsIdentifierMention(scanText, "John O'Connor"));
            assertTrue(MaskingEngine.containsIdentifierMention(
                    "Ask John O'Connor today.", "John O'" + delimiters + "Connor"));
            assertFalse(MaskingEngine.containsIdentifierMention(
                    "Ask xJohn O'" + delimiters + "Connor today.", "John O'Connor"));
        }
    }

    @Test
    void fullwidthDelimiterIdentifiersShareRegistrationScreeningAndScanCanonicalization() {
        String name = "John\uFF5B\uFF5B\uFF5D\uFF5Dathan Smith";
        MaskingContext context = new MaskingContext();
        String token = MaskingEngine.maskField(EntityKind.PERSON, name, context);

        for (String spelling : List.of(name, "John{{}}athan Smith", "Johnathan Smith")) {
            assertEquals("Ask " + token + " today.", MaskingEngine.maskFreeText("Ask " + spelling + " today.", context));
            assertEquals(MaskingEngine.REDACTED,
                    MaskingEngine.screenFreeTextBeforeTruncation("x".repeat(498) + spelling, context));
            assertThrows(MaskingLeakException.class,
                    () -> OutboundLeakScan.assertNoLeakStrict("Ask " + spelling + " today.", context, objectMapper));
        }
        String plainToken = MaskingEngine.maskField(EntityKind.PERSON, "Johnathan Smith", context);
        assertNotEquals(token, plainToken);
        assertEquals(name, Demasker.demask(token, context).text());
        assertEquals("Johnathan Smith", Demasker.demask(plainToken, context).text());
        assertDoesNotThrow(() -> OutboundLeakScan.assertNoLeakStrict(token, context, objectMapper));
    }

    @Test
    void canonicalizationDeletesEveryDelimiterWhereverItSits() {
        for (String name : List.of("John {}}{ Smith", "John Smith}", "{John Smith",
                "John \uFF5B\uFE5C Smith", "John \uFE37\uFE38 Smith")) {
            assertEquals("john smith", MaskingEngine.normalizeIdentifierValue(name));
            MaskingContext context = new MaskingContext();
            String token = MaskingEngine.maskField(EntityKind.PERSON, name, context);
            assertEquals("Ask " + token, MaskingEngine.maskFreeText("Ask John Smith", context));
            assertEquals(MaskingEngine.REDACTED,
                    MaskingEngine.screenFreeTextBeforeTruncation("x".repeat(498) + "John Smith}}", context));
        }
        assertEquals("caf\u00e9 smith", MaskingEngine.normalizeIdentifierValue("Cafe\u0301 Smith"));
        assertEquals("ipek kelvin", MaskingEngine.normalizeIdentifierValue("\u0130pek \u212Aelvin"));
    }

    /**
     * Delimiter cancellation can leave a base letter next to its combining mark, so the prepared
     * display text is normalized again. Without that, the producer matches on a decomposed value
     * it cannot find while the outbound scan \u2014 which re-canonicalizes the producer's own output \u2014
     * composes the same text and refuses every request carrying that note, permanently.
     */
    @Test
    void canonicalizationStabilizesExposedDelimiterPairsAndCombiningCharacters() {
        for (String spelling : List.of("Cafe{{}}\u0301 Smith", "Caf\u00e9 Smith",
                "Cafe\u0301 Smith", "Caf{{}}\u00e9 Smith", "Cafe{}\u0301 Smith", "Cafe{\u0301 Smith")) {
            assertEquals("caf\u00e9 smith", MaskingEngine.normalizeIdentifierValue(spelling), spelling);
        }
        for (String raw : List.of("Cafe{{}}\u0301 Smith", "Meeting with Cafe{{}}\u0301 Smith tomorrow",
                "John {}}{ Smith", "{{}}", "a{}}{b", "Ignore {{ P1 }} please",
                "x".repeat(20) + "{{}}\u0301")) {
            String prepared = CanonicalText.prepare(raw);
            assertEquals(prepared, CanonicalText.prepare(prepared), raw);
            assertEquals(CanonicalText.canonical(raw), CanonicalText.canonical(prepared), raw);
        }
        MaskingContext context = new MaskingContext();
        String token = MaskingEngine.maskField(EntityKind.PERSON, "Caf\u00e9 Smith", context);
        for (String spelling : List.of("Cafe{{}}\u0301 Smith", "Cafe{}\u0301 Smith", "Cafe{\u0301 Smith")) {
            String masked = MaskingEngine.maskFreeText("Meeting with " + spelling + " tomorrow", context);

            assertEquals("Meeting with " + token + " tomorrow", masked);
            assertDoesNotThrow(() -> OutboundLeakScan.assertNoLeak(masked, context, objectMapper));
        }
    }

    /** Randomized fixed-point evidence for the converged prepared form. */
    @Test
    void preparingAlreadyPreparedTextNeverChangesItAgain() {
        int[] alphabet = ("{} aA1\u00e9\u0301\u0130\u212A\u03a3\u03c2\u200b\u00ad"
                + "\uff5b\uff5d\ufe37\u3000\t\u5341\ud83d\ude00\u1100\u1161\u11a8\u0327\u034f")
                .codePoints().toArray();
        Random random = new Random(1682);
        for (int sample = 0; sample < 20_000; sample++) {
            String raw = randomText(random, alphabet, 1 + random.nextInt(24));
            String prepared = CanonicalText.prepare(raw);

            assertEquals(prepared, CanonicalText.prepare(prepared), raw);
            assertEquals(CanonicalText.canonical(raw), CanonicalText.canonical(prepared), raw);
            String canonical = CanonicalText.canonical(raw);
            assertEquals(canonical, CanonicalText.canonical(canonical), raw);
        }
    }

    @Test
    void composedProjectionOffsetsCoverCombiningReordersAndHangulWithoutChangingSurroundingBraces() {
        for (String spelling : List.of("Cafe{}\u0301 Smith", "Cafe{\u0327}\u0301 Smith",
                "\u1100{\u1161}\u11a8 Smith", "\u0130{}\u0301 Smith", "\u0130{}\u0327 Smith",
                "\ud801\udc00{}\u0301 Smith")) {
            String name = Normalizer.normalize(spelling.replace("{", "").replace("}", ""), Normalizer.Form.NFKC);
            MaskingContext context = new MaskingContext();
            String token = MaskingEngine.maskField(EntityKind.PERSON, name, context);
            String masked = MaskingEngine.maskFreeText("{before} " + spelling + " {after}", context);

            assertEquals("{before} " + token + " {after}", masked, spelling);
            assertDoesNotThrow(() -> OutboundLeakScan.assertNoLeakStrict(masked, context, objectMapper));
            assertEquals(token, MaskingEngine.maskFreeText(CanonicalText.canonical(name), context));
        }
    }

    /**
     * ROOT lowercasing folds a trailing capital sigma differently from a medial one, so a name
     * canonicalized standalone at registration must not stop matching the same spelling written
     * inside a sentence.
     */
    @Test
    void greekFinalSigmaFoldsTheSameStandaloneAndInContext() {
        assertEquals("niko\u03c3", MaskingEngine.normalizeIdentifierValue("NIKO\u03a3"));
        assertEquals("niko\u03c3", MaskingEngine.normalizeIdentifierValue("Niko\u03c2"));
        MaskingContext context = new MaskingContext();
        String token = MaskingEngine.maskField(EntityKind.PERSON, "NIKO\u03a3", context);

        for (String text : List.of("meet NIKO\u03a3\u0391 today", "meet NIKO\u03a3x today", "meet NIKO\u03a3 today",
                "meet Niko\u03c2 today")) {
            String masked = MaskingEngine.maskFreeText(text, context);
            String screened = MaskingEngine.screenFreeTextBeforeTruncation(
                    "x".repeat(498) + text, context);

            assertEquals(text.contains("\u03a3x") ? "meet [redacted] today"
                    : text.replace("NIKO\u03a3", token).replace("Niko\u03c2", token), masked, text);
            assertFalse(containsIgnoreCase(masked, "niko"), text);
            assertFalse(containsIgnoreCase(screened, "niko"), text);
            String capped = screened.substring(0, Math.min(512, screened.length()));
            assertDoesNotThrow(() -> OutboundLeakScan.assertNoLeakStrict(capped, context, objectMapper));
            assertDoesNotThrow(() -> OutboundLeakScan.assertNoLeakStrict(masked, context, objectMapper));
            assertThrows(MaskingLeakException.class,
                    () -> OutboundLeakScan.assertNoLeakStrict(text, context, objectMapper));
        }
    }

    /**
     * A zero-width or bidi-control character inside a name renders as the bare name and is read as
     * the bare name by a model, so every control in the chain has to be blind to it at once.
     */
    @Test
    void invisibleFormatCharactersCannotHideASeededIdentifier() {
        for (String invisible : List.of("\u200b", "\u200d", "\u200f", "\u00ad", "\ufeff", "\u034f",
                "\u2065", "\udb40\udd00", "\u115f", "\u180b", "\ufe0f")) {
            MaskingContext context = new MaskingContext();
            String token = MaskingEngine.maskField(
                    EntityKind.PERSON, "Johnathan Smith", context);
            String text = "Met Johnathan" + invisible + " Smith today.";

            MaskingContext collisionContext = new MaskingContext();
            assertNotEquals(MaskingEngine.maskField(EntityKind.PERSON, "Johnathan Smith", collisionContext),
                    MaskingEngine.maskField(EntityKind.PERSON, "Johnathan" + invisible + " Smith", collisionContext));
            assertEquals("Met " + MaskingEngine.REDACTED + " today.",
                    MaskingEngine.maskFreeText(text, collisionContext));
            assertEquals("Met " + MaskingEngine.REDACTED + " today.",
                    MaskingEngine.maskFreeText("Met Johnathan Smith today.", collisionContext));
            MaskingContext invisibleContext = new MaskingContext();
            String invisibleToken = MaskingEngine.maskField(
                    EntityKind.PERSON, "Johnathan" + invisible + " Smith", invisibleContext);
            assertEquals("Met " + invisibleToken + " today.",
                    MaskingEngine.maskFreeText("Met Johnathan Smith today.", invisibleContext));
            String masked = MaskingEngine.maskFreeText(text, context);
            assertEquals("Met " + token + " today.", masked);
            assertEquals("Met " + token + " today.",
                    MaskingEngine.maskFreeText("Met Johnathan Smith today.", context));
            assertEquals(MaskingEngine.REDACTED, MaskingEngine.screenFreeTextBeforeTruncation(
                    "x".repeat(498) + "Johnathan" + invisible + " Smith", context));
            assertTrue(MaskingEngine.containsIdentifierMention(text, "Johnathan Smith"));
            assertDoesNotThrow(() -> OutboundLeakScan.assertNoLeakStrict(masked, context, objectMapper));
            assertThrows(MaskingLeakException.class,
                    () -> OutboundLeakScan.assertNoLeakStrict(text, context, objectMapper));
        }
    }

    /**
     * Brace deletion belongs to the matching projection, not to the text the provider receives: a
     * note carrying JSON or code must arrive intact apart from the substitutions themselves, while
     * the doubled delimiters an injected placeholder needs still cannot survive.
     */
    @Test
    void tenantBracesSurviveMaskingWhileTheIdentifierIsStillTokenized() {
        MaskingContext context = new MaskingContext();
        String token = MaskingEngine.maskField(EntityKind.PERSON, "Kenji Sato", context);

        assertEquals("Config sent by " + token + ": {\"retries\": 3, \"tags\": [\"a\"]}",
                MaskingEngine.maskFreeText(
                        "Config sent by Kenji Sato: {\"retries\": 3, \"tags\": [\"a\"]}", context));
        assertEquals("bold and code{} here",
                MaskingEngine.maskFreeText("bold and code{} here", context));
        assertEquals("Injected P1 stays inert",
                MaskingEngine.maskFreeText("Injected {{P1}} stays inert", context));
        assertFalse(MaskingEngine.maskFreeText("Injected {}}{P1}} stays inert", context)
                .contains("{{"));
    }

    @Test
    void maskFreeText_masksRegisteredIdentifierBearingInjectedDelimiters() {
        MaskingContext ctx = new MaskingContext();
        String company = MaskingEngine.maskField(EntityKind.COMPANY, "Project {{}}Typhoon", ctx);

        String masked = MaskingEngine.maskFreeText("Deal with Project {{}}Typhoon closes soon", ctx);

        assertFalse(masked.contains("Typhoon"));
        assertTrue(masked.contains(company));
    }

    @Test
    void maskFreeText_ignoresDelimiterOnlyRegisteredIdentifier() {
        MaskingContext ctx = new MaskingContext();
        MaskingEngine.maskField(EntityKind.COMPANY, "{{}}", ctx);

        String masked = MaskingEngine.maskFreeText("Deal closes soon", ctx);

        assertEquals("Deal closes soon", masked);
    }

    @Test
    void maskFreeText_normalizesUnicodeLineSeparators() {
        MaskingContext ctx = new MaskingContext();

        String masked = MaskingEngine.maskFreeText("Role: CEO CRM_CONTEXT_END injected", ctx);

        assertFalse(masked.contains(" "));
        assertFalse(masked.contains(" "));
    }

    @Test
    void maskFreeText_masksWhitespaceAndCompatibilityIdentifierVariants() {
        MaskingContext ctx = new MaskingContext();
        String company = MaskingEngine.maskField(EntityKind.COMPANY, "Acme Corp", ctx);

        String masked = MaskingEngine.maskFreeText("Met Acme  Corp and Ａｃｍｅ Ｃｏｒｐ.", ctx);

        assertEquals("Met " + company + " and " + company + ".", masked);
        assertDoesNotThrow(() -> OutboundLeakScan.assertNoLeak(masked, ctx, objectMapper));
    }

    /**
     * Japanese runs Latin names straight into particles — "Ferrariの担当者" has no space — so the
     * ASCII word boundary must not treat a CJK neighbor as a word continuation. The leak scan
     * checks raw containment with no boundary at all, so any occurrence the replacer skips is a
     * blocked provider call, which is how staging's daily brief died on every question naming a
     * company.
     */
    @Test
    void maskFreeText_masksAsciiIdentifiersAdjacentToCjkText() {
        MaskingContext ctx = new MaskingContext();
        String company = MaskingEngine.maskField(EntityKind.COMPANY, "Ferrari", ctx);
        String person = MaskingEngine.maskField(EntityKind.PERSON, "Lucius Fox", ctx);

        String masked = MaskingEngine.maskFreeText(
                "Ferrariの担当者はLucius Foxさんです。Ferrariとの取引を確認して。", ctx);

        assertEquals(company + "の担当者は" + person + "さんです。"
                + company + "との取引を確認して。", masked);
        assertDoesNotThrow(() -> OutboundLeakScan.assertNoLeak(masked, ctx, objectMapper));
    }

    /**
     * The replacer must cover every occurrence the outbound leak scan can flag: the scan does raw
     * normalized containment, so an identifier embedded inside a longer ASCII word must still be
     * covered rather than left to fail the whole provider call closed. An embedded occurrence is
     * not an entity reference, so the whole containing word is redacted — never tokenized, which
     * would fabricate an entity mention inside an unrelated word.
     */
    @Test
    void maskFreeText_redactsWordsEmbeddingAnIdentifierRatherThanBlockingOrTokenizing() {
        MaskingContext ctx = new MaskingContext();
        String token = MaskingEngine.maskField(EntityKind.PERSON, "Mark", ctx);

        String masked = MaskingEngine.maskFreeText("Market notes remarked on Mark.", ctx);

        assertEquals("[redacted] notes [redacted] on " + token + ".", masked);
        assertDoesNotThrow(() -> OutboundLeakScan.assertNoLeak(masked, ctx, objectMapper));
    }

    /**
     * The ASCII boundary distinguishes scripts, not just the ASCII range: an accented letter
     * continues a Latin word, so a seeded short name must not shred ordinary French or German
     * prose, while a CJK neighbor still counts as a boundary.
     */
    @Test
    void maskFreeText_leavesAccentedLatinWordsContainingAShortIdentifierIntact() {
        MaskingContext ctx = new MaskingContext();
        String token = MaskingEngine.maskField(EntityKind.PERSON, "Ann", ctx);

        String masked = MaskingEngine.maskFreeText("Une année importante pour Ann.", ctx);

        assertEquals("Une année importante pour " + token + ".", masked);
    }

    /**
     * Redaction sentinels must survive re-masking even when a seeded name is a substring of the
     * sentinel text itself; corrupting {@code [redacted]} would break every downstream consumer,
     * and the scan already fails such payloads closed.
     */
    @Test
    void maskFreeText_neverCorruptsRedactionSentinelsWithResidualReplacement() {
        MaskingContext ctx = new MaskingContext();
        String token = MaskingEngine.maskField(EntityKind.COMPANY, "Acted", ctx);

        String masked = MaskingEngine.maskFreeText(
                "Email someone@example.com about Acted.", ctx);

        assertEquals("Email [redacted] about " + token + ".", masked);
    }

    /**
     * Contact-data matches must share unchanged source text with identifier matching: a token
     * spliced into the middle of an email or phone number would defeat the detectors, egressing the raw
     * local part, domain, and digit fragments around it — data the leak scan cannot flag because
     * only the seeded name itself is in the dictionary.
     */
    @Test
    void maskFreeText_redactsContactDataWithoutIdentifierTokensSplicingIt() {
        MaskingContext ctx = new MaskingContext();
        MaskingEngine.maskField(EntityKind.PERSON, "Nakagawa", ctx);

        String masked = MaskingEngine.maskFreeText(
                "Contact hunternakagawa@gmail.com or call 09012345678.", ctx);

        assertEquals("Contact [redacted] or call [redacted].", masked);
        assertFalse(containsIgnoreCase(masked, "gmail"));
        assertDoesNotThrow(() -> OutboundLeakScan.assertNoLeak(masked, ctx, objectMapper));
    }

    /**
     * Longest-first precedence must hold across the boundary and residual matching strategies: a
     * shorter identifier matching at a CJK-adjacent position inside a longer identifier's
     * ASCII-embedded occurrence would otherwise fragment it, and the surviving raw fragment would
     * egress because the leak scan checks whole identifiers only.
     */
    @Test
    void maskFreeText_longestIdentifierWinsAcrossBoundaryAndResidualMatching() {
        MaskingContext ctx = new MaskingContext();
        MaskingEngine.maskField(EntityKind.COMPANY, "Acme楽天Corp", ctx);
        MaskingEngine.maskField(EntityKind.COMPANY, "Corp", ctx);

        String masked = MaskingEngine.maskFreeText("XAcme楽天Corpの件", ctx);

        assertEquals("[redacted]の件", masked);
        assertFalse(masked.contains("Acme楽天"));
        assertDoesNotThrow(() -> OutboundLeakScan.assertNoLeak(masked, ctx, objectMapper));
    }

    /**
     * Temporal preservation must never disclose a record whose display name is a valid ISO value:
     * the system prompt's own date examples would exempt it from the leak scan, so passing it
     * through unchanged would egress a tenant name under a name field. Reproduced from review —
     * the identifier check forces such a value through ordinary masking instead.
     */
    @Test
    void maskTemporalRefusesToPreserveAnIsoValueThatIsASeededIdentifier() {
        MaskingContext ctx = new MaskingContext();
        MaskingEngine.maskField(EntityKind.COMPANY, "2026-08-21", ctx);
        ctx.addTrustedStaticText("asOf values look like 2026-08-21 or 2026-08-21T09:00:00Z.");

        String masked = MaskingEngine.maskTemporal("2026-08-21", ctx);
        assertFalse(masked.contains("2026-08-21"));

        assertEquals("2026-08-22", MaskingEngine.maskTemporal("2026-08-22", ctx));
    }

    /**
     * A record named a common envelope word must not poison the request: once the server-authored
     * text is registered as trusted, the scan skips the colliding identifier — the server emits
     * that word regardless of tenant data — while every other identifier keeps failing closed.
     */
    @Test
    void leakScanSkipsIdentifiersTheRegisteredServerTextProvablyContains() {
        MaskingContext ctx = new MaskingContext();
        MaskingEngine.maskField(EntityKind.COMPANY, "what", ctx);
        MaskingEngine.maskField(EntityKind.COMPANY, "Acme Corp", ctx);
        String payload = "{\"prompt\":\"and what is each one waiting on?\"}";

        assertThrows(MaskingLeakException.class,
                () -> OutboundLeakScan.assertNoLeak(payload, ctx, objectMapper));

        ctx.addTrustedStaticText("State plainly what is missing before answering.");

        assertDoesNotThrow(() -> OutboundLeakScan.assertNoLeak(payload, ctx, objectMapper));
        assertThrows(MaskingLeakException.class, () -> OutboundLeakScan.assertNoLeak(
                "{\"prompt\":\"met Acme Corp about what\"}", ctx, objectMapper));
    }

    @Test
    void unmaskedModeKeepsIdentifiersWhileUniversalScreensStillApply() {
        MaskingContext context = new MaskingContext(AiPrivacyMode.UNMASKED);

        assertEquals("Mina Patel",
                MaskingEngine.maskField(EntityKind.PERSON, "Mina Patel", context));
        assertEquals("Met Mina Patel",
                MaskingEngine.maskFreeText("Met Mina Patel", context));
        assertEquals("Email [redacted]",
                MaskingEngine.maskFreeText("Email mina@example.com", context));
        assertEquals(MaskingEngine.OMITTED_BY_POLICY,
                MaskingEngine.maskFreeText("Medical history discussed", context));
        assertTrue(context.tokenBindings().isEmpty());
    }

    private static boolean containsIgnoreCase(String value, String needle) {
        return value.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }

    private static String nfkc(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC);
    }

    private static String randomText(Random random, int[] codePoints, int length) {
        StringBuilder text = new StringBuilder(length);
        for (int index = 0; index < length; index++) {
            text.appendCodePoint(codePoints[random.nextInt(codePoints.length)]);
        }
        return text.toString();
    }

    /**
     * Compares the replacer against the legacy sequential regexes while ignoring how many adjacent
     * redaction markers a region produces. Span merging redacts the union of touching matches once
     * where the legacy passes emitted one marker per match; the redacted characters are identical,
     * so both sides are compared with adjacent markers coalesced.
     */
    private static void assertRedactsLikeLegacy(String expected, String actual, String text) {
        assertEquals(coalesceAdjacentRedactions(expected), coalesceAdjacentRedactions(actual), text);
    }

    private static String coalesceAdjacentRedactions(String text) {
        return ADJACENT_REDACTIONS.matcher(text).replaceAll(REDACTION);
    }

    private static String legacyRedactContactData(String text) {
        String redacted = LEGACY_EMAIL.matcher(nfkc(text)).replaceAll(REDACTION);
        redacted = LEGACY_URL.matcher(redacted).replaceAll(REDACTION);
        redacted = LEGACY_PHONE.matcher(redacted).replaceAll(REDACTION);
        return LEGACY_DIGIT_RUN.matcher(redacted).replaceAll(REDACTION);
    }

    /**
     * The generated alphabet contains no contact data, and every eligible primary occurrence is
     * contained in a residual match. Lookahead collects overlapping regex matches on the original
     * text before redacting their union; substituting a primary match first would erase the evidence
     * needed to absorb its surrounding word run.
     */
    private static String greedyScreenWithResidual(String text, String rawValue) {
        String sanitized = nfkc(text);
        String value = nfkc(rawValue).trim();
        String quoted = Arrays.stream(WHITESPACE.split(value))
                .map(Pattern::quote)
                .collect(Collectors.joining("\\s+"));
        Pattern residual = Pattern.compile(
                "(?=([A-Za-z0-9_]*" + quoted + "[A-Za-z0-9_]*))",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        Matcher matches = residual.matcher(sanitized);
        boolean[] covered = new boolean[sanitized.length()];
        while (matches.find()) {
            Arrays.fill(covered, matches.start(1), matches.end(1), true);
        }
        StringBuilder redacted = new StringBuilder(sanitized.length());
        for (int offset = 0; offset < sanitized.length(); offset++) {
            if (!covered[offset]) {
                redacted.append(sanitized.charAt(offset));
            } else if (offset == 0 || !covered[offset - 1]) {
                redacted.append(MaskingEngine.REDACTED);
            }
        }
        return redacted.toString();
    }

}
