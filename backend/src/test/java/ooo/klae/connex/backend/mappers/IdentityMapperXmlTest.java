package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.dto.DuplicateIdentityKey;
import ooo.klae.connex.backend.dto.DuplicateNameKey;

class IdentityMapperXmlTest {

    @Test
    void identityPreflightFiltersCurrentVisibleProcessableRecordsBeforeItsBound()
            throws Exception {
        Configuration configuration = configuration();
        Map<String, Object> parameters = Map.of(
            "workspaceId", 7,
            "orgWorkspaceIdsJson", "[7,9]",
            "keys", List.of(
                new DuplicateIdentityKey("email", "probe@example.com"),
                new DuplicateIdentityKey("phone", "+819012345678")),
            "perKeyLimit", 51);
        String people = sql(
            configuration, "findVisiblePersonIdentityMatches", parameters);
        String companies = sql(
            configuration, "findVisibleCompanyIdentityMatches", parameters);

        assertTrue(people.contains("pi.superseded_at IS NULL"));
        assertTrue(people.contains("p.suspended_at IS NULL"));
        assertTrue(people.contains("p.provision_ceased_at IS NULL"));
        assertTrue(people.contains("person_share ps"));
        assertTrue(people.contains(
            "JOIN JSON_TABLE(?, '$[*]' COLUMNS(id INT PATH '$')) org_workspace "
                + "ON org_workspace.id = p.workspace_id"));
        assertFalse(people.contains("JOIN workspace "));
        assertTrue(people.contains(
            "CASE WHEN p.workspace_id = ? THEN 0 ELSE 1 END"));
        assertTrue(people.indexOf("person_share ps") < people.indexOf("WHERE match_rank <= ?"));
        assertTrue(companies.contains("ci.superseded_at IS NULL"));
        assertTrue(companies.contains("company_share cs"));
        assertTrue(companies.contains(
            "JOIN JSON_TABLE(?, '$[*]' COLUMNS(id INT PATH '$')) org_workspace "
                + "ON org_workspace.id = c.workspace_id"));
        assertFalse(companies.contains("JOIN workspace "));
        assertTrue(companies.contains(
            "CASE WHEN c.workspace_id = ? THEN 0 ELSE 1 END"));
        assertTrue(
            companies.indexOf("company_share cs")
                < companies.indexOf("WHERE match_rank <= ?"));
    }

    @Test
    void namePreflightUsesEscapedPatternsAndTheSameVisibilityCeiling()
            throws Exception {
        Configuration configuration = configuration();
        Map<String, Object> parameters = Map.of(
            "workspaceId", 7,
            "orgWorkspaceIdsJson", "[7,9]",
            "keys", List.of(
                new DuplicateNameKey("ada lovelace", "%ada%lovelace%"),
                new DuplicateNameKey("山田 太郎", "%山田%太郎%")),
            "perKeyLimit", 51);
        String people = sql(
            configuration, "findVisiblePersonNameMatches", parameters);
        String companies = sql(
            configuration, "findVisibleCompanyNameMatches", parameters);

        assertTrue(people.contains("p.name LIKE ?"));
        assertTrue(people.contains("'name' AS kind"));
        assertTrue(people.contains("LIMIT ?"));
        assertTrue(people.contains("UNION ALL"));
        assertTrue(people.contains("p.suspended_at IS NULL"));
        assertTrue(people.contains("p.provision_ceased_at IS NULL"));
        assertTrue(people.contains("person_share ps"));
        assertTrue(people.contains(
            "JOIN JSON_TABLE(?, '$[*]' COLUMNS(id INT PATH '$')) org_workspace "
                + "ON org_workspace.id = p.workspace_id"));
        assertFalse(people.contains("normalized_name"));
        assertFalse(people.contains("JOIN workspace "));
        assertTrue(companies.contains("c.name LIKE ?"));
        assertTrue(companies.contains("company_share cs"));
        assertTrue(companies.contains(
            "JOIN JSON_TABLE(?, '$[*]' COLUMNS(id INT PATH '$')) org_workspace "
                + "ON org_workspace.id = c.workspace_id"));
        assertFalse(companies.contains("normalized_name"));
        assertFalse(companies.contains("JOIN workspace "));
    }

    private static Configuration configuration() throws Exception {
        Configuration configuration = new Configuration();
        configuration.getTypeAliasRegistry().registerAliases(
            "ooo.klae.connex.backend.beans");
        String resource = "mappers/IdentityMapper.xml";
        try (InputStream input =
                IdentityMapperXmlTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input);
            new XMLMapperBuilder(
                input,
                configuration,
                resource,
                configuration.getSqlFragments()).parse();
        }
        return configuration;
    }

    private static String sql(
            Configuration configuration,
            String statement,
            Object parameters) {
        return configuration
            .getMappedStatement(IdentityMapper.class.getName() + "." + statement)
            .getBoundSql(parameters)
            .getSql()
            .replaceAll("\\s+", " ")
            .trim();
    }
}
