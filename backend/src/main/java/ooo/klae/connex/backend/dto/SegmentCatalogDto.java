package ooo.klae.connex.backend.dto;

import java.util.List;
import java.util.Map;

/**
 * The static shape of the smart-segment builder for one record type: the filterable fields (each with
 * its kind, value source, and legal operators), the predicates that apply to the record type, the
 * option sets for enum fields, and the definition shape limits. This payload is workspace-independent
 * and cacheable; the workspace-scoped value options (tags, industries, owners, stages) are served
 * separately by the value-options endpoint so they never enter this cached response.
 */
public record SegmentCatalogDto(
    String recordType,
    List<CatalogField> fields,
    List<CatalogPredicate> predicates,
    Map<String, List<String>> enumOptions,
    CatalogLimits limits
) {

    /**
     * A filterable field. {@code kind} is one of {@code string|number|id|enum|tag|date}; {@code
     * valueSource} names where the builder sources value options ({@code none} for free entry, else
     * {@code tags|industries|owners|stages|pipelines|companies}); {@code operators} are the legal
     * operator tokens in display order.
     */
    public record CatalogField(String field, String kind, String valueSource, List<String> operators) { }

    /**
     * A graph- or temperature-derived predicate: its {@code key}, the record types it applies to, and
     * its {@code days} parameter bounds when {@code acceptsDays} is true (else the bounds are null).
     */
    public record CatalogPredicate(String key, List<String> recordTypes, boolean acceptsDays,
        Integer defaultDays, Integer minDays, Integer maxDays) { }

    /** The definition shape limits the builder must honor. */
    public record CatalogLimits(int maxConditions, int maxGroupConditions, int maxGroups, int maxDepth) { }
}
