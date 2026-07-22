package ooo.klae.connex.backend.ai.report;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import ooo.klae.connex.backend.dto.ReportAppendixRowDto;

/**
 * Locale-formatted figure registry for a report narrative. Each appendix source exposes a small set
 * of {@code {{num:<sourceId>.<field>}}} placeholders (current, prior, delta) that the model cites
 * instead of typing digits; the resolver fills the exact formatted value so no figure in the prose
 * is model-authored.
 */
final class AiReportFigures {
    static final String PREFIX = "num:";

    private final Map<String, String> resolved;
    private final Locale locale;

    private AiReportFigures(Map<String, String> resolved, Locale locale) {
        this.resolved = resolved;
        this.locale = locale;
    }

    static AiReportFigures from(List<ReportAppendixRowDto> sources, Locale locale) {
        Map<String, String> resolved = new LinkedHashMap<>();
        for (ReportAppendixRowDto source : sources) {
            String base = PREFIX + source.sourceId() + '.';
            BigDecimal current = source.value();
            BigDecimal prior = source.priorValue();
            resolved.put(base + "current", number(current, source.unit(), locale));
            if (prior != null) {
                resolved.put(base + "prior", number(prior, source.unit(), locale));
                resolved.put(base + "delta_abs", number(current.subtract(prior), source.unit(), locale));
                if (prior.signum() != 0) {
                    resolved.put(base + "delta_pct", percent(current.subtract(prior), prior.abs(), locale));
                }
            }
        }
        return new AiReportFigures(Map.copyOf(resolved), locale);
    }

    boolean has(String token) {
        return resolved.containsKey(token);
    }

    String resolve(String token) {
        return resolved.get(token);
    }

    /** Placeholder tokens available for one source, e.g. {@code num:metric.1.0.current}. */
    List<String> tokensFor(String sourceId) {
        String base = PREFIX + sourceId + '.';
        return resolved.keySet().stream().filter(key -> key.startsWith(base)).toList();
    }

    Locale locale() {
        return locale;
    }

    private static String number(BigDecimal value, String unit, Locale locale) {
        Optional<Currency> currency = currency(unit);
        NumberFormat format = currency
                .map(c -> currencyFormat(c, value, locale))
                .orElseGet(() -> plainFormat(value, locale));
        return format.format(value);
    }

    private static String percent(BigDecimal delta, BigDecimal priorMagnitude, Locale locale) {
        BigDecimal ratio = delta.divide(priorMagnitude, 6, RoundingMode.HALF_UP);
        NumberFormat format = NumberFormat.getPercentInstance(locale);
        format.setMinimumFractionDigits(1);
        format.setMaximumFractionDigits(1);
        String formatted = format.format(ratio);
        return ratio.signum() > 0 ? '+' + formatted : formatted;
    }

    private static NumberFormat currencyFormat(Currency currency, BigDecimal value, Locale locale) {
        NumberFormat format = NumberFormat.getCurrencyInstance(locale);
        format.setCurrency(currency);
        int fractionDigits = value.stripTrailingZeros().scale() <= 0 ? 0 : currency.getDefaultFractionDigits();
        format.setMinimumFractionDigits(fractionDigits);
        format.setMaximumFractionDigits(Math.max(fractionDigits, currency.getDefaultFractionDigits()));
        return format;
    }

    private static NumberFormat plainFormat(BigDecimal value, Locale locale) {
        NumberFormat format = NumberFormat.getNumberInstance(locale);
        format.setMaximumFractionDigits(value.stripTrailingZeros().scale() <= 0 ? 0 : 2);
        return format;
    }

    private static Optional<Currency> currency(String unit) {
        if (unit == null || unit.length() != 3) {
            return Optional.empty();
        }
        try {
            return Optional.of(Currency.getInstance(unit.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }
}
