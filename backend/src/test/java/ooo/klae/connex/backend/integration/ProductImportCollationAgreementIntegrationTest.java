package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import ooo.klae.connex.backend.beans.Product;
import ooo.klae.connex.backend.beans.ProductSkuResolution;
import ooo.klae.connex.backend.mappers.ProductMapper;

/**
 * Proves catalog-import SKU classification agrees with {@code uq_product_workspace_sku} whatever
 * collation {@code product.sku} carries. V102 declares {@code DEFAULT CHARSET=utf8mb4} with no
 * explicit collation, so the column follows the server's utf8mb4 default at creation time: a
 * long-lived catalog can sit on {@code utf8mb4_unicode_ci} or {@code utf8mb4_general_ci} while CI
 * only ever sees a uniform {@code utf8mb4_0900_ai_ci} scratch schema. A resolution that named a
 * literal collation would then disagree with the index it exists to mirror, so each case applies
 * the real V102 DDL to a scratch catalog, moves {@code sku} onto the case collation, and checks
 * every candidate against what the unique index actually accepts. The statement is also exercised
 * at the import cap, where it binds two parameters per candidate row.
 */
class ProductImportCollationAgreementIntegrationTest {

    private static final String SCRATCH_CATALOG =
        "connex_product_collation_it_" + UUID.randomUUID().toString().replace("-", "");
    private static final String MIGRATION = "db/migration/tenant/V102__product.sql";
    private static final int WORKSPACE_ID = 1;
    private static final int IMPORT_CAP = 5000;
    private static final Map<String, String> STORED = Map.of(
        "SKU-A", "Stored widget", "SKU-L", "Stored letter");
    private static final List<String> CANDIDATES = List.of(
        "SKU-A", "SKU-Á", "ＳＫＵ-Ａ", "SKU-ss", "SKU-ß", "SKU-Ł", "SKU-OTHER");

    private static String bootstrapUrl;
    private static String scratchUrl;
    private static String username;
    private static String password;
    private static boolean created;
    private static SqlSessionFactory sessionFactory;

