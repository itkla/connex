package ooo.klae.connex.backend.ai.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;

import ooo.klae.connex.backend.dto.ReportAppendixRowDto;

class AiReportFactsTest {

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void employmentMeasuresHaveEnglishAndJapaneseLabels() {
        ReportAppendixRowDto departure = source("employment_departure_count");
        ReportAppendixRowDto arrival = source("employment_arrival_count");

        LocaleContextHolder.setLocale(Locale.ENGLISH);
        assertEquals("Employment departures", AiReportFacts.measureLabel(departure));
        assertEquals("Employment arrivals", AiReportFacts.measureLabel(arrival));

        LocaleContextHolder.setLocale(Locale.JAPANESE);
        assertEquals("勤務先からの離職数", AiReportFacts.measureLabel(departure));
        assertEquals("勤務先への入社数", AiReportFacts.measureLabel(arrival));
    }

    /**
     * Without a static label the assembler masks the measure name as a tenant company token, so a
     * grounded narrative would describe the metric as {@code COMPANY_n}.
     */
    @Test
    void commercialMeasureLabelsResolveInBothLocales() {
        Map<String, String> english = Map.of(
                "quote_count", "Quote volume",
                "quote_issue_rate", "Quote issue rate",
                "document_to_win_rate", "Document-to-win rate",
                "approval_decision_count", "Approval decisions",
                "approval_cycle_days", "Approval cycle days",
                "effective_discount_percent", "Effective discount (won)",
                "open_discount_percent", "Effective discount (open)");
        Map<String, String> japanese = Map.of(
                "quote_count", "見積作成数",
                "quote_issue_rate", "見積確定率",
                "document_to_win_rate", "書類からの受注率",
                "approval_decision_count", "承認決裁数",
                "approval_cycle_days", "承認所要日数",
                "effective_discount_percent", "実効値引率（受注）",
                "open_discount_percent", "実効値引率（進行中）");

        LocaleContextHolder.setLocale(Locale.ENGLISH);
        for (Map.Entry<String, String> entry : english.entrySet()) {
            ReportAppendixRowDto row = source(entry.getKey());
            assertTrue(AiReportFacts.hasStaticMeasureLabel(row), entry.getKey());
            assertEquals(entry.getValue(), AiReportFacts.measureLabel(row));
        }

        LocaleContextHolder.setLocale(Locale.JAPANESE);
        for (Map.Entry<String, String> entry : japanese.entrySet()) {
            ReportAppendixRowDto row = source(entry.getKey());
            assertTrue(AiReportFacts.hasStaticMeasureLabel(row), entry.getKey());
            assertEquals(entry.getValue(), AiReportFacts.measureLabel(row));
        }
    }

    private static ReportAppendixRowDto source(String measure) {
        return new ReportAppendixRowDto(
                "metric.0.0", "employment", measure + " · Total",
                BigDecimal.ONE, BigDecimal.ZERO, "count");
    }
}
