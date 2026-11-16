package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.beans.Product;
import ooo.klae.connex.backend.dto.ProductFacetFilter;

/** Verifies the shared product list/export query parses and preserves browser search semantics. */
class ProductMapperXmlTest {

    @Test
    void filteredSearchIsOptionalBoundAndAccentSensitive() throws Exception {
        Configuration configuration = configuration();

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("workspaceId", 7);
        parameters.put("query", null);
        parameters.put("facets", ProductFacetFilter.none());
        BoundSql unfiltered = configuration.getMappedStatement(
            ProductMapper.class.getName() + ".getFiltered").getBoundSql(parameters);
        assertTrue(sql(unfiltered).contains("WHERE workspace_id = ?"));
        assertFalse(sql(unfiltered).contains("LOWER(name)"));
        assertFalse(sql(unfiltered).contains("billing_frequency IN"));
        assertFalse(sql(unfiltered).contains("active IN"));
        assertFalse(sql(unfiltered).contains("effective_start IS"));

        parameters.put("query", "%café%");
        BoundSql filtered = configuration.getMappedStatement(
            ProductMapper.class.getName() + ".getFiltered").getBoundSql(parameters);
        String filteredSql = sql(filtered);
        assertTrue(filteredSql.contains(
            "LOWER(name) COLLATE utf8mb4_bin LIKE LOWER(?) COLLATE utf8mb4_bin"));
        assertTrue(filteredSql.contains(
            "LOWER(sku) COLLATE utf8mb4_bin LIKE LOWER(?) COLLATE utf8mb4_bin"));
        assertTrue(filteredSql.contains(
            "LOWER(description) COLLATE utf8mb4_bin LIKE LOWER(?) COLLATE utf8mb4_bin"));
        assertTrue(filteredSql.endsWith("ORDER BY name, id"));
        assertTrue(filtered.getParameterMappings().stream()
            .filter(mapping -> mapping.getProperty().equals("query")).count() == 3);
    }

    /** Each browser facet binds its selections and adds exactly one predicate, or none when unselected. */
    @Test
    void facetSelectionsBindAsPredicatesAndBothDateBucketsNarrowNothing() throws Exception {
        Configuration configuration = configuration();

        BoundSql billing = facetSql(configuration,
            ProductFacetFilter.fromRequest(List.of("one_time", "recurring"), null, null));
        assertTrue(sql(billing).contains("AND billing_frequency IN"));
        assertEquals(2, billing.getParameterMappings().stream()
            .filter(mapping -> mapping.getProperty().startsWith("__frch_frequency")).count());

        BoundSql availability = facetSql(configuration,
            ProductFacetFilter.fromRequest(null, List.of("false"), null));
        assertTrue(sql(availability).contains("AND active IN"));
        assertEquals(1, availability.getParameterMappings().stream()
            .filter(mapping -> mapping.getProperty().startsWith("__frch_state")).count());

        String bounded = sql(facetSql(configuration,
            ProductFacetFilter.fromRequest(null, null, List.of(ProductFacetFilter.BOUNDED))));
        assertTrue(bounded.contains("AND (effective_start IS NOT NULL OR effective_end IS NOT NULL)"));

        String noDateLimit = sql(facetSql(configuration,
            ProductFacetFilter.fromRequest(null, null, List.of(ProductFacetFilter.NO_DATE_LIMIT))));
        assertTrue(noDateLimit.contains("AND effective_start IS NULL AND effective_end IS NULL"));

        String bothBuckets = sql(facetSql(configuration, ProductFacetFilter.fromRequest(
            null, null, List.of(ProductFacetFilter.BOUNDED, ProductFacetFilter.NO_DATE_LIMIT))));
        assertFalse(bothBuckets.contains("effective_start IS"));
        assertTrue(bothBuckets.endsWith("ORDER BY name, id"));
    }

    private static BoundSql facetSql(Configuration configuration, ProductFacetFilter facets) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("workspaceId", 7);
        parameters.put("query", null);
        parameters.put("facets", facets);
        return configuration.getMappedStatement(ProductMapper.class.getName() + ".getFiltered")
            .getBoundSql(parameters);
    }

    private Configuration configuration() throws Exception {
        Configuration configuration = new Configuration();
        configuration.getTypeAliasRegistry().registerAlias("Product", Product.class);
        String resource = "mappers/ProductMapper.xml";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input);
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        return configuration;
    }

    private static String sql(BoundSql boundSql) {
        return boundSql.getSql().replaceAll("\\s+", " ").trim();
    }
}
