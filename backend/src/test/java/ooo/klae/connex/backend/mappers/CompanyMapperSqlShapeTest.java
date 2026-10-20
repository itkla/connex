package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.dto.MemberScope;
import ooo.klae.connex.backend.support.CompanyVisibilityParity;
import ooo.klae.connex.backend.warmth.RelationshipWarmthModel;

/** Expands every converted statement and optional CP ceiling without connecting to MySQL. */
class CompanyMapperSqlShapeTest {
    private static final String NAMESPACE = "ooo.klae.connex.backend.mappers.CompanyMapper.";
    private static final Pattern CONTROL_TABLE = Pattern.compile(
        "(?i)\\b(?:from|join)\\s+(?:workspace|workspace_member|app_user|organization)\\b");

    @Test
    void allTwentyThreeStatementsBindTheTrustedScopeAndExpandWithoutControlTables() throws Exception {
        Configuration configuration = configuration("mappers/CompanyMapper.xml");
        Map<String, Object> parameters = parameters();
        assertEquals(23, CompanyVisibilityParity.STATEMENTS.size());
        for (String statement : CompanyVisibilityParity.STATEMENTS) {
            var bound = configuration.getMappedStatement(NAMESPACE + statement).getBoundSql(parameters);
            assertFalse(CONTROL_TABLE.matcher(bound.getSql()).find(), statement);
            assertTrue(bound.getSql().contains("JSON_TABLE"), statement);
            assertTrue(bound.getParameterMappings().stream().anyMatch(
                mapping -> mapping.getProperty().equals("orgWorkspaceIdsJson")), statement);
        }
    }

    @Test
    void tagDefinitionHasNoNestedRecordSelects() throws Exception {
        Configuration configuration = configuration("mappers/TagMapper.xml");
        var tag = configuration.getMappedStatement("ooo.klae.connex.backend.mappers.TagMapper.getTagById");
        assertTrue(tag.getResultMaps().stream().flatMap(result -> result.getResultMappings().stream())
            .noneMatch(result -> result.getNestedQueryId() != null));
    }

    private Configuration configuration(String resource) throws Exception {
        Configuration configuration = new Configuration();
        configuration.getTypeAliasRegistry().registerAliases("ooo.klae.connex.backend.beans");
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input);
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        return configuration;
    }

    private static Map<String, Object> parameters() {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("workspaceId", 7);
        parameters.put("orgWorkspaceIdsJson", "[7,11]");
        parameters.put("id", 41);
        parameters.put("companyId", 41);
        parameters.put("ids", List.of(41));
        parameters.put("tagId", 23);
        parameters.put("segmentIdsJson", "[41]");
        parameters.put("query", "%Company%");
        parameters.put("pattern", "%Company%");
        parameters.put("archived", false);
        parameters.put("noIndustry", false);
        parameters.put("memberScope", MemberScope.allTeam());
        parameters.put("warmth", CompanyVisibilityParity.warmth());
        parameters.put("model", RelationshipWarmthModel.current().sqlParameters());
        parameters.put("reference", CompanyVisibilityParity.REFERENCE);
        parameters.put("sourceLimit", 100);
        parameters.put("limit", 25);
        parameters.put("offset", 0);
        return parameters;
    }
}
