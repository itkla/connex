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
            new FieldSpec("tag", Kind.TAG, ValueSource.TAGS)),
            catalog.fields("company"));
    }

    @Test
    void personFieldsMatchTheCatalog() {
        assertEquals(List.of(
            new FieldSpec("name", Kind.STRING, ValueSource.NONE),
            new FieldSpec("title", Kind.STRING, ValueSource.NONE),
            new FieldSpec("email", Kind.STRING, ValueSource.NONE),
            new FieldSpec("company", Kind.ID, ValueSource.COMPANIES),
            new FieldSpec("tag", Kind.TAG, ValueSource.TAGS)),
            catalog.fields("person"));
    }

    @Test
    void dealFieldsMatchTheCatalog() {
        assertEquals(List.of(
            new FieldSpec("name", Kind.STRING, ValueSource.NONE),
            new FieldSpec("value", Kind.NUMBER, ValueSource.NONE),
            new FieldSpec("stage", Kind.ID, ValueSource.STAGES),
            new FieldSpec("owner", Kind.ID, ValueSource.OWNERS),
            new FieldSpec("status", Kind.ENUM, ValueSource.NONE),
            new FieldSpec("close_date", Kind.DATE, ValueSource.NONE),
            new FieldSpec("tag", Kind.TAG, ValueSource.TAGS)),
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
    void predicatesAreCompanyOnly() {
        assertEquals(Set.of("warm_intro_available", "open_deal", "cooling", "no_activity"),
            catalog.predicates().stream().map(PredicateSpec::key).collect(Collectors.toSet()));
        for (PredicateSpec spec : catalog.predicates()) {
            assertEquals(Set.of("company"), spec.recordTypes(), spec.key());
            assertTrue(catalog.predicateAppliesTo(spec.key(), "company"));
            assertFalse(catalog.predicateAppliesTo(spec.key(), "person"));
            assertFalse(catalog.predicateAppliesTo(spec.key(), "deal"));
        }
        assertTrue(catalog.recordTypeSupportsPredicates("company"));
        assertFalse(catalog.recordTypeSupportsPredicates("person"));
        assertFalse(catalog.recordTypeSupportsPredicates("deal"));
        assertTrue(catalog.isPredicate("cooling"));
        assertFalse(catalog.isPredicate("unknown"));
    }

    @Test
    void onlyNoActivityAcceptsDays() {
        PredicateSpec noActivity = catalog.predicate("no_activity");
        assertTrue(noActivity.acceptsDays());
        assertEquals(30, noActivity.defaultDays());
        assertEquals(1, noActivity.minDays());
        assertEquals(3650, noActivity.maxDays());
        assertFalse(catalog.predicate("open_deal").acceptsDays());
        assertFalse(catalog.predicate("cooling").acceptsDays());
        assertFalse(catalog.predicate("warm_intro_available").acceptsDays());
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
