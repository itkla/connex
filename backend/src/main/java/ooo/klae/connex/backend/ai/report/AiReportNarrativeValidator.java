package ooo.klae.connex.backend.ai.report;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import ooo.klae.connex.backend.dto.ReportAppendixRowDto;

/**
 * Fail-closed re-validation of a stored report narrative on the cache-read path. The resolver already
 * guarantees grounded, fully-resolved content at write time; this guards DB-resident payloads and
 * drift — a cached narrative whose titles or cited sources no longer exist in the current registry is
 * rejected so it regenerates rather than rendering stale claims beside fresh figures.
 */
final class AiReportNarrativeValidator {
    static final int MAX_SECTIONS = 3;
    static final int MAX_CLAIMS_PER_SECTION = 12;
    static final int MAX_FINDINGS = 12;

    private AiReportNarrativeValidator() {
    }

    static Optional<AiReportNarrativeContent> validate(
            AiReportNarrativeContent content, AiReportContext context) {
        if (content == null || content.sections() == null || content.findings() == null
                || content.sections().isEmpty() || content.findings().isEmpty()
                || content.sections().size() > MAX_SECTIONS
                || content.findings().size() > MAX_FINDINGS) {
            return Optional.empty();
        }
        Set<String> sourceIds = new HashSet<>();
        for (ReportAppendixRowDto source : context.sources()) {
            sourceIds.add(source.sourceId());
        }
        Set<String> titles = AiReportFacts.titleSet();
        for (AiReportNarrativeContent.Section section : content.sections()) {
            if (section == null || section.title() == null || !titles.contains(section.title().strip())
                    || !claimsValid(section.claims(), MAX_CLAIMS_PER_SECTION, sourceIds)) {
                return Optional.empty();
            }
        }
        if (!claimsValid(content.findings(), MAX_FINDINGS, sourceIds)) {
            return Optional.empty();
        }
        return Optional.of(content);
    }

    private static boolean claimsValid(
            List<AiReportNarrativeContent.Claim> claims, int maxClaims, Set<String> sourceIds) {
        if (claims == null || claims.isEmpty() || claims.size() > maxClaims) {
            return false;
        }
        for (AiReportNarrativeContent.Claim claim : claims) {
            if (claim == null || claim.text() == null || claim.text().isBlank()
                    || claim.text().contains("{{")
                    || claim.sourceIds() == null || claim.sourceIds().isEmpty()
                    || !sourceIds.containsAll(claim.sourceIds())) {
                return false;
            }
        }
        return true;
    }
}
