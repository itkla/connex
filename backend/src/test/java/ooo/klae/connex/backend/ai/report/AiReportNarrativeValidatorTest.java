package ooo.klae.connex.backend.ai.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;

import ooo.klae.connex.backend.dto.ReportAppendixRowDto;

class AiReportNarrativeValidatorTest {
    private static final String REVENUE_SOURCE = "revenue.total";
    private static final String DEAL_SOURCE = "deals.won";

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void validate_exactSupportedClaim_returnsContent() {
        AiReportNarrativeContent content = content(supportedClaim(REVENUE_SOURCE));

        Optional<AiReportNarrativeContent> result =
                AiReportNarrativeValidator.validate(content, context());

        assertTrue(result.isPresent());
        assertEquals(List.of(REVENUE_SOURCE),
                result.get().sections().getFirst().claims().getFirst().sourceIds());
    }

    @Test
    void validate_serverCuratedRecommendation_returnsContent() {
        String recommendation = AiReportFacts.claims(source(REVENUE_SOURCE)).get(1);
        AiReportNarrativeContent content = content(
                new AiReportNarrativeContent.Claim(recommendation, List.of(REVENUE_SOURCE)));

        assertTrue(AiReportNarrativeValidator.validate(content, context()).isPresent());
    }

    @Test
    void relationshipHealthFacts_areLocalizedAndAccepted() {
        ReportAppendixRowDto source = new ReportAppendixRowDto(
                "health.coverage", "coverage", "coverage_gap_count · Cooling",
                new BigDecimal("3"), new BigDecimal("2"), "count");
        String claim = AiReportFacts.claim(source);
        String recommendation = AiReportFacts.claims(source).get(1);
        AiReportContext context = new AiReportContext(
                "Relationship health", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31),
                List.of(source));
        AiReportNarrativeContent content = new AiReportNarrativeContent(
                List.of(new AiReportNarrativeContent.Section(
                        "Executive summary",
                        List.of(new AiReportNarrativeContent.Claim(
                                claim, List.of(source.sourceId()))))),
                List.of(new AiReportNarrativeContent.Claim(
                        recommendation, List.of(source.sourceId()))));

        assertTrue(claim.startsWith("Coverage gap count · Cooling"));
        assertTrue(AiReportNarrativeValidator.validate(content, context).isPresent());

        LocaleContextHolder.setLocale(Locale.JAPANESE);
        String japaneseClaim = AiReportFacts.claim(source);
        AiReportNarrativeContent japaneseContent = new AiReportNarrativeContent(
                List.of(new AiReportNarrativeContent.Section(
                        "エグゼクティブサマリー",
                        List.of(new AiReportNarrativeContent.Claim(
                                japaneseClaim, List.of(source.sourceId()))))),
                List.of(new AiReportNarrativeContent.Claim(
                        AiReportFacts.claims(source).get(1), List.of(source.sourceId()))));

