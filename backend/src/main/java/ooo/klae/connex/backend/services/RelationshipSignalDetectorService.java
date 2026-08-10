package ooo.klae.connex.backend.services;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import tools.jackson.databind.ObjectMapper;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.RelationshipSignal;
import ooo.klae.connex.backend.dto.DealRiskDto;
import ooo.klae.connex.backend.dto.DealRiskFactor;
import ooo.klae.connex.backend.dto.RadarResponseDto;
import ooo.klae.connex.backend.dto.RelationshipTemperatureDto;
import ooo.klae.connex.backend.dto.WarmPathBridgeDto;
import ooo.klae.connex.backend.dto.WarmPathDto;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;

/** Converts existing deterministic relationship outputs into canonical signal candidates. */
@Service
@RequiredArgsConstructor
public class RelationshipSignalDetectorService {
    public static final String RELATIONSHIP_DECAY = "relationship_decay";
    public static final String DEAL_RISK = "deal_risk";
    public static final String WARM_PATH = "warm_path";

    static final int DECAY_CAP = 30;
    static final int DEAL_RISK_CAP = 20;
    static final int WARM_PATH_CAP = 10;

    private static final String RANK_RULE =
        "priority_then_source_strength_then_subject";
    private static final DateTimeFormatter MYSQL_DATETIME =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ScoringService scoringService;
    private final DealRiskService dealRiskService;
    private final WarmPathService warmPathService;
    private final PersonMapper personMapper;
    private final CompanyMapper companyMapper;
    private final DealMapper dealMapper;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /** Detects cooling person and company relationships from the canonical warmth output. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Detection detectDecay(int workspaceId, String generationToken) {
        ScoringService.WorkspaceScores scores = scoringService.scoreWorkspace(workspaceId);
        Map<Integer, String> contactSourceStateHashes =
            scoringService.contactSourceStateHashes(
                workspaceId, Set.of(), Set.of(), Set.of());
        Map<Integer, String> companySourceStateHashes =
            scoringService.companySourceStateHashes(workspaceId);
        List<TemperatureCandidate> ranked = new ArrayList<>();
        for (RelationshipTemperatureDto temperature : scores.contacts()) {
            if ("cooling".equals(temperature.getTrend())) {
                ranked.add(new TemperatureCandidate("person", temperature));
            }
        }
        for (RelationshipTemperatureDto temperature : scores.companies()) {
            if ("cooling".equals(temperature.getTrend())) {
                ranked.add(new TemperatureCandidate("company", temperature));
            }
        }
        ranked.sort(Comparator
            .comparingInt((TemperatureCandidate candidate) ->
                candidate.temperature().getDaysSinceTouch() == null
                    ? Integer.MIN_VALUE
                    : candidate.temperature().getDaysSinceTouch())
            .reversed()
            .thenComparing(TemperatureCandidate::subjectType)
            .thenComparingInt(candidate -> candidate.temperature().getId()));
        List<TemperatureCandidate> selected = ranked.stream().limit(DECAY_CAP).toList();
        Map<Integer, Person> people = new LinkedHashMap<>();
        List<Integer> personIds = selected.stream()
            .filter(candidate -> "person".equals(candidate.subjectType()))
            .map(candidate -> candidate.temperature().getId())
            .toList();
        if (!personIds.isEmpty()) {
            personMapper.getByIds(workspaceId, personIds)
                .forEach(person -> people.put(person.getId(), person));
        }
        Map<Integer, Company> companies = new LinkedHashMap<>();
        List<Integer> companyIds = selected.stream()
            .filter(candidate -> "company".equals(candidate.subjectType()))
            .map(candidate -> candidate.temperature().getId())
            .toList();
        if (!companyIds.isEmpty()) {
            companyMapper.getByIds(workspaceId, companyIds)
                .forEach(company -> companies.put(company.getId(), company));
        }
        List<RelationshipSignal> candidates = new ArrayList<>();
        for (TemperatureCandidate candidate : selected) {
            int subjectId = candidate.temperature().getId();
            String subjectLabel = "person".equals(candidate.subjectType())
                ? personLabel(people.get(subjectId), subjectId)
                : companyLabel(companies.get(subjectId), subjectId);
            if (subjectLabel != null) {
                candidates.add(decaySignal(
                    workspaceId,
                    candidate.subjectType(),
                    subjectLabel,
                    candidate.temperature(),
                    "person".equals(candidate.subjectType())
                        ? contactSourceStateHashes.getOrDefault(
                            subjectId, ScoringService.emptyContactSourceStateHash())
                        : companySourceStateHashes.getOrDefault(
                            subjectId, ScoringService.emptyContactSourceStateHash()),
                    generationToken));
            }
        }
        return new Detection(
            candidates, evidenceAsOf(scores), generationToken);
    }

    /** Detects high and medium deal risks from the canonical risk assessment output. */
    public Detection detectDealRisk(int workspaceId, String generationToken) {
        ScoringService.WorkspaceScores scores = scoringService.scoreWorkspace(workspaceId);
        Map<Integer, RelationshipTemperatureDto> warmth = new LinkedHashMap<>();
        scores.contacts().forEach(score -> warmth.put(score.getId(), score));
        List<RiskCandidate> ranked = new ArrayList<>();
        Instant detectorAsOf = evidenceAsOf(scores);
        for (DealRiskService.NotificationRiskState state
                : dealRiskService.assessWorkspaceNotificationStates(
                    workspaceId,
                    warmth,
                    scoringService.contactSourceStateHashes(
                        workspaceId, Set.of(), Set.of(), Set.of()))) {
            DealRiskDto risk = state.assessment();
            if (!("high".equals(risk.getLevel()) || "medium".equals(risk.getLevel()))) {
                continue;
            }
            ranked.add(new RiskCandidate(risk, state.sourceStateHash()));
        }
        ranked.sort(Comparator
            .comparingInt((RiskCandidate candidate) ->
                "high".equals(candidate.risk().getLevel()) ? 0 : 1)
            .thenComparing(Comparator.comparingInt(
                (RiskCandidate candidate) -> candidate.risk().getScore()).reversed())
            .thenComparingInt(candidate -> candidate.risk().getDealId()));
        List<RiskCandidate> selected = ranked.stream().limit(DEAL_RISK_CAP).toList();
        Map<Integer, Deal> deals = new LinkedHashMap<>();
        if (!selected.isEmpty()) {
            dealMapper.getByIds(
                workspaceId,
                selected.stream().map(candidate -> candidate.risk().getDealId()).toList())
                .forEach(deal -> deals.put(deal.getId(), deal));
        }
        List<RelationshipSignal> candidates = new ArrayList<>();
        for (RiskCandidate candidate : selected) {
            DealRiskDto risk = candidate.risk();
            Deal deal = deals.get(risk.getDealId());
            if (deal != null) {
                candidates.add(dealRiskSignal(
                    workspaceId, deal, risk, candidate.sourceStateHash(),
                    riskEvidenceAsOf(risk, detectorAsOf), generationToken));
            }
        }
        return new Detection(
            candidates, detectorAsOf, generationToken);
    }

