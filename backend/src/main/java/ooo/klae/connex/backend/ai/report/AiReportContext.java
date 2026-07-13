package ooo.klae.connex.backend.ai.report;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import ooo.klae.connex.backend.dto.ReportAppendixRowDto;

/**
 * Deterministic, caller-supplied source registry for one report narrative. The context is capped
 * before prompt assembly so a large appendix cannot produce an unbounded provider payload.
 * @param reportName report display name
 * @param periodStart first included date
 * @param periodEnd last included date
 * @param sources ordered deterministic appendix rows available for citation
 */
public record AiReportContext(
        String reportName,
        LocalDate periodStart,
        LocalDate periodEnd,
        List<ReportAppendixRowDto> sources) {

    static final int MAX_SOURCES = 300;
    static final int MAX_REPORT_NAME_CHARS = 128;
    static final int MAX_SOURCE_LABEL_CHARS = 240;
    static final int MAX_UNIT_CHARS = 32;

    private static final Pattern SOURCE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,79}");

    public AiReportContext {
        if (reportName == null || reportName.isBlank()) {
            throw new IllegalArgumentException("reportName is required");
        }
        Objects.requireNonNull(periodStart, "periodStart");
        Objects.requireNonNull(periodEnd, "periodEnd");
        if (periodEnd.isBefore(periodStart)) {
            throw new IllegalArgumentException("periodEnd must not precede periodStart");
        }
        Objects.requireNonNull(sources, "sources");
        if (sources.isEmpty()) {
            throw new IllegalArgumentException("at least one report source is required");
        }
        reportName = truncate(reportName.strip(), MAX_REPORT_NAME_CHARS);
        sources = validateAndCap(sources);
    }

    private static List<ReportAppendixRowDto> validateAndCap(List<ReportAppendixRowDto> sources) {
        if (sources.size() > MAX_SOURCES) {
            throw new IllegalArgumentException("report source registry is too large");
        }
        Set<String> sourceIds = new HashSet<>();
        List<ReportAppendixRowDto> capped = sources.stream()
                .map(AiReportContext::validatedSource)
                .toList();
        for (ReportAppendixRowDto source : capped) {
            if (!sourceIds.add(source.sourceId())) {
                throw new IllegalArgumentException("duplicate report source id");
            }
        }
        return capped;
    }

    private static ReportAppendixRowDto validatedSource(ReportAppendixRowDto source) {
        Objects.requireNonNull(source, "source");
        if (source.sourceId() == null || !SOURCE_ID.matcher(source.sourceId()).matches()) {
            throw new IllegalArgumentException("invalid report source id");
        }
        if (source.label() == null || source.label().isBlank()) {
            throw new IllegalArgumentException("report source label is required");
        }
        Objects.requireNonNull(source.value(), "source.value");
        String widgetId = source.widgetId() == null ? "" : source.widgetId().strip();
        String label = truncate(source.label().strip(), MAX_SOURCE_LABEL_CHARS);
        String unit = source.unit() == null ? "" : truncate(source.unit().strip(), MAX_UNIT_CHARS);
        return new ReportAppendixRowDto(
                source.sourceId(), widgetId, label, source.value(), source.priorValue(), unit);
    }

    private static String truncate(String value, int maxCodePoints) {
        int codePoints = value.codePointCount(0, value.length());
        if (codePoints <= maxCodePoints) {
            return value;
        }
        int end = value.offsetByCodePoints(0, maxCodePoints - 1);
        return value.substring(0, end) + "…";
    }
}
