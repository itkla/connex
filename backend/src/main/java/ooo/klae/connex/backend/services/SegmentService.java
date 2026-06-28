package ooo.klae.connex.backend.services;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.PersonEdge;
import ooo.klae.connex.backend.dto.RelationshipTemperatureDto;
import ooo.klae.connex.backend.dto.SegmentSelection;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.mappers.PersonEdgeMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.SegmentMapper;

/**
 * Evaluates graph-aware smart-segment predicates to the ids of matching records, scoped to the
 * active workspace and the current user. Multiple predicates are combined with AND (intersection).
 *
 * <p>v1 supports the {@code company} record type with four predicates: {@code warm_intro_available}
 * (a contact at the company is strongly connected to someone the team has engaged, but the current
 * user has no activity with the company), {@code open_deal}, {@code cooling} (relationship
 * temperature cool/cold or cooling), and {@code no_activity} (no activity within a window). SQL
 * predicates run via {@link SegmentMapper}; the temperature predicate reuses {@link ScoringService}.
 */
@Service
@RequiredArgsConstructor
public class SegmentService {

    private final WorkspaceService workspaceService;
    private final AuthService authService;
    private final ScoringService scoringService;
    private final SegmentMapper segmentMapper;
    private final PersonEdgeMapper edgeMapper;
    private final PersonMapper personMapper;

    private static final int DEFAULT_DAYS = 30;
    private static final int MAX_DAYS = 3650;
    private static final int STRONG_EDGE = 2;
    private static final Set<String> COMPANY_KEYS =
        Set.of("warm_intro_available", "open_deal", "cooling", "no_activity");

    /**
     * Returns the ids of records matching ALL of the given segment predicates.
     */
    public List<Integer> evaluate(String recordType, List<SegmentSelection> segments) {
        if (!"company".equals(normalize(recordType))) {
            throw new BadRequestException("Smart segments are not available for record type: " + recordType);
        }
        if (segments == null || segments.isEmpty()) {
            return List.of();
        }
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int userId = authService.getCurrentUser().getId();

        Set<Integer> result = null;
        for (SegmentSelection selection : segments) {
            Set<Integer> matched = evaluateCompanySegment(selection, workspaceId, userId);
            if (result == null) {
                result = matched;
            } else {
                result.retainAll(matched);
            }
            if (result.isEmpty()) {
                return List.of();
            }
        }
        return result == null ? List.of() : new ArrayList<>(result);
    }

    private Set<Integer> evaluateCompanySegment(SegmentSelection selection, int workspaceId, int userId) {
        String key = normalize(selection.getKey());
        if (key == null || !COMPANY_KEYS.contains(key)) {
            throw new BadRequestException("Unknown segment: " + selection.getKey());
        }
        return switch (key) {
            case "open_deal" -> new HashSet<>(segmentMapper.companyIdsWithOpenDeal(workspaceId));
            case "no_activity" -> new HashSet<>(segmentMapper.companyIdsNoActivitySince(workspaceId, resolveDays(selection.getDays())));
            case "cooling" -> coolingCompanyIds(workspaceId);
            case "warm_intro_available" -> warmIntroCompanyIds(workspaceId, userId);
            default -> throw new BadRequestException("Unknown segment: " + selection.getKey());
        };
    }

    private Set<Integer> coolingCompanyIds(int workspaceId) {
        Set<Integer> ids = new HashSet<>();
        for (RelationshipTemperatureDto temperature : scoringService.scoreCompanies(workspaceId)) {
            if ("cool".equals(temperature.getBand())
                    || "cold".equals(temperature.getBand())
                    || "cooling".equals(temperature.getTrend())) {
                ids.add(temperature.getId());
            }
        }
        return ids;
    }

    private Set<Integer> warmIntroCompanyIds(int workspaceId, int userId) {
        Set<Integer> engaged = new HashSet<>(personMapper.getEngagedPersonIds(workspaceId));
        if (engaged.isEmpty()) {
            return new HashSet<>();
        }
        Set<Integer> warmlyConnected = new HashSet<>();
        for (PersonEdge edge : edgeMapper.getAllEdges(workspaceId)) {
            if (edge.getStrength() < STRONG_EDGE) {
                continue;
            }
            if (engaged.contains(edge.getSourcePersonId())) {
                warmlyConnected.add(edge.getTargetPersonId());
            }
            if (engaged.contains(edge.getTargetPersonId())) {
                warmlyConnected.add(edge.getSourcePersonId());
            }
        }
        if (warmlyConnected.isEmpty()) {
            return new HashSet<>();
        }
        return new HashSet<>(segmentMapper.companyIdsForPersonsWithoutUserActivity(
            workspaceId, userId, new ArrayList<>(warmlyConnected)));
    }

    private int resolveDays(Integer days) {
        if (days == null) {
            return DEFAULT_DAYS;
        }
        return Math.min(Math.max(days, 1), MAX_DAYS);
    }

    private static String normalize(String value) {
        return value == null ? null : value.trim().toLowerCase();
    }
}