    /** Detects warm introduction opportunities from the existing warm-path graph output. */
    public Detection detectWarmPaths(
            int workspaceId, String generationToken, Instant evidenceAsOf) {
        List<RelationshipSignal> candidates = warmPathService
            .computePaths(workspaceId, WARM_PATH_CAP)
            .stream()
            .map(path -> warmPathSignal(
                workspaceId, path, evidenceAsOf, generationToken))
            .sorted(signalOrder())
            .limit(WARM_PATH_CAP)
            .toList();
        return new Detection(candidates, evidenceAsOf, generationToken);
    }

    private RelationshipSignal decaySignal(
            int workspaceId,
            String subjectType,
            String subjectLabel,
            RelationshipTemperatureDto temperature,
            String sourceStateHash,
            String generationToken) {
        int recencyRank = temperature.getDaysSinceTouch() == null
            ? Integer.MIN_VALUE
            : temperature.getDaysSinceTouch();
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("band", temperature.getBand());
        parameters.put("trend", temperature.getTrend());
        parameters.put("score", temperature.getScore());
        parameters.put("lastTouchAt", temperature.getLastTouchAt());
        parameters.put("daysSinceTouch", temperature.getDaysSinceTouch());
        parameters.put("goesColdAt", temperature.getGoesColdAt());
        parameters.put("daysUntilCold", temperature.getDaysUntilCold());
        parameters.put("touchCount", temperature.getTouchCount());
        parameters.put("modelVersion", temperature.getModelVersion());
        List<RadarResponseDto.Evidence> evidence = List.of(
            new RadarResponseDto.Evidence(
                "relationship_temperature",
                parameters,
                List.of(new RadarResponseDto.Reference(
                    subjectType, temperature.getId()))));
        List<RadarResponseDto.RankFactor> factors = List.of(
            new RadarResponseDto.RankFactor("priority", "ascending", "cooling"),
            new RadarResponseDto.RankFactor(
                "daysSinceTouch", "descending", temperature.getDaysSinceTouch()),
            new RadarResponseDto.RankFactor(
                "subject", "ascending", subjectType + ":" + temperature.getId()));
        return signal(
            workspaceId, RELATIONSHIP_DECAY, subjectType, temperature.getId(), subjectLabel,
            "cooling", 2, recencyRank, evidence, factors, temperature.getAsOf(),
            sourceStateHash, generationToken);
    }

