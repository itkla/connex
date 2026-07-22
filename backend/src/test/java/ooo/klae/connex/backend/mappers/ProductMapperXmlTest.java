package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.beans.Product;

/** Verifies the shared product list/export query parses and preserves browser search semantics. */
class ProductMapperXmlTest {

    @Test
    void filteredSearchIsOptionalBoundAndAccentSensitive() throws Exception {
        Configuration configuration = new Configuration();
        configuration.getTypeAliasRegistry().registerAlias("Product", Product.class);
        String resource = "mappers/ProductMapper.xml";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input);
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("workspaceId", 7);
        parameters.put("query", null);
        BoundSql unfiltered = configuration.getMappedStatement(
            ProductMapper.class.getName() + ".getFiltered").getBoundSql(parameters);
        assertTrue(sql(unfiltered).contains("WHERE workspace_id = ?"));
        assertFalse(sql(unfiltered).contains("LOWER(name)"));

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

    private static String sql(BoundSql boundSql) {
        return boundSql.getSql().replaceAll("\\s+", " ").trim();
    }
}
