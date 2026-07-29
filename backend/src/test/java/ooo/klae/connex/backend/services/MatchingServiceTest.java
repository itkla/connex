package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.apache.hc.client5.http.psl.PublicSuffixMatcherLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.i18n.phonenumbers.PhoneNumberUtil;

/**
 * Pure unit coverage for canonical identity normalization.
 */
class MatchingServiceTest {

    private MatchingService matchingService;

    @BeforeEach
    void setUp() {
        matchingService = new MatchingService(
            PhoneNumberUtil.getInstance(),
            PublicSuffixMatcherLoader.getDefault());
    }

    @Test
    void normalizesJapaneseNamesWithUnicodeAndKanaFolding() {
        assertAll(
            () -> assertNormalizedName("ｶﾀｶﾅ　ＡＢＣ", "かたかな abc"),
            () -> assertNormalizedName("コーヒー", "こーひー"),
            () -> assertNormalizedName("ヤマダ　 太郎", "やまだ 太郎"),
            () -> assertNormalizedName("ｶﾞ ヽヾ", "が ゝゞ"),
            () -> assertNormalizedName("ヷヸヹヺ", "わ゙ゐ゙ゑ゙を゙"),
            () -> assertNormalizedName("カタカナ・Name", "かたかな・name"),
            () -> assertNormalizedName("  山田\t\n太郎  ", "山田 太郎"));
    }

    @Test
    void rejectsUnsafeOrOversizedNames() {
        assertAll(
            () -> assertEmptyName(null),
            () -> assertEmptyName(""),
            () -> assertEmptyName("　"),
            () -> assertEmptyName("山田\u0000太郎"),
            () -> assertEmptyName("山田\u200b太郎"),
            () -> assertEmptyName("a".repeat(256)));
    }

    @Test
    void normalizesJapaneseAndInternationalPhonesToE164() {
        assertAll(
            () -> assertIdentifier(IdentityKind.PHONE, "090-1234-5678", "+819012345678"),
            () -> assertIdentifier(IdentityKind.PHONE, "+81 90 1234 5678", "+819012345678"),
            () -> assertIdentifier(
                IdentityKind.PHONE,
                "＋８１（０）９０－１２３４－５６７８",
                "+819012345678"),
            () -> assertIdentifier(IdentityKind.PHONE, "03-1234-5678", "+81312345678"),
            () -> assertIdentifier(IdentityKind.PHONE, "+1 202-555-0123", "+12025550123"),
            () -> assertIdentifier(IdentityKind.PHONE, "+81-090-1234-5678", "+819012345678"),
            () -> assertIdentifier(IdentityKind.PHONE, "０９０・１２３４・５６７８", "+819012345678"));
    }

    @Test
    void resolvesNationalFormatPhonesAgainstJapanAsTheDefaultRegion() {
        assertAll(
            () -> assertIdentifier(IdentityKind.PHONE, "0120-123-456", "+81120123456"),
            () -> assertIdentifier(IdentityKind.PHONE, "020 7946 0958", "+812079460958"),
            () -> assertIdentifier(IdentityKind.PHONE, "01 42 68 53 00", "+81142685300"));
    }

    @Test
    void rejectsMalformedOrInvalidPhones() {
        assertAll(
            () -> assertEmpty(IdentityKind.PHONE, "090-1234-5678 内線 2"),
            () -> assertEmpty(IdentityKind.PHONE, "०३-१२३४-५६७८"),
            () -> assertEmpty(IdentityKind.PHONE, "090-12"),
            () -> assertEmpty(IdentityKind.PHONE, "090-ABCD-5678"),
            () -> assertEmpty(IdentityKind.PHONE, "++819012345678"),
            () -> assertEmpty(IdentityKind.PHONE, "81 90 1234 5678"),
            () -> assertEmpty(IdentityKind.PHONE, "+81(09012345678"),
            () -> assertEmpty(IdentityKind.PHONE, "0".repeat(257)));
    }

    @Test
    void normalizesAsciiAndIdnEmailsWithoutProviderSpecificFolding() {
        assertAll(
            () -> assertIdentifier(
                IdentityKind.EMAIL,
                "  Test.User+Tag@EXAMPLE.COM　",
                "test.user+tag@example.com"),
            () -> assertIdentifier(
                IdentityKind.EMAIL,
                "Ｔｅｓｔ＋tag＠例え.co.jp",
                "test+tag@xn--r8jz45g.co.jp"),
            () -> assertEquals(
                Optional.of("example.co.jp"),
                matchingService.extractCompanyDomainFromEmail("Person@dept.example.co.jp")));
    }

