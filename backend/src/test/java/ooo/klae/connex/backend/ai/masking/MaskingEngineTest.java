package ooo.klae.connex.backend.ai.masking;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.ai.AiPrivacyMode;

import tools.jackson.databind.ObjectMapper;

class MaskingEngineTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

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

        assertEquals(MaskingEngine.OMITTED_BY_POLICY,
                MaskingEngine.maskFreeText("The contact discussed a diagn{{}}osis.", ctx));
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
     * masked rather than left to fail the whole provider call closed.
     */
    @Test
    void maskFreeText_masksIdentifiersEmbeddedInLongerWordsRatherThanBlockingTheCall() {
        MaskingContext ctx = new MaskingContext();
        MaskingEngine.maskField(EntityKind.COMPANY, "Acme", ctx);

        String masked = MaskingEngine.maskFreeText("Ask the Acmeister about renewal.", ctx);

        assertDoesNotThrow(() -> OutboundLeakScan.assertNoLeak(masked, ctx, objectMapper));
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

}
