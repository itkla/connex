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

/** Verifies every deal member-scope SQL branch resolves to the canonical owner predicate. */
class DealMapperXmlTest {
    private static final List<String> SCOPED_STATEMENTS = List.of(
        "getDealsPageFiltered",
        "countDealsFiltered",
        "dealMetricsFiltered",
        "getFilteredDealIds",
        "countsByStatus",
        "countsByStage",
        "countsByPipeline",
        "countsByCompany",
        "countsByOwner",
        "countsByCurrency",
        "getDealBoard"
    );

    @Test
    void memberScopeBranchesKeepWorkspaceFirstAndRenderExpectedPredicates() throws Exception {
        Configuration configuration = configuration();
        MemberScope allTeam = MemberScope.fromRequest(null, null, 7);
        MemberScope me = MemberScope.fromRequest("me", List.of(99), 7);
        MemberScope members = MemberScope.fromRequest("members", List.of(3, 5), 7);
        MemberScope unassigned = MemberScope.fromRequest("unassigned", List.of(99), 7);

        for (String statement : SCOPED_STATEMENTS) {
            assertScopePredicate(configuration, statement, allTeam, null);
            assertScopePredicate(configuration, statement, me, "d.owner_id = ?");
            assertScopePredicate(configuration, statement, members, "d.owner_id IN");
            assertScopePredicate(configuration, statement, unassigned, "d.owner_id IS NULL");
        }

        String membersSql = sql(configuration, "getDealsPageFiltered", members);
        assertTrue(membersSql.matches(".*d\\.owner_id IN \\(\\s*\\?\\s*,\\s*\\?\\s*\\).*"));
        String singleMemberSql = sql(configuration, "getDealsPageFiltered",
            MemberScope.fromRequest("members", List.of(3), 7));
        assertTrue(singleMemberSql.matches(".*d\\.owner_id IN \\(\\s*\\?\\s*\\).*"));
    }

    private static void assertScopePredicate(Configuration configuration, String statement,
            MemberScope scope, String predicate) {
        String sql = sql(configuration, statement, scope);
        int workspaceIndex = sql.indexOf("d.workspace_id = ?");
        assertTrue(workspaceIndex >= 0);
        if (predicate == null) {
            assertFalse(sql.contains("d.owner_id = ?"));
            assertFalse(sql.contains("d.owner_id IN"));
            assertFalse(sql.contains("d.owner_id IS NULL"));
            return;
        }
        int predicateIndex = sql.indexOf(predicate);
        assertTrue(predicateIndex > workspaceIndex);
    }

    private static String sql(Configuration configuration, String statement, MemberScope scope) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("workspaceId", 11);
        parameters.put("pipelineId", 13);
        parameters.put("memberScope", scope);
        parameters.put("noCompany", false);
        parameters.put("limit", 25);
        parameters.put("offset", 0);
        return configuration.getMappedStatement(DealMapper.class.getName() + "." + statement)
            .getBoundSql(parameters)
            .getSql()
            .replaceAll("\\s+", " ")
            .trim();
    }

    private static Configuration configuration() throws Exception {
        Configuration configuration = new Configuration();
        configuration.getTypeAliasRegistry().registerAliases("ooo.klae.connex.backend.beans");
        for (String resource : List.of("mappers/PersonMapper.xml", "mappers/DealMapper.xml")) {
            try (InputStream input = DealMapperXmlTest.class.getClassLoader().getResourceAsStream(resource)) {
                assertNotNull(input);
                new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
            }
        }
        return configuration;
    }
}