    private RelationshipSignal dealRiskSignal(
            int workspaceId,
            Deal deal,
            DealRiskDto risk,
            String sourceStateHash,
            Instant evidenceAsOf,
            String generationToken) {
        List<RadarResponseDto.Evidence> evidence = new ArrayList<>();
        for (DealRiskFactor factor : risk.getFactors()) {
            Map<String, Object> parameters = new LinkedHashMap<>(factor.getParams());
            parameters.remove("person");
            parameters.put("severity", factor.getSeverity());
            List<RadarResponseDto.Reference> references = new ArrayList<>();
            references.add(new RadarResponseDto.Reference("deal", deal.getId()));
            Object personId = parameters.get("personId");
            if (personId instanceof Number number) {
                references.add(new RadarResponseDto.Reference("person", number.intValue()));
            }
            evidence.add(new RadarResponseDto.Evidence(
                factor.getCode(), parameters, List.copyOf(references)));
        }
        int priorityRank = "high".equals(risk.getLevel()) ? 0 : 1;
        List<RadarResponseDto.RankFactor> factors = List.of(
            new RadarResponseDto.RankFactor("priority", "ascending", risk.getLevel()),
            new RadarResponseDto.RankFactor("riskScore", "descending", risk.getScore()),
            new RadarResponseDto.RankFactor("subject", "ascending", "deal:" + deal.getId()));
        return signal(
            workspaceId, DEAL_RISK, "deal", deal.getId(), label(deal.getName(), deal.getId()),
            risk.getLevel(), priorityRank, risk.getScore(), evidence, factors, evidenceAsOf,
            sourceStateHash, generationToken);
    }

    private RelationshipSignal warmPathSignal(
            int workspaceId,
            WarmPathDto path,
            Instant evidenceAsOf,
            String generationToken) {
        List<RadarResponseDto.Evidence> evidence = new ArrayList<>();
        for (WarmPathBridgeDto bridge : path.getBridges()) {
            Map<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("bridgePersonId", bridge.getPersonId());
            parameters.put("bridgeName", bridge.getName());
            parameters.put("evidenceType", bridge.getEvidenceType());
            parameters.put("reachType", path.getReachType());
            parameters.put("evidenceCompany", bridge.getEvidenceCompany());
            parameters.put("overlapStartYear", bridge.getOverlapStartYear());
            parameters.put("overlapEndYear", bridge.getOverlapEndYear());
            parameters.put("pathScore", bridge.getScore());
            List<RadarResponseDto.Reference> references = new ArrayList<>();
            references.add(new RadarResponseDto.Reference("person", path.getTargetId()));
            references.add(new RadarResponseDto.Reference("person", bridge.getPersonId()));
            bridge.getSupportingPersonIds().forEach(id -> references.add(
                new RadarResponseDto.Reference("person", id)));
            bridge.getSupportingEdgeIds().forEach(id -> references.add(
                new RadarResponseDto.Reference("person_edge", id)));
            evidence.add(new RadarResponseDto.Evidence(
                "warm_path", parameters, List.copyOf(references)));
        }
        List<RadarResponseDto.RankFactor> factors = List.of(
            new RadarResponseDto.RankFactor("priority", "ascending", "opportunity"),
            new RadarResponseDto.RankFactor("pathScore", "descending", path.getScore()),
            new RadarResponseDto.RankFactor("subject", "ascending", "person:" + path.getTargetId()));
        return signal(
            workspaceId, WARM_PATH, "person", path.getTargetId(),
            label(path.getTargetName(), path.getTargetId()),
            "opportunity", 3, path.getScore(), evidence, factors, evidenceAsOf,
            sourceFingerprint(
                WARM_PATH,
                "person",
                path.getTargetId(),
                "opportunity",
                path.getScore(),
                evidence),
            generationToken);
    }

