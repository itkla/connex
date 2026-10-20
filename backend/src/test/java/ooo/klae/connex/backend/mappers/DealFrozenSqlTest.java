package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.dto.MemberScope;
import ooo.klae.connex.backend.support.DealVisibilityParity;
import ooo.klae.connex.backend.support.LegacyRecordVisibilitySql;

/** Checks frozen Deal SQL parity without executing SQL or requiring a database. */
class DealFrozenSqlTest {
    @Test
    void allNineteenStatementsDifferOnlyInTheirOrganizationCeiling() throws Exception {
        Configuration frozen = new Configuration();
        frozen.getTypeAliasRegistry().registerAliases("ooo.klae.connex.backend.beans");
        var field = LegacyRecordVisibilitySql.class.getDeclaredField("DEAL");
        field.setAccessible(true);
        String xml = (String) field.get(null);
        new XMLMapperBuilder(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)),
            frozen, "frozen-deal", frozen.getSqlFragments()).parse();
        Configuration tenant = new Configuration();
        tenant.getTypeAliasRegistry().registerAliases("ooo.klae.connex.backend.beans");
        for (String resource : List.of("mappers/PersonMapper.xml", "mappers/DealMapper.xml")) {
            try (var input = getClass().getClassLoader().getResourceAsStream(resource)) {
                new XMLMapperBuilder(input, tenant, resource, tenant.getSqlFragments()).parse();
            }
        }
        assertFalse(tenant.hasStatement("ooo.klae.connex.backend.mappers.DealMapper.getDealsPage"));
        List<String> statements = new java.util.ArrayList<>(DealVisibilityParity.READS);
        statements.addAll(DealVisibilityParity.WRITES);
        assertEquals(19, statements.size());
        for (String statement : statements) {
            for (boolean filtered : List.of(false, true)) {
                for (String sort : List.of("name", "value", "actual_value", "expected_close_date", "closed_at",
                        "created_at", "updated_at", "stage_id", "position", "company", "pipeline", "stage", "status")) {
                    for (String direction : List.of("asc", "desc")) {
                        Map<String, Object> parameters = parameters(filtered);
                        parameters.put("sort", sort);
                        parameters.put("dir", direction);
                        String id = "ooo.klae.connex.backend.mappers.DealMapper." + statement;
                        assertEquals(normalize(frozen.getMappedStatement(id).getBoundSql(parameters).getSql()),
                            normalize(tenant.getMappedStatement(id).getBoundSql(parameters).getSql()), id);
                    }
                }
            }
        }
    }

    private static Map<String, Object> parameters(boolean filtered) {
        Map<String, Object> result = new HashMap<>();
        result.put("workspaceId", 7);
        result.put("orgWorkspaceIdsJson", "[7,8,9]");
        result.put("dealId", 41);
        result.put("dealIds", List.of(41, 42));
        result.put("personId", 11);
        result.put("pipelineId", 12);
        result.put("stageId", 13);
        result.put("role", "champion");
        result.put("limit", filtered ? 1 : 10);
        result.put("offset", filtered ? 1 : 0);
        for (String key : List.of("query", "currency", "pipelineIds", "stageIds", "companyIds", "personIds",
                "statuses", "riskIds", "memberScope", "segmentIdsJson")) {
            result.put(key, null);
        }
        result.put("noCompany", filtered);
        if (filtered) {
            result.put("query", "%related label%");
            result.put("currency", "JPY");
            for (String key : List.of("pipelineIds", "stageIds", "companyIds", "personIds", "riskIds")) {
                result.put(key, List.of(11, 12));
            }
            result.put("statuses", List.of("won", "lost"));
            result.put("segmentIdsJson", "[41,42]");
            result.put("memberScope", MemberScope.allTeam());
        }
        return result;
    }

    private static String normalize(String sql) {
        return sql.replaceAll("\\s+", " ").trim()
            .replaceAll("JOIN workspace vws ON vws.id = (?:cs|ps|filtered_ps)\\.workspace_id "
                + "JOIN workspace ows ON ows.id = (\\w+)\\.workspace_id AND ows.org_id = vws.org_id", "ORG_SCOPE($1)")
            .replaceAll("JOIN JSON_TABLE\\(\\?, '\\$\\[\\*\\]' COLUMNS\\(id INT PATH '\\$'\\)\\) "
                + "org_workspace ON org_workspace.id = (\\w+)\\.workspace_id", "ORG_SCOPE($1)");
    }
}
