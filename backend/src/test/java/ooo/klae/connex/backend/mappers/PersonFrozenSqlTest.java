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
import ooo.klae.connex.backend.support.PersonVisibilityParity;
import ooo.klae.connex.backend.support.LegacyRecordVisibilitySql;

/** Checks frozen Person SQL parity without executing SQL or requiring a database. */
class PersonFrozenSqlTest {
    @Test
    void allTwentyNineStatementsDifferOnlyInTheirOrganizationCeiling() throws Exception {
        Configuration frozen = new Configuration();
        frozen.getTypeAliasRegistry().registerAliases("ooo.klae.connex.backend.beans");
        var field = LegacyRecordVisibilitySql.class.getDeclaredField("PERSON");
        field.setAccessible(true);
        String xml = (String) field.get(null);
        new XMLMapperBuilder(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)),
            frozen, "frozen-person", frozen.getSqlFragments()).parse();
        Configuration tenant = new Configuration();
        tenant.getTypeAliasRegistry().registerAliases("ooo.klae.connex.backend.beans");
        for (String resource : List.of("mappers/PersonMapper.xml", "mappers/TagMapper.xml")) {
            try (var input = getClass().getClassLoader().getResourceAsStream(resource)) {
                new XMLMapperBuilder(input, tenant, resource, tenant.getSqlFragments()).parse();
            }
        }
        List<String> statements = PersonVisibilityParity.STATEMENTS;
        assertEquals(29, statements.size());
        for (String statement : statements) {
            for (boolean filtered : List.of(false, true)) {
                for (String sort : List.of("name", "email", "phone", "title", "company", "createdAt", "updatedAt", "warmth")) {
                    for (String direction : List.of("asc", "desc")) {
                        Map<String, Object> parameters = parameters(filtered);
                        parameters.put("sort", sort);
                        parameters.put("dir", direction);
                        String id = "ooo.klae.connex.backend.mappers.PersonMapper." + statement;
                        assertEquals(normalize(frozen.getMappedStatement(id).getBoundSql(parameters).getSql()),
                            normalize(tenant.getMappedStatement(id).getBoundSql(parameters).getSql()), id);
                    }
                }
            }
        }
    }

    private static Map<String, Object> parameters(boolean filtered) {
        Map<String, Object> result = PersonVisibilityParity.parameters();
        if (filtered) {
            result.put("query", "%employer%");
            result.put("companies", List.of("Employer"));
            result.put("titles", List.of("Director"));
            result.put("noCompany", true);
            result.put("lifecycleStages", List.of(ooo.klae.connex.backend.beans.PersonLifecycleStage.NEW));
            result.put("leadSources", List.of(ooo.klae.connex.backend.beans.PersonLeadSource.EVENT));
            result.put("firstResponseStates", List.of(ooo.klae.connex.backend.beans.PersonFirstResponseState.PENDING));
            result.put("excludedActivityIds", List.of(1));
            result.put("excludedNoteIds", List.of(1));
            result.put("excludedTaskIds", List.of(1));
            result.put("limit", 1);
            result.put("offset", 1);
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
