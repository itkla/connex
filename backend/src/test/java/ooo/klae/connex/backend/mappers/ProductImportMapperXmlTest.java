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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.beans.Product;

/**
 * Verifies catalog-import SKU resolution stays workspace-scoped, parameter-bound, and aligned with
 * {@code uq_product_workspace_sku}.
 */
class ProductImportMapperXmlTest {

    private Configuration configuration;

    @BeforeEach
    void parseMapper() throws Exception {
        configuration = new Configuration();
        configuration.getTypeAliasRegistry().registerAlias("Product", Product.class);
        String resource = "mappers/ProductMapper.xml";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input);
            new XMLMapperBuilder(
                input, configuration, resource, configuration.getSqlFragments()).parse();
        }
    }

    @Test
    void skuLookupIsBoundWorkspaceScopedAndNotBinaryCollated() {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("workspaceId", 7);
        parameters.put("skus", List.of("A-1", "B-2"));
        BoundSql boundSql = configuration.getMappedStatement(
            ProductMapper.class.getName() + ".findBySkus").getBoundSql(parameters);
        String sql = sql(boundSql);

        assertTrue(sql.contains("WHERE workspace_id = ?"), sql);
        assertTrue(sql.contains("AND sku IN ( ? , ? )"), sql);
        assertFalse(sql.contains("COLLATE"), sql);
        assertTrue(sql.endsWith("ORDER BY id"), sql);
        assertEquals(3, boundSql.getParameterMappings().size());
    }

    @Test
    void importSkuResolutionIsBoundWorkspaceScopedAndNamesNoLiteralCollation() {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("workspaceId", 7);
        parameters.put("skus", List.of("SKU-A", "SKU-Á"));
        BoundSql boundSql = configuration.getMappedStatement(
            ProductMapper.class.getName() + ".resolveImportSkus").getBoundSql(parameters);
        String sql = sql(boundSql);

        assertFalse(sql.contains("COLLATE"), sql);
        assertFalse(sql.contains("SKU-"), sql);
        assertTrue(
            sql.contains("SELECT 0, product.sku FROM product WHERE workspace_id = ? AND 1 = 0"),
            sql);
        assertTrue(sql.contains("UNION ALL VALUES ROW(?, ?) , ROW(?, ?) )"), sql);
        assertTrue(sql.contains("COUNT(*) OVER (PARTITION BY candidates.sku)"), sql);
        assertTrue(sql.contains("DENSE_RANK() OVER (ORDER BY candidates.sku)"), sql);
        assertTrue(sql.contains("product.workspace_id = ?"), sql);
        assertTrue(sql.contains("product.sku = candidates.sku"), sql);
        assertEquals(6, boundSql.getParameterMappings().size());
        assertEquals("workspaceId", boundSql.getParameterMappings().getFirst().getProperty());
        assertEquals("workspaceId", boundSql.getParameterMappings().getLast().getProperty());
    }

    @Test
    void getByIdForUpdateSelectsForUpdate() {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("workspaceId", 7);
        parameters.put("id", 31);
        String sql = sql(configuration.getMappedStatement(
            ProductMapper.class.getName() + ".getByIdForUpdate").getBoundSql(parameters));

        assertTrue(sql.contains("WHERE workspace_id = ? AND id = ?"), sql);
        assertTrue(sql.endsWith("FOR UPDATE"), sql);
    }

    private static String sql(BoundSql boundSql) {
        return boundSql.getSql().replaceAll("\\s+", " ").trim();
    }
}
