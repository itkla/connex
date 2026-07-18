package ooo.klae.connex.backend.ai.report;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;

import ooo.klae.connex.backend.ai.AiRawOutputGuard;
import ooo.klae.connex.backend.dto.ReportAppendixRowDto;

/**
 * Grounds a model-authored report narrative. The model writes prose but never types a figure: it
 * cites deterministic sources and references figures only through {@code {{num:...}}} placeholders.
 * The pre-demask guard rejects any literal digit the model typed; the resolver fills each placeholder
 * with its exact locale-formatted value, drops claims that are not fully grounded, and fails closed
 * when the surviving structure is not viable.
 */
final class AiReportProseResolver {
    static final int MAX_SECTIONS = 3;
    static final int MAX_CLAIMS_PER_SECTION = 12;
    static final int MAX_FINDINGS = 12;
    static final int MAX_TITLE_CHARS = 160;
    static final int MAX_CLAIM_CHARS = 1600;

    private static final Pattern TOKEN = Pattern.compile("\\{\\{\\s*([^}]*?)\\s*}}");
    private static final Pattern DIGIT = Pattern.compile("\\p{Nd}");
    private static final String UNKNOWN_REFERENCE = "[unknown reference]";

    private AiReportProseResolver() {
    }

    /**
     * A pre-demask guard that rejects any narrative whose prose contains a literal figure. Every
     * {@code text} field must express figures through placeholders only; this runs before demasking
     * so a demasked entity name that legitimately contains digits cannot trip it.
     * @return guard over the raw masked output
     */
    static AiRawOutputGuard noLiteralFigures() {
        return AiReportProseResolver::proseHasNoLiteralDigit;
    }

    private static boolean proseHasNoLiteralDigit(JsonNode maskedOutput) {
        for (JsonNode text : maskedOutput.findValues("text")) {
            if (text.isString() && DIGIT.matcher(stripTokens(text.asString())).find()) {
                return false;
            }
        }
        return true;
    }

    static Optional<AiReportNarrativeContent> resolve(
            AiReportNarrativeContent content, AiReportContext context, AiReportFigures figures) {
        if (content == null || content.sections() == null || content.findings() == null) {
            return Optional.empty();
        }
        Map<String, ReportAppendixRowDto> sources = new LinkedHashMap<>();
        for (ReportAppendixRowDto source : context.sources()) {
            sources.put(source.sourceId(), source);
        }
        Set<String> titles = titleLookup();
        int[] tally = {0, 0};

        List<AiReportNarrativeContent.Section> sections = new ArrayList<>();
        for (AiReportNarrativeContent.Section section : bounded(content.sections(), MAX_SECTIONS)) {
            resolveSection(section, sources.keySet(), titles, figures, tally).ifPresent(sections::add);
        }
        List<AiReportNarrativeContent.Claim> findings = resolveClaims(
                bounded(content.findings(), MAX_FINDINGS), sources.keySet(), figures, tally);

        if (sections.isEmpty() || findings.isEmpty()) {
            return Optional.empty();
        }
        int emitted = tally[0];
        int dropped = tally[1];
        if (emitted == 0 || dropped * 2 > emitted) {
            return Optional.empty();
        }
        return Optional.of(new AiReportNarrativeContent(List.copyOf(sections), List.copyOf(findings)));
    }

    private static Optional<AiReportNarrativeContent.Section> resolveSection(
            AiReportNarrativeContent.Section section,
            Set<String> sourceIds,
            Set<String> titles,
            AiReportFigures figures,
            int[] tally) {
        if (section == null || section.title() == null) {
            return Optional.empty();
        }
        String canonicalTitle = titles.contains(normalize(section.title())) ? section.title().strip() : null;
        if (canonicalTitle == null) {
            return Optional.empty();
        }
        List<AiReportNarrativeContent.Claim> claims = resolveClaims(
                bounded(section.claims(), MAX_CLAIMS_PER_SECTION), sourceIds, figures, tally);
        if (claims.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new AiReportNarrativeContent.Section(
                truncate(canonicalTitle, MAX_TITLE_CHARS), claims));
    }

    private static List<AiReportNarrativeContent.Claim> resolveClaims(
            List<AiReportNarrativeContent.Claim> claims,
            Set<String> sourceIds,
            AiReportFigures figures,
            int[] tally) {
        if (claims == null) {
            return List.of();
        }
        List<AiReportNarrativeContent.Claim> resolved = new ArrayList<>();
        for (AiReportNarrativeContent.Claim claim : claims) {
            tally[0]++;
            AiReportNarrativeContent.Claim grounded = resolveClaim(claim, sourceIds, figures);
            if (grounded == null) {
                tally[1]++;
                continue;
            }
            resolved.add(grounded);
        }
        return resolved;
    }

    private static AiReportNarrativeContent.Claim resolveClaim(
            AiReportNarrativeContent.Claim claim, Set<String> sourceIds, AiReportFigures figures) {
        if (claim == null || claim.text() == null || claim.text().isBlank()
                || claim.sourceIds() == null || claim.sourceIds().isEmpty()) {
            return null;
        }
        List<String> citations = claim.sourceIds().stream()
                .filter(sourceIds::contains)
                .distinct()
                .toList();
        if (citations.isEmpty() || claim.text().contains(UNKNOWN_REFERENCE)) {
            return null;
        }
        String text = fillTokens(claim.text().strip(), figures);
        if (text == null || text.contains("{{")) {
            return null;
        }
        return new AiReportNarrativeContent.Claim(truncate(text, MAX_CLAIM_CHARS), citations);
    }

    private static String fillTokens(String text, AiReportFigures figures) {
        Matcher matcher = TOKEN.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String token = matcher.group(1);
            if (!figures.has(token)) {
                return null;
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(figures.resolve(token)));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String stripTokens(String text) {
        return TOKEN.matcher(text).replaceAll(" ");
    }

    private static Set<String> titleLookup() {
        Set<String> lookup = new LinkedHashSet<>();
        for (String title : AiReportFacts.titles()) {
            lookup.add(normalize(title));
        }
        return lookup;
    }

    private static <T> List<T> bounded(List<T> values, int max) {
        if (values == null) {
            return List.of();
        }
        return values.size() <= max ? values : values.subList(0, max);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
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
