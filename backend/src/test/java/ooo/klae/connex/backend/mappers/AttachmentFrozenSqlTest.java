package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import ooo.klae.connex.backend.support.LegacyAttachmentSql;

/** Checks every dynamic statement shape against the independently frozen pre-L8 SQL. */
class AttachmentFrozenSqlTest {
    @Test
    void fourStatementsChangeOnlyTheirUserFactJoinsAndNamePredicates() throws Exception {
        Configuration frozen = configuration();
        new XMLMapperBuilder(new ByteArrayInputStream(LegacyAttachmentSql.XML.getBytes(StandardCharsets.UTF_8)),
            frozen, "frozen-attachments", frozen.getSqlFragments()).parse();
        Configuration tenant = configuration();
        try (var input = getClass().getClassLoader().getResourceAsStream("mappers/AttachmentMapper.xml")) {
            new XMLMapperBuilder(input, tenant, "tenant-attachments", tenant.getSqlFragments()).parse();
        }
        for (String statement : List.of("search", "getPage", "countPage", "countOrphaned")) {
            for (boolean filtered : List.of(false, true)) {
                for (String sort : List.of("newest", "oldest", "name", "largest", "unknown")) {
                    Map<String, Object> parameters = new HashMap<>();
                    parameters.put("workspaceId", 7);
                    parameters.put("currentUserId", 11);
                    parameters.put("query", filtered ? "%name%" : null);
                    parameters.put("sort", sort);
                    parameters.put("types", filtered ? List.of("user", "company") : null);
                    parameters.put("kinds", filtered ? List.of("pdf") : null);
                    parameters.put("tagIds", filtered ? List.of(42) : null);
                    parameters.put("orphaned", filtered);
                    parameters.put("limit", 10);
                    parameters.put("offset", 1);
                    parameters.put("userFactsJson", "[]");
                    String legacy = frozen.getMappedStatement("legacy.AttachmentMapper." + statement)
                        .getBoundSql(parameters).getSql();
                    String current = tenant.getMappedStatement("ooo.klae.connex.backend.mappers.AttachmentMapper." + statement)
                        .getBoundSql(parameters).getSql();
                    assertEquals(normalize(legacy, statement), normalize(current, statement), statement + ":" + parameters);
                }
            }
        }
    }

    private Configuration configuration() {
        Configuration configuration = new Configuration();
        configuration.getTypeAliasRegistry().registerAliases("ooo.klae.connex.backend.beans");
        return configuration;
    }

    private String normalize(String sql, String statement) {
        String normalized = sql.replaceAll("\\s+", " ").trim();
        normalized = normalized.replaceAll("JSON_TABLE\\(\\?,.*?\\)\\)", "USER_FACTS")
            .replace("app_user", "USER_FACTS")
            .replace("u.display_name LIKE ?", "u.name_matches = 1")
            .replace("COALESCE(c.name, p.name, d.name, eu.display_name) LIKE ?",
                "COALESCE(c.name, p.name, d.name) LIKE ? OR (a.entity_type = 'user' AND eu.name_matches = 1)");
        if (statement.equals("countOrphaned")) {
            normalized = normalized.replace("LEFT JOIN USER_FACTS u ON u.id = a.uploaded_by_id ", "");
        }
        return normalized;
    }
}
