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
    void valuesAreTrimmedCaseFoldedAndDeduplicated() {
        ProductFacetFilter filter = ProductFacetFilter.fromRequest(
            List.of(" ONE_TIME", "one_time", "Recurring "),
            List.of("True", "false", "TRUE"),
            List.of("Bounded", " none"));

        assertEquals(Set.of("one_time", "recurring"), filter.billingFrequencies());
        assertEquals(Set.of(true, false), filter.availability());
        assertEquals(Set.of(ProductFacetFilter.BOUNDED, ProductFacetFilter.UNBOUNDED), filter.effectiveDates());
    }

    @Test
    void anyValueTheBrowserCannotSendIsABadRequest() {
        assertThrows(BadRequestException.class,
            () -> ProductFacetFilter.fromRequest(List.of("monthly"), null, null));
        assertThrows(BadRequestException.class,
            () -> ProductFacetFilter.fromRequest(null, List.of("1"), null));
        assertThrows(BadRequestException.class,
            () -> ProductFacetFilter.fromRequest(null, null, List.of("__empty__")));
        assertThrows(BadRequestException.class,
            () -> ProductFacetFilter.fromRequest(null, Arrays.asList((String) null), null));
        assertThrows(BadRequestException.class,
            () -> ProductFacetFilter.fromRequest(List.of(""), null, null));
    }

    @Test
    void theCanonicalConstructorRejectsUnknownValuesFromCode() {
        assertThrows(IllegalArgumentException.class,
            () -> new ProductFacetFilter(Set.of("weekly"), Set.of(), Set.of()));
        assertThrows(IllegalArgumentException.class,
            () -> new ProductFacetFilter(Set.of(), Set.of(), Set.of("expired")));
    }
}