    private RelationshipSignal signal(
            int workspaceId,
            String family,
            String subjectType,
            int subjectId,
            String subjectLabel,
            String priority,
            int priorityRank,
            int rankValue,
            List<RadarResponseDto.Evidence> evidence,
            List<RadarResponseDto.RankFactor> factors,
            Instant evidenceAsOf,
            String sourceStateHash,
            String generationToken) {
        RelationshipSignal signal = new RelationshipSignal();
        signal.setWorkspaceId(workspaceId);
        signal.setFamily(family);
        signal.setSubjectType(subjectType);
        signal.setSubjectId(subjectId);
        signal.setSubjectLabel(subjectLabel);
        signal.setPriority(priority);
        signal.setPriorityRank(priorityRank);
        signal.setRankValue(rankValue);
        signal.setDedupeKey(family + ":" + subjectType + ":" + subjectId);
        signal.setEvidenceJson(objectMapper.writeValueAsString(evidence));
        signal.setRankExplanationJson(objectMapper.writeValueAsString(
            Map.of("rule", RANK_RULE, "factors", factors)));
        signal.setEvidenceAsOf(LocalDateTime.ofInstant(evidenceAsOf, ZoneOffset.UTC));
        signal.setSourceStateHash(sourceStateHash);
        signal.setGenerationToken(generationToken);
        return signal;
    }

    private Instant evidenceAsOf(ScoringService.WorkspaceScores scores) {
        if (!scores.contacts().isEmpty()) {
            return scores.contacts().getFirst().getAsOf();
        }
        if (!scores.companies().isEmpty()) {
            return scores.companies().getFirst().getAsOf();
        }
        return clock.instant();
    }

    private static Instant riskEvidenceAsOf(DealRiskDto risk, Instant fallback) {
        try {
            return LocalDateTime.parse(risk.getAssessedAt(), MYSQL_DATETIME)
                .toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException | NullPointerException exception) {
            return fallback;
        }
    }

    private static Comparator<RelationshipSignal> signalOrder() {
        return Comparator.comparingInt(RelationshipSignal::getPriorityRank)
            .thenComparing(Comparator.comparingInt(RelationshipSignal::getRankValue).reversed())
            .thenComparing(RelationshipSignal::getFamily)
            .thenComparing(RelationshipSignal::getSubjectType)
            .thenComparingInt(RelationshipSignal::getSubjectId);
    }

    private static String label(String value, int id) {
        return value == null || value.isBlank() ? "#" + id : value.trim();
    }

    private static String personLabel(Person person, int id) {
        return person == null ? null : label(person.getName(), id);
    }

    private static String companyLabel(Company company, int id) {
        return company == null ? null : label(company.getName(), id);
    }

    private String sourceFingerprint(
            String family,
            String subjectType,
            int subjectId,
            String priority,
            int rankValue,
            List<RadarResponseDto.Evidence> evidence) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("family", family);
        state.put("subjectType", subjectType);
        state.put("subjectId", subjectId);
        state.put("priority", priority);
        state.put("rankValue", rankValue);
        state.put("evidence", evidence);
        return hash(objectMapper.writeValueAsString(state));
    }

    private static String hash(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    /** One bounded detector result with a shared evidence reference time. */
    public record Detection(
            List<RelationshipSignal> candidates,
            Instant evidenceAsOf,
            String generationToken) {
        public Detection {
            candidates = List.copyOf(candidates);
        }
    }

    private record TemperatureCandidate(
            String subjectType, RelationshipTemperatureDto temperature) {
    }

    private record RiskCandidate(DealRiskDto risk, String sourceStateHash) {
    }
}
