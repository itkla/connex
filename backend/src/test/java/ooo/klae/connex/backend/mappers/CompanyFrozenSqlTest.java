package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.support.CompanyVisibilityParity;
import ooo.klae.connex.backend.support.LegacyRecordVisibilitySql;
import ooo.klae.connex.backend.support.PersonVisibilityParity;

/** Pins company SQL parity independently of database startup, including whole and partial phones. */
class CompanyFrozenSqlTest {
    @Test
    void allTwentyThreeStatementsDifferOnlyInTheirOrganizationCeiling() throws Exception {
        Configuration frozen = new Configuration();
        frozen.getTypeAliasRegistry().registerAliases("ooo.klae.connex.backend.beans");
        var field = LegacyRecordVisibilitySql.class.getDeclaredField("COMPANY");
        field.setAccessible(true);
        String xml = (String) field.get(null);
        new XMLMapperBuilder(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)),
            frozen, "frozen-company", frozen.getSqlFragments()).parse();
        Configuration tenant = new Configuration();
        tenant.getTypeAliasRegistry().registerAliases("ooo.klae.connex.backend.beans");
        try (var input = getClass().getClassLoader().getResourceAsStream("mappers/CompanyMapper.xml")) {
            new XMLMapperBuilder(input, tenant, "mappers/CompanyMapper.xml", tenant.getSqlFragments()).parse();
        }
        assertEquals(23, CompanyVisibilityParity.STATEMENTS.size());
        for (String statement : CompanyVisibilityParity.STATEMENTS) {
            for (String query : List.of("", "%Company%", "%09096846208%", "%96846208%")) {
                for (String sort : List.of("name", "website", "industry", "createdAt", "updatedAt", "warmth")) {
                    for (boolean archived : List.of(false, true)) {
                        var parameters = PersonVisibilityParity.parameters();
                        parameters.put("segmentIdsJson", "[41,42]");
                        parameters.put("pattern", "%Company%");
                        parameters.put("normalizedName", "company a");
                        parameters.put("query", query.isEmpty() ? null : query);
                        parameters.put("industry", List.of("Software"));
                        parameters.put("noIndustry", true);
                        parameters.put("sort", sort);
                        parameters.put("archived", archived);
                        String id = "ooo.klae.connex.backend.mappers.CompanyMapper." + statement;
                        assertEquals(normalize(frozen.getMappedStatement(id).getBoundSql(parameters).getSql()),
                            normalize(tenant.getMappedStatement(id).getBoundSql(parameters).getSql()), id);
                    }
                }
            }
        }
    }

    private static String normalize(String sql) {
        return sql.replaceAll("\\s+", " ").trim()
            .replaceAll("JOIN workspace vws ON vws.id = (?:cs|ps|filtered_cs|search_shared)\\.workspace_id "
                + "JOIN workspace ows ON ows.id = (\\w+)\\.workspace_id AND ows.org_id = vws.org_id", "ORG_SCOPE($1)")
            .replaceAll("JOIN workspace pvws ON pvws.id = ps\\.workspace_id "
                + "JOIN workspace pows ON pows.id = (\\w+)\\.workspace_id AND pows.org_id = pvws.org_id", "ORG_SCOPE($1)")
            .replaceAll("JOIN JSON_TABLE\\(\\?, '\\$\\[\\*\\]' COLUMNS\\(id INT PATH '\\$'\\)\\) "
                + "org_workspace ON org_workspace.id = (\\w+)\\.workspace_id", "ORG_SCOPE($1)");
    }
}
