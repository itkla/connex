package ooo.klae.connex.backend.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.exceptions.BadRequestException;

/** Covers how the products browser's facet params resolve into one canonical filter. */
class ProductFacetFilterTest {

    @Test
    void absentParamsNarrowNothing() {
        assertEquals(ProductFacetFilter.none(), ProductFacetFilter.fromRequest(null, null, null));
        assertEquals(ProductFacetFilter.none(), ProductFacetFilter.fromRequest(List.of(), List.of(), List.of()));
    }

    @Test
    void theBrowserFacetKeysResolveAndRepeatsCollapse() {
        ProductFacetFilter filter = ProductFacetFilter.fromRequest(
            List.of("one_time", "recurring", "one_time"),
            List.of("true", "false", "true"),
            List.of("bounded", "__empty__"));

        assertEquals(Set.of("one_time", "recurring"), filter.billingFrequencies());
        assertEquals(Set.of(true, false), filter.availability());
        assertEquals(
            Set.of(ProductFacetFilter.BOUNDED, ProductFacetFilter.NO_DATE_LIMIT), filter.effectiveDates());
    }

    /**
     * The browser matches facet keys exactly, so a key it would match against no product must not
     * be read leniently here, or the export would hold rows the view does not list.
     */
    @Test
    void anyKeyTheBrowserWouldNotMatchIsABadRequest() {
        for (List<String> billing : List.of(
                List.of("monthly"), List.of("Recurring"), List.of(" recurring"), List.of(""))) {
            assertThrows(BadRequestException.class, () -> ProductFacetFilter.fromRequest(billing, null, null));
        }
        for (List<String> active : List.of(List.of("TRUE"), List.of("1"), Arrays.asList((String) null))) {
            assertThrows(BadRequestException.class, () -> ProductFacetFilter.fromRequest(null, active, null));
        }
        for (List<String> dates : List.of(List.of("none"), List.of("Bounded"), List.of("bounded", "none"))) {
            assertThrows(BadRequestException.class, () -> ProductFacetFilter.fromRequest(null, null, dates));
        }
    }

    @Test
    void theCanonicalConstructorRejectsUnknownValuesFromCode() {
        assertThrows(IllegalArgumentException.class,
            () -> new ProductFacetFilter(Set.of("weekly"), Set.of(), Set.of()));
        assertThrows(IllegalArgumentException.class,
            () -> new ProductFacetFilter(Set.of(), Set.of(), Set.of("expired")));
    }
}