    @Test
    void rejectsUnsupportedOrUnsafeEmailSyntax() {
        String oversizedLabel = "a".repeat(64) + ".com";
        assertAll(
            () -> assertEmpty(IdentityKind.EMAIL, "a@@example.com"),
            () -> assertEmpty(IdentityKind.EMAIL, "日本語@example.com"),
            () -> assertEmpty(IdentityKind.EMAIL, "\"quoted\"@example.com"),
            () -> assertEmpty(IdentityKind.EMAIL, "user@[127.0.0.1]"),
            () -> assertEmpty(IdentityKind.EMAIL, "user @example.com"),
            () -> assertEmpty(IdentityKind.EMAIL, ".user@example.com"),
            () -> assertEmpty(IdentityKind.EMAIL, "user..tag@example.com"),
            () -> assertEmpty(IdentityKind.EMAIL, "user@" + oversizedLabel),
            () -> assertEmpty(IdentityKind.EMAIL, "user@example"),
            () -> assertEquals(Optional.empty(), matchingService.extractCompanyDomainFromEmail("invalid")));
    }

    @Test
    void extractsPslAwareRegistrableDomains() {
        assertAll(
            () -> assertIdentifier(
                IdentityKind.DOMAIN,
                "https://www.foo.co.jp/about",
                "foo.co.jp"),
            () -> assertIdentifier(
                IdentityKind.DOMAIN,
                "sub.example.github.io",
                "example.github.io"),
            () -> assertIdentifier(
                IdentityKind.DOMAIN,
                "Person@dept.example.co.jp",
                "example.co.jp"),
            () -> assertIdentifier(
                IdentityKind.DOMAIN,
                "//WWW.Example.COM:8443/path?x=1#part",
                "example.com"),
            () -> assertIdentifier(
                IdentityKind.DOMAIN,
                "https://例え.co.jp/path",
                "xn--r8jz45g.co.jp"),
            () -> assertIdentifier(
                IdentityKind.DOMAIN,
                "www.city.kawasaki.jp",
                "city.kawasaki.jp"),
            () -> assertIdentifier(IdentityKind.DOMAIN, "a.b.ck", "a.b.ck"));
    }

    @Test
    void rejectsNonRegistrableOrUnsafeDomainInputs() {
        assertAll(
            () -> assertEmpty(IdentityKind.DOMAIN, "co.jp"),
            () -> assertEmpty(IdentityKind.DOMAIN, "github.io"),
            () -> assertEmpty(IdentityKind.DOMAIN, "https://user:pass@example.com"),
            () -> assertEmpty(IdentityKind.DOMAIN, "ftp://example.com"),
            () -> assertEmpty(IdentityKind.DOMAIN, "127.0.0.1"),
            () -> assertEmpty(IdentityKind.DOMAIN, "https://[::1]"),
            () -> assertEmpty(IdentityKind.DOMAIN, "https://exa%6dple.com"),
            () -> assertEmpty(IdentityKind.DOMAIN, "example.com:99999"),
            () -> assertEmpty(IdentityKind.DOMAIN, "example..com"),
            () -> assertEmpty(IdentityKind.DOMAIN, "localhost"));
    }

    @Test
    void normalizesExternalIdsConservatively() {
        assertAll(
            () -> assertIdentifier(IdentityKind.EXTERNAL_ID, "  CRM:Account/ＡＢＣ-123  ", "crm:account/abc-123"),
            () -> assertEmpty(IdentityKind.EXTERNAL_ID, "abc 123"),
            () -> assertEmpty(IdentityKind.EXTERNAL_ID, "顧客-123"),
            () -> assertEmpty(IdentityKind.EXTERNAL_ID, "abc\u200b123"),
            () -> assertEmpty(IdentityKind.EXTERNAL_ID, "a".repeat(513)),
            () -> assertEmpty(IdentityKind.EXTERNAL_ID, null));
    }

    @Test
    void rejectsNullIdentifierKindAsProgrammingError() {
        assertThrows(NullPointerException.class, () -> matchingService.normalizeIdentifier(null, "value"));
    }

    private void assertNormalizedName(String raw, String expected) {
        assertEquals(Optional.of(expected), matchingService.normalizeName(raw));
    }

    private void assertEmptyName(String raw) {
        assertTrue(matchingService.normalizeName(raw).isEmpty());
    }

    private void assertIdentifier(IdentityKind kind, String raw, String expected) {
        assertEquals(Optional.of(expected), matchingService.normalizeIdentifier(kind, raw));
    }

    private void assertEmpty(IdentityKind kind, String raw) {
        assertTrue(matchingService.normalizeIdentifier(kind, raw).isEmpty());
    }
}
