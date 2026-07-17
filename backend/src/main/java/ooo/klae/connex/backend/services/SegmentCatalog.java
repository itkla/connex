package ooo.klae.connex.backend.services;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * The declarative source of truth for the smart-segment condition vocabulary: the supported record
 * types, the field catalog (each field's {@link Kind} and value source), the predicate catalog (each
 * predicate's applicable record types and parameters), the operators legal for each kind, the closed
 * enum option sets, and the definition shape limits.
 *
 * <p>{@link SegmentService} consults this registry for validation and evaluation dispatch, and a
 * future catalog endpoint will render the builder from it, so the vocabulary lives here once instead
 * of being mirrored across the service, the mapper, and the client. Extend the catalog by adding a
 * field or predicate entry here; keep {@link Kind} a closed enum, and keep the change additive so
 * already-persisted definitions in rules and campaign audiences keep validating.
 */
@Component
public class SegmentCatalog {

    /** The value kinds a field can hold; determines its legal operators and value binding. */
    public enum Kind { STRING, NUMBER, ID, ENUM, TAG, DATE }

    /**
     * Where the builder sources a field's value options. {@code NONE} means free entry
     * (text / number / date); the others name a workspace-scoped option list supplied separately by
     * the value-options endpoint rather than baked into the cacheable catalog.
     */
    public enum ValueSource { NONE, TAGS, INDUSTRIES, OWNERS, STAGES, PIPELINES, COMPANIES }

    /** A filterable field: its {@code field} key, value {@link Kind}, and value-option {@link ValueSource}. */
    public record FieldSpec(String field, Kind kind, ValueSource valueSource) { }

    /**
     * A graph- or temperature-derived predicate: its {@code key}, the record types it applies to, and
     * whether it accepts a {@code days} parameter (with bounds) for time-windowed matching.
     */
    public record PredicateSpec(String key, Set<String> recordTypes, boolean acceptsDays,
            int defaultDays, int minDays, int maxDays) { }

    private static final int MAX_CONDITIONS = 32;
    private static final int MAX_GROUP_CONDITIONS = 16;
    private static final int MAX_GROUPS = 8;
    private static final int MAX_DEPTH = 4;
    private static final int DEFAULT_DAYS = 30;
    private static final int MIN_DAYS = 1;
    private static final int MAX_DAYS = 3650;

    private static final Map<Kind, List<String>> OPERATORS = Map.of(
        Kind.STRING, List.of("equals", "contains", "starts_with", "is_set"),
        Kind.NUMBER, List.of("equals", "gt", "gte", "lt", "lte"),
        Kind.ID, List.of("is", "in"),
        Kind.ENUM, List.of("is"),
        Kind.TAG, List.of("has"),
        Kind.DATE, List.of("before", "after", "within_days", "is_set"));

    private static final Map<String, List<String>> ENUM_OPTIONS = Map.of(
        "status", List.of("open", "won", "lost"));

    private static final Map<String, List<FieldSpec>> FIELDS = buildFields();
    private static final Map<String, PredicateSpec> PREDICATES = buildPredicates();

    private static Map<String, List<FieldSpec>> buildFields() {
        Map<String, List<FieldSpec>> fields = new LinkedHashMap<>();
        fields.put("company", List.of(
            new FieldSpec("industry", Kind.STRING, ValueSource.INDUSTRIES),
            new FieldSpec("name", Kind.STRING, ValueSource.NONE),
            new FieldSpec("website", Kind.STRING, ValueSource.NONE),
            new FieldSpec("phone", Kind.STRING, ValueSource.NONE),
            new FieldSpec("tag", Kind.TAG, ValueSource.TAGS),
            new FieldSpec("created", Kind.DATE, ValueSource.NONE),
            new FieldSpec("updated", Kind.DATE, ValueSource.NONE)));
        fields.put("person", List.of(
            new FieldSpec("name", Kind.STRING, ValueSource.NONE),
            new FieldSpec("title", Kind.STRING, ValueSource.NONE),
            new FieldSpec("email", Kind.STRING, ValueSource.NONE),
            new FieldSpec("phone", Kind.STRING, ValueSource.NONE),
            new FieldSpec("company", Kind.ID, ValueSource.COMPANIES),
            new FieldSpec("tag", Kind.TAG, ValueSource.TAGS),
            new FieldSpec("created", Kind.DATE, ValueSource.NONE),
            new FieldSpec("updated", Kind.DATE, ValueSource.NONE)));
        fields.put("deal", List.of(
            new FieldSpec("name", Kind.STRING, ValueSource.NONE),
            new FieldSpec("value", Kind.NUMBER, ValueSource.NONE),
            new FieldSpec("actual_value", Kind.NUMBER, ValueSource.NONE),
            new FieldSpec("stage", Kind.ID, ValueSource.STAGES),
            new FieldSpec("owner", Kind.ID, ValueSource.OWNERS),
            new FieldSpec("status", Kind.ENUM, ValueSource.NONE),
            new FieldSpec("close_date", Kind.DATE, ValueSource.NONE),
            new FieldSpec("tag", Kind.TAG, ValueSource.TAGS),
            new FieldSpec("created", Kind.DATE, ValueSource.NONE),
            new FieldSpec("updated", Kind.DATE, ValueSource.NONE)));
        return Collections.unmodifiableMap(fields);
    }

    private static Map<String, PredicateSpec> buildPredicates() {
        Map<String, PredicateSpec> predicates = new LinkedHashMap<>();
        predicates.put("warm_intro_available",
            new PredicateSpec("warm_intro_available", Set.of("company"), false, 0, 0, 0));
        predicates.put("open_deal",
            new PredicateSpec("open_deal", Set.of("company"), false, 0, 0, 0));
        predicates.put("cooling",
            new PredicateSpec("cooling", Set.of("company"), false, 0, 0, 0));
        predicates.put("no_activity",
            new PredicateSpec("no_activity", Set.of("company"), true, DEFAULT_DAYS, MIN_DAYS, MAX_DAYS));
        predicates.put("has_open_task",
            new PredicateSpec("has_open_task", Set.of("person", "deal"), false, 0, 0, 0));
        predicates.put("overdue_task",
            new PredicateSpec("overdue_task", Set.of("person", "deal"), false, 0, 0, 0));
        predicates.put("recent_meeting",
            new PredicateSpec("recent_meeting", Set.of("person", "deal"), true, DEFAULT_DAYS, MIN_DAYS, MAX_DAYS));
        predicates.put("has_note",
            new PredicateSpec("has_note", Set.of("person", "deal"), false, 0, 0, 0));
        predicates.put("has_attachment",
            new PredicateSpec("has_attachment", Set.of("company", "person", "deal"), false, 0, 0, 0));
        return Collections.unmodifiableMap(predicates);
    }

    /**
     * @param recordType a candidate record type
     * @return whether smart segments support the record type
     */
    public boolean supportsRecordType(String recordType) {
        return FIELDS.containsKey(recordType);
    }

    /** @return the supported record types, in catalog order */
    public Set<String> recordTypes() {
        return FIELDS.keySet();
    }

    /**
     * @param recordType a supported record type
     * @return the filterable fields for the record type, in catalog order (empty when unsupported)
     */
    public List<FieldSpec> fields(String recordType) {
        return FIELDS.getOrDefault(recordType, List.of());
    }

    /**
     * @param recordType a supported record type
     * @param field a field key
     * @return the field's {@link Kind}, or {@code null} when the record type has no such field
     */
    public Kind fieldKind(String recordType, String field) {
        for (FieldSpec spec : fields(recordType)) {
            if (spec.field().equals(field)) {
                return spec.kind();
            }
        }
        return null;
    }

    /**
     * @param kind a field kind
     * @return the operator tokens legal for the kind, in display order
     */
    public List<String> operatorsFor(Kind kind) {
        return OPERATORS.getOrDefault(kind, List.of());
    }

    /** @return the predicate catalog, in catalog order */
    public Collection<PredicateSpec> predicates() {
        return PREDICATES.values();
    }

    /**
     * @param key a candidate predicate key
     * @return whether the key names a known predicate
     */
    public boolean isPredicate(String key) {
        return PREDICATES.containsKey(key);
    }

    /**
     * @param key a predicate key
     * @return the predicate spec, or {@code null} when the key is unknown
     */
    public PredicateSpec predicate(String key) {
        return PREDICATES.get(key);
    }

    /**
     * @param recordType a supported record type
     * @return whether any predicate applies to the record type
     */
    public boolean recordTypeSupportsPredicates(String recordType) {
        for (PredicateSpec spec : PREDICATES.values()) {
            if (spec.recordTypes().contains(recordType)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @param key a predicate key
     * @param recordType a record type
     * @return whether the predicate applies to the record type
     */
    public boolean predicateAppliesTo(String key, String recordType) {
        PredicateSpec spec = PREDICATES.get(key);
        return spec != null && spec.recordTypes().contains(recordType);
    }

    /** @return the closed set of deal status values */
    public Set<String> statusValues() {
        return Set.copyOf(ENUM_OPTIONS.getOrDefault("status", List.of()));
    }

    /**
     * @param field an enum-kind field key
     * @return the field's ordered option values, or an empty list when the field has no option set
     */
    public List<String> enumOptions(String field) {
        return ENUM_OPTIONS.getOrDefault(field, List.of());
    }

    /** @return the maximum number of conditions a definition may reference across all groups */
    public int maxConditions() {
        return MAX_CONDITIONS;
    }

    /** @return the maximum number of conditions a single group may hold */
    public int maxGroupConditions() {
        return MAX_GROUP_CONDITIONS;
    }

    /** @return the maximum number of nested groups a single group may hold */
    public int maxGroups() {
        return MAX_GROUPS;
    }

    /** @return the maximum group nesting depth */
    public int maxDepth() {
        return MAX_DEPTH;
    }

    /** @return the default {@code days} window applied when a predicate omits one */
    public int defaultDays() {
        return DEFAULT_DAYS;
    }

    /**
     * Clamps a caller-supplied {@code days} window to the catalog bounds, defaulting when absent.
     *
     * @param days a candidate day count, or {@code null}
     * @return the day count clamped to {@code [minDays, maxDays]}, or the default when {@code null}
     */
    public int clampDays(Integer days) {
        if (days == null) {
            return DEFAULT_DAYS;
        }
        return Math.min(Math.max(days, MIN_DAYS), MAX_DAYS);
    }
}
