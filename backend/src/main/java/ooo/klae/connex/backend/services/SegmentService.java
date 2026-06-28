package ooo.klae.connex.backend.services;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.PersonEdge;
import ooo.klae.connex.backend.dto.RelationshipTemperatureDto;
import ooo.klae.connex.backend.dto.SegmentCondition;
import ooo.klae.connex.backend.dto.SegmentDefinition;
import ooo.klae.connex.backend.dto.SegmentFieldsDto;
import ooo.klae.connex.backend.dto.TagDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.mappers.PersonEdgeMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.SegmentMapper;
import ooo.klae.connex.backend.mappers.TagMapper;
import ooo.klae.connex.backend.util.LikePattern;

/**
 * Evaluates a {@link SegmentDefinition} to the ids of matching records, scoped to the active
 * workspace and the current user. A definition is a set of conditions combined with {@code match}
 * ({@code "all"} = intersection, {@code "any"} = union); each condition is a graph-aware predicate
 * or a field comparison, optionally negated (complemented within the workspace's records). v1
 * supports the {@code company} record type.
 *
 * <p>The condition model is deliberately feature-agnostic so a future rule engine can reuse it as
 * its {@code WHEN}. Predicate keys: {@code warm_intro_available}, {@code open_deal}, {@code cooling},
 * {@code no_activity}. Field conditions: {@code industry} (equals/contains), {@code name} (contains),
 * {@code tag} (has). SQL runs via {@link SegmentMapper}; {@code cooling} reuses {@link ScoringService}.
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
    private final TagMapper tagMapper;

    private static final int DEFAULT_DAYS = 30;
    private static final int MAX_DAYS = 3650;
    private static final int STRONG_EDGE = 2;
    private static final int MAX_CONDITIONS = 16;
    private static final Set<String> PREDICATE_KEYS =
        Set.of("warm_intro_available", "open_deal", "cooling", "no_activity");

    /**
     * Returns the ids of records matching the definition.
     */
    public List<Integer> evaluate(String recordType, SegmentDefinition definition) {
        requireCompany(recordType);
        if (definition == null || definition.getConditions() == null || definition.getConditions().isEmpty()) {
            return List.of();
        }
        if (definition.getConditions().size() > MAX_CONDITIONS) {
            throw new BadRequestException("A segment may have at most " + MAX_CONDITIONS + " conditions");
        }
        String match = normalize(definition.getMatch());
        boolean any = "any".equals(match);
        if (!any && !"all".equals(match)) {
            throw new BadRequestException("Invalid match (expected 'all' or 'any'): " + definition.getMatch());
        }

        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int userId = authService.getCurrentUser().getId();
        Set<Integer> universe = null;

        Set<Integer> result = null;
        Set<String> seen = new HashSet<>();
        for (SegmentCondition condition : definition.getConditions()) {
            if (!seen.add(signature(condition))) {
                continue;
            }
            Set<Integer> matched = evaluateCondition(condition, workspaceId, userId);
            if (condition.isNegate()) {
                if (universe == null) {
                    universe = new HashSet<>(segmentMapper.companyIdsInWorkspace(workspaceId));
                }
                Set<Integer> complement = new HashSet<>(universe);
                complement.removeAll(matched);
                matched = complement;
            }
            if (result == null) {
                result = matched;
            } else if (any) {
                result.addAll(matched);
            } else {
                result.retainAll(matched);
                if (result.isEmpty()) {
                    return List.of();
                }
            }
        }
        return result == null ? List.of() : new ArrayList<>(result);
    }

    /**
     * The field value-options that power the builder: distinct industry values and workspace tags.
     */
    public SegmentFieldsDto fields(String recordType) {
        requireCompany(recordType);
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        List<TagDto> tags = tagMapper.getAllTags(workspaceId).stream().map(TagDto::from).toList();
        return new SegmentFieldsDto(segmentMapper.distinctIndustries(workspaceId), tags);
    }

    private Set<Integer> evaluateCondition(SegmentCondition condition, int workspaceId, int userId) {
        String type = normalize(condition.getType());
        if ("predicate".equals(type)) {
            return evaluatePredicate(condition, workspaceId, userId);
        }
        if ("field".equals(type)) {
            return evaluateField(condition, workspaceId);
        }
        throw new BadRequestException("Unknown condition type (expected 'predicate' or 'field'): " + condition.getType());
    }

    private Set<Integer> evaluatePredicate(SegmentCondition condition, int workspaceId, int userId) {
        String key = normalize(condition.getKey());
        if (key == null || !PREDICATE_KEYS.contains(key)) {
            throw new BadRequestException("Unknown predicate: " + condition.getKey());
        }
        return switch (key) {
            case "open_deal" -> new HashSet<>(segmentMapper.companyIdsWithOpenDeal(workspaceId));
            case "no_activity" -> new HashSet<>(segmentMapper.companyIdsNoActivitySince(workspaceId, resolveDays(condition.getDays())));
            case "cooling" -> coolingCompanyIds(workspaceId);
            case "warm_intro_available" -> warmIntroCompanyIds(workspaceId, userId);
            default -> throw new BadRequestException("Unknown predicate: " + condition.getKey());
        };
    }

    private Set<Integer> evaluateField(SegmentCondition condition, int workspaceId) {
        String field = normalize(condition.getField());
        String op = normalize(condition.getOp());
        if (field == null || op == null) {
            throw new BadRequestException("Field condition requires 'field' and 'op'");
        }
        return switch (field) {
            case "industry" -> switch (op) {
                case "equals" -> new HashSet<>(segmentMapper.companyIdsByIndustry(workspaceId, requireValue(condition)));
                case "contains" -> new HashSet<>(segmentMapper.companyIdsByIndustryContains(workspaceId, LikePattern.containing(requireValue(condition))));
                default -> throw new BadRequestException("Unsupported operator for 'industry': " + condition.getOp());
            };
            case "name" -> switch (op) {
                case "contains" -> new HashSet<>(segmentMapper.companyIdsByNameContains(workspaceId, LikePattern.containing(requireValue(condition))));
                default -> throw new BadRequestException("Unsupported operator for 'name': " + condition.getOp());
            };
            case "tag" -> switch (op) {
                case "has" -> new HashSet<>(segmentMapper.companyIdsByTag(workspaceId, parseTagId(condition)));
                default -> throw new BadRequestException("Unsupported operator for 'tag': " + condition.getOp());
            };
            default -> throw new BadRequestException("Unknown field: " + condition.getField());
        };
    }

    private Set<Integer> coolingCompanyIds(int workspaceId) {
        Set<Integer> owned = new HashSet<>(segmentMapper.companyIdsInWorkspace(workspaceId));
        Set<Integer> ids = new HashSet<>();
        for (RelationshipTemperatureDto temperature : scoringService.scoreCompanies(workspaceId)) {
            if (owned.contains(temperature.getId())
                    && ("cool".equals(temperature.getBand())
                        || "cold".equals(temperature.getBand())
                        || "cooling".equals(temperature.getTrend()))) {
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
            if (edge.getStrength() < STRONG_EDGE || edge.getSourcePersonId() == edge.getTargetPersonId()) {
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

    private void requireCompany(String recordType) {
        if (!"company".equals(normalize(recordType))) {
            throw new BadRequestException("Smart segments are not available for record type: " + recordType);
        }
    }

    private int resolveDays(Integer days) {
        if (days == null) {
            return DEFAULT_DAYS;
        }
        return Math.min(Math.max(days, 1), MAX_DAYS);
    }

    private static String requireValue(SegmentCondition condition) {
        String value = condition.getValue();
        if (value == null || value.isBlank()) {
            throw new BadRequestException("Field condition requires a value");
        }
        return value.trim();
    }

    private static int parseTagId(SegmentCondition condition) {
        try {
            return Integer.parseInt(requireValue(condition));
        } catch (NumberFormatException e) {
            throw new BadRequestException("Tag condition requires a numeric tag id");
        }
    }

    private static String signature(SegmentCondition c) {
        return normalize(c.getType()) + "|" + normalize(c.getKey()) + "|" + c.getDays() + "|"
            + normalize(c.getField()) + "|" + normalize(c.getOp()) + "|" + c.getValue() + "|" + c.isNegate();
    }

    private static String normalize(String value) {
        return value == null ? null : value.trim().toLowerCase();
    }
}