    @BeforeAll
    static void createScratchCatalog() throws Exception {
        String configuredUrl = System.getenv().getOrDefault(
            "CONNEX_DB_URL",
            "jdbc:mysql://localhost:3306/connexdb?createDatabaseIfNotExist=true&sslMode=DISABLED");
        username = System.getenv("CONNEX_DB_USERNAME");
        password = System.getenv("CONNEX_DB_PASSWORD");
        assumeTrue(
            username != null && password != null,
            "CONNEX_DB_USERNAME/CONNEX_DB_PASSWORD not set; skipping product collation test");
        bootstrapUrl = withCatalog(configuredUrl, "mysql");
        scratchUrl = withCatalog(configuredUrl, SCRATCH_CATALOG);
        try (Connection connection = DriverManager.getConnection(bootstrapUrl, username, password);
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE `" + SCRATCH_CATALOG
                + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
            created = true;
        } catch (SQLException exception) {
            assumeTrue(false, "Cannot create product collation scratch catalog: "
                + exception.getMessage());
        }
        sessionFactory = buildSessionFactory();
    }

    @AfterAll
    static void dropScratchCatalog() throws SQLException {
        if (!created) {
            return;
        }
        try (Connection connection = DriverManager.getConnection(bootstrapUrl, username, password);
                Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS `" + SCRATCH_CATALOG + "`");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "utf8mb4_0900_ai_ci", "utf8mb4_unicode_ci", "utf8mb4_general_ci", "utf8mb4_0900_as_cs"})
    void resolutionMatchesWhatTheUniqueIndexAccepts(String collation) throws Exception {
        List<ProductSkuResolution> resolutions = resolveOn(collation);
        List<Boolean> rejected = uniqueIndexRejections();

        for (int index = 0; index < CANDIDATES.size(); index++) {
            ProductSkuResolution resolution = resolutions.get(index);
            String candidate = CANDIDATES.get(index);
            assertEquals(index, resolution.getCandidateIndex());
            boolean repeatsAnEarlierCandidate = resolutions.subList(0, index).stream()
                .anyMatch(earlier -> earlier.getCollationOrder() == resolution.getCollationOrder());
            assertEquals(
                rejected.get(index),
                resolution.getProductId() != null || repeatsAnEarlierCandidate,
                collation + ": " + candidate + " must resolve the way the unique index acts");
            assertEquals(
                resolutions.stream()
                    .filter(other -> other.getCollationOrder() == resolution.getCollationOrder())
                    .count(),
                resolution.getEquivalentCount(),
                collation + ": " + candidate + " must count its own equivalence class");
            if (resolution.getProductId() != null) {
                assertTrue(
                    STORED.containsValue(resolution.getProductName()),
                    collation + ": " + candidate + " must name the catalog row it matched");
            }
        }
    }

    @Test
    void resolutionFollowsTheColumnCollationInsteadOfAFixedOne() throws Exception {
        assertNotEquals(
            classification(resolveOn("utf8mb4_0900_ai_ci")),
            classification(resolveOn("utf8mb4_unicode_ci")),
            "a legacy utf8mb4_unicode_ci column must not be classified as utf8mb4_0900_ai_ci");
    }

    @Test
    void resolvesAFullImportCapInOneStatement() throws Exception {
        applyProductTable("utf8mb4_0900_ai_ci");
        List<String> capped = IntStream.range(0, IMPORT_CAP)
            .mapToObj(candidate -> "CAP-" + candidate)
            .toList();

        try (SqlSession session = sessionFactory.openSession()) {
            List<ProductSkuResolution> resolutions = session.getMapper(ProductMapper.class)
                .resolveImportSkus(WORKSPACE_ID, capped);

            assertEquals(IMPORT_CAP, resolutions.size());
            for (int index = 0; index < IMPORT_CAP; index++) {
                assertEquals(index, resolutions.get(index).getCandidateIndex());
                assertEquals(1, resolutions.get(index).getEquivalentCount());
            }
        }
    }

    private static List<ProductSkuResolution> resolveOn(String collation) throws Exception {
        applyProductTable(collation);
        assertEquals(
            collation,
            skuColumnCollation(),
            "precondition: the scratch catalog must carry the case collation");
        try (SqlSession session = sessionFactory.openSession()) {
            List<ProductSkuResolution> resolutions = session.getMapper(ProductMapper.class)
                .resolveImportSkus(WORKSPACE_ID, CANDIDATES);
            assertEquals(CANDIDATES.size(), resolutions.size());
            return resolutions;
        }
    }

    private static String classification(List<ProductSkuResolution> resolutions) {
        StringBuilder signature = new StringBuilder();
        for (ProductSkuResolution resolution : resolutions) {
            signature.append(resolution.getCollationOrder())
                .append(resolution.getProductId() == null ? "-new" : "-matched")
                .append(';');
        }
        return signature.toString();
    }

    private static void applyProductTable(String collation) throws Exception {
        try (Connection connection = DriverManager.getConnection(scratchUrl, username, password);
                Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS product");
            statement.execute(migrationDdl());
            statement.execute(
                "ALTER TABLE product MODIFY sku VARCHAR(64) COLLATE " + collation + " NULL");
        }
        try (Connection connection = DriverManager.getConnection(scratchUrl, username, password);
                PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO product (workspace_id, sku, name) VALUES (?, ?, ?)")) {
            for (Map.Entry<String, String> stored : STORED.entrySet()) {
                statement.setInt(1, WORKSPACE_ID);
                statement.setString(2, stored.getKey());
                statement.setString(3, stored.getValue());
                statement.executeUpdate();
            }
        }
    }

    private static List<Boolean> uniqueIndexRejections() throws SQLException {
        List<Boolean> rejections = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(scratchUrl, username, password)) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO product (workspace_id, sku, name) VALUES (?, ?, ?)")) {
                for (String candidate : CANDIDATES) {
                    statement.setInt(1, WORKSPACE_ID);
                    statement.setString(2, candidate);
                    statement.setString(3, "Index probe");
                    try {
                        statement.executeUpdate();
                        rejections.add(false);
                    } catch (SQLIntegrityConstraintViolationException rejected) {
                        rejections.add(true);
                    }
                }
            }
            connection.rollback();
        }
        return rejections;
    }

    private static String skuColumnCollation() throws SQLException {
        try (Connection connection = DriverManager.getConnection(scratchUrl, username, password);
                PreparedStatement statement = connection.prepareStatement("""
                    SELECT collation_name
                    FROM information_schema.columns
                    WHERE table_schema = ? AND table_name = 'product' AND column_name = 'sku'
                    """)) {
            statement.setString(1, SCRATCH_CATALOG);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getString(1);
            }
        }
    }

    private static String migrationDdl() throws IOException {
        try (InputStream input = ProductImportCollationAgreementIntegrationTest.class
                .getClassLoader().getResourceAsStream(MIGRATION)) {
            assertNotNull(input, MIGRATION + " must be on the test classpath");
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            String ddl = sql.lines()
                .filter(line -> !line.stripLeading().startsWith("--"))
                .collect(Collectors.joining("\n"));
            int terminator = ddl.lastIndexOf(';');
            return terminator < 0 ? ddl : ddl.substring(0, terminator);
        }
    }

    private static SqlSessionFactory buildSessionFactory() throws IOException {
        Configuration configuration = new Configuration(new Environment(
            "product-collation",
            new JdbcTransactionFactory(),
            new UnpooledDataSource(
                "com.mysql.cj.jdbc.Driver", scratchUrl, username, password)));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.getTypeAliasRegistry().registerAlias("Product", Product.class);
        String resource = "mappers/ProductMapper.xml";
        try (InputStream input = ProductImportCollationAgreementIntegrationTest.class
                .getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input, resource + " must be on the test classpath");
            new XMLMapperBuilder(
                input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        return new SqlSessionFactoryBuilder().build(configuration);
    }

    private static String withCatalog(String jdbcUrl, String catalog) {
        int authorityEnd = jdbcUrl.indexOf('/', "jdbc:mysql://".length());
        if (authorityEnd < 0) {
            throw new IllegalArgumentException("CONNEX_DB_URL must include a database path");
        }
        int queryStart = jdbcUrl.indexOf('?', authorityEnd);
        String suffix = queryStart < 0 ? "" : jdbcUrl.substring(queryStart);
        return jdbcUrl.substring(0, authorityEnd + 1) + catalog + suffix;
    }
}