        assertTrue(japaneseClaim.startsWith("カバレッジ不足の会社数 · 低下"));
        assertTrue(AiReportNarrativeValidator.validate(japaneseContent, context).isPresent());
    }

    @Test
    void forecastingFacts_areLocalizedAndAccepted() {
        ReportAppendixRowDto source = new ReportAppendixRowDto(
                "forecast.weighted", "forecast", "forecast_weighted · USD · 2026-09",
                new BigDecimal("125000"), null, "USD");
        String claim = AiReportFacts.claim(source);
        String recommendation = AiReportFacts.claims(source).get(1);
        AiReportContext context = new AiReportContext(
                "Forecasting", LocalDate.of(2026, 7, 1), LocalDate.of(2026, 9, 30), List.of(source));
        AiReportNarrativeContent content = new AiReportNarrativeContent(
                List.of(new AiReportNarrativeContent.Section(
                        "Executive summary",
                        List.of(new AiReportNarrativeContent.Claim(claim, List.of(source.sourceId()))))),
                List.of(new AiReportNarrativeContent.Claim(recommendation, List.of(source.sourceId()))));

        assertEquals(
                "Likely forecast (weighted) · USD · 2026-09 is a deterministic forward forecast.",
                claim);
        assertTrue(recommendation.contains("weighted forecast at risk"));
        assertEquals("Best-case forecast · Total", AiReportFacts.label(new ReportAppendixRowDto(
                "forecast.best", "forecast", "forecast_best · Total",
                new BigDecimal("250000"), null, "USD")));
        assertEquals("Commit forecast · Total", AiReportFacts.label(new ReportAppendixRowDto(
                "forecast.worst", "forecast", "forecast_worst · Total",
                new BigDecimal("75000"), null, "USD")));
        assertTrue(AiReportNarrativeValidator.validate(content, context).isPresent());

        LocaleContextHolder.setLocale(Locale.JAPANESE);
        String japaneseClaim = AiReportFacts.claim(source);
        String japaneseRecommendation = AiReportFacts.claims(source).get(1);
        AiReportNarrativeContent japaneseContent = new AiReportNarrativeContent(
                List.of(new AiReportNarrativeContent.Section(
                        "エグゼクティブサマリー",
                        List.of(new AiReportNarrativeContent.Claim(
                                japaneseClaim, List.of(source.sourceId()))))),
                List.of(new AiReportNarrativeContent.Claim(
                        japaneseRecommendation, List.of(source.sourceId()))));

        assertEquals("見込み予測（加重） · USD · 2026-09は確定的に計算された将来予測です。", japaneseClaim);
        assertTrue(japaneseRecommendation.contains("加重予測"));
        assertEquals("最良ケース予測 · 合計", AiReportFacts.label(new ReportAppendixRowDto(
                "forecast.best", "forecast", "forecast_best · Total",
                new BigDecimal("250000"), null, "USD")));
        assertEquals("コミット予測 · 合計", AiReportFacts.label(new ReportAppendixRowDto(
                "forecast.worst", "forecast", "forecast_worst · Total",
                new BigDecimal("75000"), null, "USD")));
        assertTrue(AiReportNarrativeValidator.validate(japaneseContent, context).isPresent());
    }

    @Test
    void validate_unknownCitation_failsClosed() {
        AiReportNarrativeContent content = content(
                new AiReportNarrativeContent.Claim("Unsupported claim.", List.of("unknown.source")));

        assertTrue(AiReportNarrativeValidator.validate(content, context()).isEmpty());
    }

    @Test
    void validate_multipleCitations_failsClosed() {
        AiReportNarrativeContent content = content(new AiReportNarrativeContent.Claim(
                AiReportFacts.claim(source(REVENUE_SOURCE)), List.of(REVENUE_SOURCE, DEAL_SOURCE)));

        assertTrue(AiReportNarrativeValidator.validate(content, context()).isEmpty());
    }

    @Test
    void validate_semanticallyUnsupportedClaim_failsClosed() {
        AiReportNarrativeContent content = content(new AiReportNarrativeContent.Claim(
                "Pipeline declined sharply.", List.of(REVENUE_SOURCE)));

        assertTrue(AiReportNarrativeValidator.validate(content, context()).isEmpty());
    }

    @Test
    void validate_fullWidthInventedQuantity_failsClosed() {
        AiReportNarrativeContent content = content(new AiReportNarrativeContent.Claim(
                "Revenue improved by １２３ percent.", List.of(REVENUE_SOURCE)));

        assertTrue(AiReportNarrativeValidator.validate(content, context()).isEmpty());
    }

    @Test
    void validate_japaneseKanjiQuantity_failsClosed() {
        LocaleContextHolder.setLocale(Locale.JAPANESE);
        AiReportNarrativeContent content = new AiReportNarrativeContent(
                List.of(new AiReportNarrativeContent.Section(
                        "エグゼクティブサマリー",
                        List.of(new AiReportNarrativeContent.Claim(
                                "売上高は二倍になりました。", List.of(REVENUE_SOURCE))))),
                List.of(new AiReportNarrativeContent.Claim(
                        AiReportFacts.claim(source(REVENUE_SOURCE)), List.of(REVENUE_SOURCE))));

        assertTrue(AiReportNarrativeValidator.validate(content, context()).isEmpty());
    }

    @Test
    void validate_unsupportedSectionTitle_failsClosed() {
        AiReportNarrativeContent content = new AiReportNarrativeContent(
                List.of(new AiReportNarrativeContent.Section(
                        "Declining pipeline",
                        List.of(supportedClaim(REVENUE_SOURCE)))),
                List.of(supportedClaim(REVENUE_SOURCE)));

        assertTrue(AiReportNarrativeValidator.validate(content, context()).isEmpty());
    }

    private static AiReportNarrativeContent content(AiReportNarrativeContent.Claim sectionClaim) {
        return new AiReportNarrativeContent(
                List.of(new AiReportNarrativeContent.Section("Executive summary", List.of(sectionClaim))),
                List.of(supportedClaim(REVENUE_SOURCE)));
    }

    private static AiReportNarrativeContent.Claim supportedClaim(String sourceId) {
        return new AiReportNarrativeContent.Claim(
                AiReportFacts.claim(source(sourceId)), List.of(sourceId));
    }

    private static AiReportContext context() {
        return new AiReportContext(
                "Monthly sales review",
                LocalDate.of(2026, 6, 1),
                LocalDate.of(2026, 6, 30),
                List.of(source(REVENUE_SOURCE), source(DEAL_SOURCE)));
    }

    private static ReportAppendixRowDto source(String sourceId) {
        if (REVENUE_SOURCE.equals(sourceId)) {
            return new ReportAppendixRowDto(
                    sourceId, "revenue", "Total revenue", new BigDecimal("1000000"),
                    new BigDecimal("950000"), "USD");
        }
        return new ReportAppendixRowDto(
                sourceId, "deals", "Deals won", new BigDecimal("12"), new BigDecimal("10"), "count");
    }
}
