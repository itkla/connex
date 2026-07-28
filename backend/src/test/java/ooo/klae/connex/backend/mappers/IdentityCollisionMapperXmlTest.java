package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/** Pins bounded per-group member retrieval in the identity collision mapper XML. */
class IdentityCollisionMapperXmlTest {

    private static final Pattern FIND_VISIBLE_MEMBERS = Pattern.compile(
        "<select\\s+id=\"findVisibleMembers\"[^>]*>(.*?)</select>",
        Pattern.DOTALL);
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
    private static final Pattern XML_COMMENT_TOKEN = Pattern.compile("<!--|-->");

    @Test
    void visibleMemberBranchesKeepTheirOwnLimitsWithoutWindowFunctions() throws IOException {
        String xml = mapperXml();

        assertFalse(
            XML_COMMENT_TOKEN.matcher(xml).find(),
            "IdentityCollisionMapper.xml must not contain XML comment tokens");
        String statement = extractExactlyOne(
            FIND_VISIBLE_MEMBERS,
            xml,
            "findVisibleMembers select");
        assertFalse(
            WINDOW_FUNCTION.matcher(statement).find(),
            "findVisibleMembers must not use an OVER window function");
        String personBranch = extractExactlyOne(
            PERSON_BRANCH,
            statement,
            "findVisibleMembers person when-branch");
        String companyBranch = extractExactlyOne(
            COMPANY_BRANCH,
            statement,
            "findVisibleMembers company otherwise-branch");

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
}
