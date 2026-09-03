package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.beans.DisqualificationReason;

/** Tenant binding and materialisation-safety checks for the reason mapper (#559). */
class DisqualificationReasonMapperXmlTest {
    @Test
    void everyStatementBindsTheWorkspaceAndEveryWriteKeepsItInThePredicateOrValues() throws Exception {
        Configuration configuration = configuration();
        Map<String, Object> parameters = Map.of(
            "workspaceId", 7,
            "id", 3,
            "code", "NO_FIT",
            "requiresNote", false,
            "position", 2);
        for (String statement : java.util.List.of(
                "getAll", "getById", "getByIdForUpdate", "getByCode",
                "getByCodeForUpdate", "insertBuiltIn", "archive", "restore")) {
            String sql = sql(configuration, statement, parameters);
            assertTrue(sql.contains("workspace_id = ?") || sql.contains("(workspace_id,"), statement);
        }

        assertTrue(sql(configuration, "getByIdForUpdate", parameters).contains("FOR UPDATE"));
        assertTrue(sql(configuration, "getByCodeForUpdate", parameters).contains("FOR UPDATE"));

        DisqualificationReason reason = new DisqualificationReason();
        reason.setWorkspaceId(7);
        reason.setId(3);
        reason.setCode("CUSTOM");
        reason.setLabel("Custom");
        assertTrue(sql(configuration, "insert", reason).contains("(workspace_id,"));
        assertTrue(sql(configuration, "update", reason).contains("WHERE workspace_id = ? AND id = ?"));
    }

    @Test
    void concurrentMaterialisationHasBothSqlAndSchemaUniquenessGuards() throws Exception {
        String mapper = resource("mappers/DisqualificationReasonMapper.xml");
        String migration = resource(
            "db/migration/tenant/V197__disqualification_reasons.sql");

        assertTrue(mapper.contains("INSERT IGNORE INTO disqualification_reason"));
        assertTrue(migration.contains(
            "UNIQUE KEY uq_disqualification_reason_workspace_code (workspace_id, code)"));
        assertFalse(mapper.contains("${"));
        assertFalse(mapper.contains("JOIN workspace"));
    }

    private static String sql(Configuration configuration, String statement, Object parameters) {
        BoundSql boundSql = configuration.getMappedStatement(
            DisqualificationReasonMapper.class.getName() + "." + statement)
            .getBoundSql(parameters);
        return boundSql.getSql().replaceAll("\\s+", " ").trim();
    }

    private static Configuration configuration() throws Exception {
        Configuration configuration = new Configuration();
        configuration.getTypeAliasRegistry().registerAliases(
            "ooo.klae.connex.backend.beans");
        String resource = "mappers/DisqualificationReasonMapper.xml";
        try (InputStream input = DisqualificationReasonMapperXmlTest.class
                .getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input);
            new XMLMapperBuilder(
                input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        return configuration;
    }

    private static String resource(String path) throws Exception {
        try (InputStream input = DisqualificationReasonMapperXmlTest.class
                .getClassLoader().getResourceAsStream(path)) {
            assertNotNull(input);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
