package ooo.klae.connex.backend.ai.report;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import ooo.klae.connex.backend.dto.ReportAppendixRowDto;

class AiReportFiguresTest {
    @ParameterizedTest
    @ValueSource(strings = {"en", "ja"})
    void roundsYenCurrentAndPriorHalfUp(String language) {
        Locale locale = Locale.forLanguageTag(language);
        String symbol = Currency.getInstance("JPY").getSymbol(locale);
        AiReportFigures figures = AiReportFigures.from(List.of(
                new ReportAppendixRowDto("yen", "w0", "JPY",
                        new BigDecimal("1000.50"), new BigDecimal("1000.49"), "JPY")), locale);

        assertEquals(symbol + "1,001", figures.resolve("num:yen.current"));
        assertEquals(symbol + "1,000", figures.resolve("num:yen.prior"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"en", "ja"})
    void roundsYenAbsoluteDeltaHalfUp(String language) {
        Locale locale = Locale.forLanguageTag(language);
        String symbol = Currency.getInstance("JPY").getSymbol(locale);
        AiReportFigures figures = AiReportFigures.from(List.of(
                new ReportAppendixRowDto("tie", "w0", "JPY",
                        new BigDecimal("2000.99"), new BigDecimal("1000.49"), "JPY"),
                new ReportAppendixRowDto("below", "w0", "JPY",
                        new BigDecimal("2000.98"), new BigDecimal("1000.49"), "JPY")), locale);

        assertEquals(symbol + "1,001", figures.resolve("num:tie.delta_abs"));
        assertEquals(symbol + "1,000", figures.resolve("num:below.delta_abs"));
    }
}
