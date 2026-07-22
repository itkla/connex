package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.services.SegmentCatalog.FieldSpec;
import ooo.klae.connex.backend.services.SegmentCatalog.Kind;
import ooo.klae.connex.backend.services.SegmentCatalog.PredicateSpec;
import ooo.klae.connex.backend.services.SegmentCatalog.ValueSource;

/**
 * Golden characterization of the segment catalog. Locks the exact vocabulary — record types, fields
 * and their kinds/value sources, operators per kind, predicate applicability, enum options, and the
 * shape limits — so an accidental change to the condition model is caught here. The Increment 1
 * registry refactor must reproduce this vocabulary exactly (zero behavior change).
 */
class SegmentCatalogTest {

    private final SegmentCatalog catalog = new SegmentCatalog();

    @Test
    void recordTypesAreCompanyPersonDealInOrder() {
        assertEquals(List.of("company", "person", "deal"), List.copyOf(catalog.recordTypes()));
        assertTrue(catalog.supportsRecordType("company"));
        assertTrue(catalog.supportsRecordType("person"));
        assertTrue(catalog.supportsRecordType("deal"));
        assertFalse(catalog.supportsRecordType("task"));
        assertFalse(catalog.supportsRecordType(null));
    }

    @Test
    void companyFieldsMatchTheCatalog() {
        assertEquals(List.of(
            new FieldSpec("industry", Kind.STRING, ValueSource.INDUSTRIES),
            new FieldSpec("name", Kind.STRING, ValueSource.NONE),
            new FieldSpec("website", Kind.STRING, ValueSource.NONE),
            new FieldSpec("phone", Kind.STRING, ValueSource.NONE),
            new FieldSpec("owner", Kind.ID, ValueSource.OWNERS),
            new FieldSpec("tag", Kind.TAG, ValueSource.TAGS),
            new FieldSpec("created", Kind.DATE, ValueSource.NONE),
            new FieldSpec("updated", Kind.DATE, ValueSource.NONE)),
            catalog.fields("company"));
    }

    @Test
    void personFieldsMatchTheCatalog() {
        assertEquals(List.of(
            new FieldSpec("name", Kind.STRING, ValueSource.NONE),
            new FieldSpec("title", Kind.STRING, ValueSource.NONE),
            new FieldSpec("email", Kind.STRING, ValueSource.NONE),
            new FieldSpec("phone", Kind.STRING, ValueSource.NONE),
            new FieldSpec("company", Kind.ID, ValueSource.COMPANIES),
            new FieldSpec("owner", Kind.ID, ValueSource.OWNERS),
            new FieldSpec("tag", Kind.TAG, ValueSource.TAGS),
            new FieldSpec("created", Kind.DATE, ValueSource.NONE),
            new FieldSpec("updated", Kind.DATE, ValueSource.NONE)),
            catalog.fields("person"));
    }

    @Test
    void dealFieldsMatchTheCatalog() {
        assertEquals(List.of(
            new FieldSpec("name", Kind.STRING, ValueSource.NONE),
            new FieldSpec("value", Kind.NUMBER, ValueSource.NONE),
            new FieldSpec("actual_value", Kind.NUMBER, ValueSource.NONE),
            new FieldSpec("stage", Kind.ID, ValueSource.STAGES),
            new FieldSpec("owner", Kind.ID, ValueSource.OWNERS),
            new FieldSpec("status", Kind.ENUM, ValueSource.NONE),
            new FieldSpec("close_date", Kind.DATE, ValueSource.NONE),
            new FieldSpec("tag", Kind.TAG, ValueSource.TAGS),
            new FieldSpec("created", Kind.DATE, ValueSource.NONE),
            new FieldSpec("updated", Kind.DATE, ValueSource.NONE)),
            catalog.fields("deal"));
    }

    @Test
    void fieldKindResolvesAndFallsToNull() {
        assertEquals(Kind.STRING, catalog.fieldKind("company", "industry"));
        assertEquals(Kind.NUMBER, catalog.fieldKind("deal", "value"));
        assertEquals(Kind.DATE, catalog.fieldKind("deal", "close_date"));
        assertNull(catalog.fieldKind("company", "value"));
        assertNull(catalog.fieldKind("company", "unknown"));
        assertNull(catalog.fieldKind("task", "name"));
    }

    @Test
    void operatorsPerKindMatchTheCatalogInOrder() {
        assertEquals(List.of("equals", "contains", "starts_with", "is_set"), catalog.operatorsFor(Kind.STRING));
        assertEquals(List.of("equals", "gt", "gte", "lt", "lte"), catalog.operatorsFor(Kind.NUMBER));
        assertEquals(List.of("is", "in"), catalog.operatorsFor(Kind.ID));
        assertEquals(List.of("is"), catalog.operatorsFor(Kind.ENUM));
        assertEquals(List.of("has"), catalog.operatorsFor(Kind.TAG));
        assertEquals(List.of("before", "after", "within_days", "is_set"), catalog.operatorsFor(Kind.DATE));
    }

