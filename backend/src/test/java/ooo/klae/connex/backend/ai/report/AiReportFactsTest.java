package ooo.klae.connex.backend.ai.report;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.util.Locale;

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

    private static ReportAppendixRowDto source(String measure) {
        return new ReportAppendixRowDto(
                "metric.0.0", "employment", measure + " · Total",
                BigDecimal.ONE, BigDecimal.ZERO, "count");
    }
}
