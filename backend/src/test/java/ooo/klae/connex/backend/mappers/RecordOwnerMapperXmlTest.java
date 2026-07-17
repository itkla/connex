package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.dto.MemberScope;

/** Verifies company and contact member scopes render only on page and count queries. */
class RecordOwnerMapperXmlTest {

    @Test
    void companyPageAndCountRenderEveryOwnerScope() throws Exception {
        Configuration configuration = configuration("mappers/CompanyMapper.xml");

        assertScopes(configuration, CompanyMapper.class, "getCompaniesPage", "c");
        assertScopes(configuration, CompanyMapper.class, "countCompanies", "c");
        assertFalse(companySql(configuration, "getCompanyIdsFiltered", null).contains("c.owner_id"));
    }

    @Test
    void personPageAndCountRenderEveryOwnerScopeButIdsStayAllTeam() throws Exception {
        Configuration configuration = configuration("mappers/PersonMapper.xml");

        assertScopes(configuration, PersonMapper.class, "getPersonsPage", "p");
        assertScopes(configuration, PersonMapper.class, "countPersons", "p");
        assertFalse(personSql(configuration, "getPersonIdsFiltered", null).contains("p.owner_id"));
    }

    private static void assertScopes(Configuration configuration, Class<?> mapper,
            String statement, String alias) {
        assertPredicate(configuration, mapper, statement, alias, MemberScope.allTeam(), null);
        assertPredicate(configuration, mapper, statement, alias,
            MemberScope.fromRequest("me", null, 7), alias + ".owner_id = ?");
        assertPredicate(configuration, mapper, statement, alias,
            MemberScope.fromRequest("members", List.of(3, 5), 7), alias + ".owner_id IN");
        assertPredicate(configuration, mapper, statement, alias,
            MemberScope.fromRequest("unassigned", null, 7), alias + ".owner_id IS NULL");

        String members = sql(configuration, mapper, statement,
            MemberScope.fromRequest("members", List.of(3, 5), 7));
        assertTrue(members.matches(".*" + alias + "\\.owner_id IN \\(\\s*\\?\\s*,\\s*\\?\\s*\\).*"));
    }

    private static void assertPredicate(Configuration configuration, Class<?> mapper,
            String statement, String alias, MemberScope scope, String predicate) {
        String sql = sql(configuration, mapper, statement, scope);
        int workspaceIndex = sql.indexOf(alias + ".workspace_id = ?");
        assertTrue(workspaceIndex >= 0);
        if (predicate == null) {
            assertFalse(sql.contains(alias + ".owner_id = ?"));
            assertFalse(sql.contains(alias + ".owner_id IN"));
            assertFalse(sql.contains(alias + ".owner_id IS NULL"));
            return;
        }
        assertTrue(sql.indexOf(predicate) > workspaceIndex);
    }

    private static String sql(Configuration configuration, Class<?> mapper,
            String statement, MemberScope scope) {
        if (mapper.equals(CompanyMapper.class)) {
            return companySql(configuration, statement, scope);
        }
        return personSql(configuration, statement, scope);
    }

    private static String companySql(Configuration configuration, String statement, MemberScope scope) {
        Map<String, Object> parameters = baseParameters(scope);
        parameters.put("industry", null);
        parameters.put("noIndustry", false);
        parameters.put("ids", null);
        return boundSql(configuration, CompanyMapper.class, statement, parameters);
    }

    private static String personSql(Configuration configuration, String statement, MemberScope scope) {
        Map<String, Object> parameters = baseParameters(scope);
        parameters.put("companies", null);
        parameters.put("titles", null);
        parameters.put("noCompany", false);
        return boundSql(configuration, PersonMapper.class, statement, parameters);
    }

    private static Map<String, Object> baseParameters(MemberScope scope) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("workspaceId", 11);
        parameters.put("query", null);
        parameters.put("sort", null);
        parameters.put("dir", null);
        parameters.put("memberScope", scope);
        parameters.put("limit", 25);
        parameters.put("offset", 0);
        return parameters;
    }

    private static String boundSql(Configuration configuration, Class<?> mapper,
            String statement, Map<String, Object> parameters) {
        return configuration.getMappedStatement(mapper.getName() + "." + statement)
            .getBoundSql(parameters)
            .getSql()
            .replaceAll("\\s+", " ")
            .trim();
    }

    private static Configuration configuration(String resource) throws Exception {
        Configuration configuration = new Configuration();
        configuration.getTypeAliasRegistry().registerAliases("ooo.klae.connex.backend.beans");
        try (InputStream input = RecordOwnerMapperXmlTest.class.getClassLoader()
                .getResourceAsStream(resource)) {
            assertNotNull(input);
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        return configuration;
    }
}