    @Test
    void predicateApplicabilityMatchesTheCatalog() {
        assertEquals(Set.of("warm_intro_available", "open_deal", "cooling", "no_activity",
                "has_open_task", "overdue_task", "recent_meeting", "has_note", "has_attachment",
                "warmth_hot", "warmth_warm", "warmth_cool", "warmth_cold", "warmth_rising", "going_cold",
                "at_risk", "risk_high", "risk_close_overdue", "risk_closing_soon", "risk_stalled",
                "risk_stakeholder_cold", "risk_no_stakeholders"),
            catalog.predicates().stream().map(PredicateSpec::key).collect(Collectors.toSet()));

        for (String key : Set.of("warm_intro_available", "open_deal", "cooling", "no_activity")) {
            assertEquals(Set.of("company"), catalog.predicate(key).recordTypes(), key);
        }
        for (String key : Set.of("has_open_task", "overdue_task", "recent_meeting", "has_note")) {
            assertEquals(Set.of("person", "deal"), catalog.predicate(key).recordTypes(), key);
        }
        for (String key : Set.of("warmth_hot", "warmth_warm", "warmth_cool", "warmth_cold",
                "warmth_rising", "going_cold")) {
            assertEquals(Set.of("company", "person"), catalog.predicate(key).recordTypes(), key);
        }
        assertEquals(Set.of("company", "person", "deal"), catalog.predicate("has_attachment").recordTypes());
        for (String key : Set.of("at_risk", "risk_high", "risk_close_overdue", "risk_closing_soon",
                "risk_stalled", "risk_stakeholder_cold", "risk_no_stakeholders")) {
            assertEquals(Set.of("deal"), catalog.predicate(key).recordTypes(), key);
        }

        assertTrue(catalog.predicateAppliesTo("open_deal", "company"));
        assertTrue(catalog.predicateAppliesTo("at_risk", "deal"));
        assertFalse(catalog.predicateAppliesTo("at_risk", "company"));
        assertFalse(catalog.predicateAppliesTo("open_deal", "person"));
        assertTrue(catalog.predicateAppliesTo("has_open_task", "person"));
        assertFalse(catalog.predicateAppliesTo("has_open_task", "company"));
        assertTrue(catalog.predicateAppliesTo("has_attachment", "company"));
        assertTrue(catalog.predicateAppliesTo("warmth_hot", "person"));
        assertFalse(catalog.predicateAppliesTo("warmth_hot", "deal"));

        assertTrue(catalog.recordTypeSupportsPredicates("company"));
        assertTrue(catalog.recordTypeSupportsPredicates("person"));
        assertTrue(catalog.recordTypeSupportsPredicates("deal"));
        assertTrue(catalog.isPredicate("cooling"));
        assertFalse(catalog.isPredicate("unknown"));
    }

    @Test
    void dayParameterizedPredicates() {
        for (String key : Set.of("no_activity", "recent_meeting", "going_cold")) {
            PredicateSpec spec = catalog.predicate(key);
            assertTrue(spec.acceptsDays(), key);
            assertEquals(30, spec.defaultDays(), key);
            assertEquals(1, spec.minDays(), key);
            assertEquals(3650, spec.maxDays(), key);
        }
        assertFalse(catalog.predicate("open_deal").acceptsDays());
        assertFalse(catalog.predicate("cooling").acceptsDays());
        assertFalse(catalog.predicate("warm_intro_available").acceptsDays());
        assertFalse(catalog.predicate("has_open_task").acceptsDays());
        assertFalse(catalog.predicate("has_attachment").acceptsDays());
        assertFalse(catalog.predicate("warmth_hot").acceptsDays());
        assertNull(catalog.predicate("unknown"));
    }

    @Test
    void statusValuesAreOpenWonLost() {
        assertEquals(Set.of("open", "won", "lost"), catalog.statusValues());
        assertEquals(List.of("open", "won", "lost"), catalog.enumOptions("status"));
        assertTrue(catalog.enumOptions("unknown").isEmpty());
    }

    @Test
    void limitsMatchTheDefinitionShape() {
        assertEquals(32, catalog.maxConditions());
        assertEquals(16, catalog.maxGroupConditions());
        assertEquals(8, catalog.maxGroups());
        assertEquals(4, catalog.maxDepth());
        assertEquals(30, catalog.defaultDays());
    }

    @Test
    void clampDaysDefaultsAndBounds() {
        assertEquals(30, catalog.clampDays(null));
        assertEquals(1, catalog.clampDays(0));
        assertEquals(1, catalog.clampDays(-5));
        assertEquals(5, catalog.clampDays(5));
        assertEquals(3650, catalog.clampDays(999999));
    }
}
