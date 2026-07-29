package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

/** Pins exact group pagination and bounded member retrieval in the collision mapper XML. */
class IdentityCollisionMapperXmlTest {

    private static final Pattern FIND_VISIBLE_MEMBERS = Pattern.compile(
        "<select\\s+id=\"findVisibleMembers\"[^>]*>(.*?)</select>",
        Pattern.DOTALL);
    private static final Pattern FIND_VISIBLE_GROUP_PAGE = Pattern.compile(
        "<select\\s+id=\"findVisibleGroupPage\"[^>]*>(.*?)</select>",
        Pattern.DOTALL);
    private static final String MAX_EXECUTION_TIME_HINT =
        "/*+ MAX_EXECUTION_TIME(3000) */";
    private static final String HINTED_OUTER_SELECT =
        ") SELECT /*+ MAX_EXECUTION_TIME(3000) */ record_type,";
    private static final Pattern COLLISION_GROUP_CTE = Pattern.compile(
        "WITH collision_groups AS \\((.*?)\\), collision_groups_with_sentinel AS \\(",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern MAX_EXECUTION_TIME_HINT_PATTERN =
        Pattern.compile(Pattern.quote(MAX_EXECUTION_TIME_HINT));
    private static final Pattern PERSON_BRANCH = Pattern.compile(
        "<when\\s+test=\"group\\.recordType\\s*==\\s*'person'\"\\s*>(.*?)</when>",
        Pattern.DOTALL);
    private static final Pattern COMPANY_BRANCH = Pattern.compile(
        "<otherwise\\s*>(.*?)</otherwise>",
        Pattern.DOTALL);
    private static final Pattern MEMBER_LIMIT = Pattern.compile(
        "\\bLIMIT\\s+#\\{memberLimit\\}",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern WINDOW_FUNCTION = Pattern.compile(
        "\\bOVER\\s*\\(",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern COUNT_FUNCTION = Pattern.compile(
        "\\bCOUNT\\s*\\(",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern PERSON_INDEXED_KEYSET = Pattern.compile(
        "\\bpi\\.workspace_id\\s*=\\s*#\\{workspaceId\\}.*"
            + "\\bpi\\.kind\\s*=\\s*#\\{group\\.kind\\}.*"
            + "\\bpi\\.normalized_value\\s*=\\s*#\\{group\\.normalizedValue\\}.*"
            + "\\bpi\\.person_id\\s*&gt;\\s*#\\{afterRecordId\\}.*"
            + "\\bORDER\\s+BY\\s+pi\\.person_id\\b.*"
            + "\\bLIMIT\\s+#\\{memberLimit\\}",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern COMPANY_INDEXED_KEYSET = Pattern.compile(
        "\\bci\\.workspace_id\\s*=\\s*#\\{workspaceId\\}.*"
            + "\\bci\\.kind\\s*=\\s*#\\{group\\.kind\\}.*"
            + "\\bci\\.normalized_value\\s*=\\s*#\\{group\\.normalizedValue\\}.*"
            + "\\bci\\.company_id\\s*&gt;\\s*#\\{afterRecordId\\}.*"
            + "\\bORDER\\s+BY\\s+ci\\.company_id\\b.*"
            + "\\bLIMIT\\s+#\\{memberLimit\\}",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern XML_COMMENT_TOKEN = Pattern.compile("<!--|-->");

    @Test
    void groupPageIsOneNarrowTimedAggregateWithSentinelWindowMetadata()
            throws IOException {
        String xml = mapperXml();
        Configuration configuration = mapperConfiguration();
        String namespace = IdentityCollisionMapper.class.getName();

        assertEquals(
            1L,
            FIND_VISIBLE_GROUP_PAGE.matcher(xml).results().count(),
            "exactly one group page statement");
        assertFalse(configuration.hasStatement(namespace + ".findVisibleGroups", false));
        assertFalse(configuration.hasStatement(namespace + ".countVisibleGroups", false));
        MappedStatement statement =
            configuration.getMappedStatement(namespace + ".findVisibleGroupPage");
        assertEquals(3, statement.getTimeout());

        String personSql = groupSql(statement, "person", "email");
        String companySql = groupSql(statement, "company", "domain");
        String emailSql = groupSql(statement, null, "email");
        String domainSql = groupSql(statement, null, "domain");
        String combinedSql = groupSql(statement, null, "phone");
        String unfilteredSql = groupSql(statement, null, null);

        assertTrue(personSql.contains("FROM person_identity pi"));
        assertTrue(personSql.contains("JOIN identity_collision ic"));
        assertTrue(personSql.contains("JOIN person p"));
        assertTrue(personSql.contains("p.suspended_at IS NULL"));
        assertTrue(personSql.contains("p.provision_ceased_at IS NULL"));
        assertTrue(personSql.contains("pi.superseded_at IS NULL"));
        assertTrue(personSql.contains("pi.kind = ?"));
        assertFalse(personSql.contains("company_identity"));
        assertFalse(emailSql.contains("company_identity"));

        assertTrue(companySql.contains("FROM company_identity ci"));
        assertTrue(companySql.contains("JOIN identity_collision ic"));
        assertTrue(companySql.contains("ci.superseded_at IS NULL"));
        assertTrue(companySql.contains("ci.kind = ?"));
        assertFalse(companySql.contains("person_identity"));
        assertFalse(domainSql.contains("person_identity"));
        assertTrue(domainSql.contains("ci.kind = ?"));
        assertFalse(Pattern.compile("\\bJOIN\\s+company\\s", Pattern.CASE_INSENSITIVE)
            .matcher(companySql).find());

        assertTrue(combinedSql.contains("FROM person_identity pi"));
        assertTrue(combinedSql.contains("FROM company_identity ci"));
        assertTrue(combinedSql.contains("pi.kind = ?"));
        assertTrue(combinedSql.contains("ci.kind = ?"));
        assertFalse(unfilteredSql.contains(".kind = ?"));

        assertTrue(emailSql.contains("pi.kind = ?"));

        for (String sql : List.of(
                personSql,
                companySql,
                emailSql,
                domainSql,
                combinedSql,
                unfilteredSql)) {
            assertFalse(sql.contains("record_name"));
            assertFalse(sql.contains("p.name"));
            assertFalse(sql.contains("c.name"));
            assertFalse(sql.contains("FORCE INDEX"));
            assertEquals(
                1L,
                countMatches(MAX_EXECUTION_TIME_HINT_PATTERN, sql),
                "every rendered group branch must contain the exact hint once");
            assertTrue(
                sql.contains(HINTED_OUTER_SELECT),
                "the exact hint must immediately follow the outer SELECT");
            assertFalse(
                extractExactlyOne(
                    COLLISION_GROUP_CTE,
                    sql,
                    "rendered collision_groups CTE")
                    .contains(MAX_EXECUTION_TIME_HINT),
                "the hint must not be inside collision_groups");
            assertTrue(sql.contains("COUNT(*) AS collision_size"));
            assertTrue(sql.contains("MAX(ic.rebuilt_at) AS rebuilt_at"));
            assertTrue(sql.contains("HAVING COUNT(*) >= 2"));
            assertTrue(sql.contains("UNION ALL SELECT NULL, NULL, NULL, NULL, NULL"));
            assertTrue(sql.contains("COUNT(record_type) OVER () AS total"));
            assertTrue(sql.contains("ROW_NUMBER() OVER"));
            assertTrue(sql.contains(
                "ORDER BY record_type IS NULL, record_type, kind, normalized_value"));
            assertTrue(sql.contains("record_type IS NOT NULL AND page_ordinal > ?"));
            assertTrue(sql.contains("record_type IS NULL AND total <= ?"));
        }
    }

    @Test
    void visibleMemberBranchesKeepIndexedKeysetLimitsWithoutCountsOrWindows()
            throws IOException {
        String xml = mapperXml();

        assertFalse(
            XML_COMMENT_TOKEN.matcher(xml).find(),
            "IdentityCollisionMapper.xml must not contain XML comment tokens");
        assertFalse(
            xml.contains("id=\"countVisibleGroupMembers\""),
            "member pagination must not retain a full-group count statement");
        String statement = extractExactlyOne(
            FIND_VISIBLE_MEMBERS,
            xml,
            "findVisibleMembers select");
        assertFalse(
            WINDOW_FUNCTION.matcher(statement).find(),
            "findVisibleMembers must not use an OVER window function");
        assertFalse(
            COUNT_FUNCTION.matcher(statement).find(),
            "findVisibleMembers must not count the collision group");
        String personBranch = extractExactlyOne(
            PERSON_BRANCH,
            statement,
            "findVisibleMembers person when-branch");
        String companyBranch = extractExactlyOne(
            COMPANY_BRANCH,
            statement,
            "findVisibleMembers company otherwise-branch");
        assertTrue(personBranch.contains("pi.superseded_at IS NULL"));
        assertTrue(companyBranch.contains("ci.superseded_at IS NULL"));
        assertTrue(
            PERSON_INDEXED_KEYSET.matcher(personBranch).find(),
            "person members must retain the composite-index keyset predicate and order");
        assertTrue(
            COMPANY_INDEXED_KEYSET.matcher(companyBranch).find(),
            "company members must retain the composite-index keyset predicate and order");

        assertEquals(
            1L,
            countMatches(MEMBER_LIMIT, personBranch),
            "person branch member limit count");
        assertEquals(
            1L,
            countMatches(MEMBER_LIMIT, companyBranch),
            "company branch member limit count");
        assertEquals(
            2L,
            countMatches(MEMBER_LIMIT, statement),
            "findVisibleMembers total member limit count");
    }

    private static String extractExactlyOne(Pattern pattern, String source, String label) {
        Matcher matcher = pattern.matcher(source);
        if (!matcher.find()) {
            throw new IllegalStateException("Missing " + label);
        }
        String value = matcher.group(1);
        if (matcher.find()) {
            throw new IllegalStateException("Multiple matches for " + label);
        }
        return value;
    }

    private static long countMatches(Pattern pattern, String source) {
        return pattern.matcher(source).results().count();
    }

    private static String mapperXml() throws IOException {
        try (InputStream input = IdentityCollisionMapperXmlTest.class.getClassLoader()
                .getResourceAsStream("mappers/IdentityCollisionMapper.xml")) {
            if (input == null) {
                throw new IllegalStateException("IdentityCollisionMapper.xml is missing");
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Configuration mapperConfiguration() throws IOException {
        Configuration configuration = new Configuration();
        String resource = "mappers/IdentityCollisionMapper.xml";
        try (InputStream input = IdentityCollisionMapperXmlTest.class.getClassLoader()
                .getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException("IdentityCollisionMapper.xml is missing");
            }
            new XMLMapperBuilder(
                input,
                configuration,
                resource,
                configuration.getSqlFragments()).parse();
        }
        return configuration;
    }

    private static String groupSql(
            MappedStatement statement,
            String recordType,
            String kind) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("workspaceId", 7);
        parameters.put("recordType", recordType);
        parameters.put("kind", kind);
        parameters.put("limit", 50);
        parameters.put("offset", 0L);
        return statement.getBoundSql(parameters)
            .getSql()
            .replaceAll("\\s+", " ")
            .trim();
    }
}
