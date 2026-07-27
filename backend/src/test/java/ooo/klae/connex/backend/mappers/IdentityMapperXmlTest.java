package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.dto.DuplicateIdentityKey;
import ooo.klae.connex.backend.dto.DuplicateNameKey;

class IdentityMapperXmlTest {

    @Test
    void identityPreflightFiltersCurrentVisibleProcessableRecordsBeforeItsBound() throws Exception {
        Configuration configuration = configuration();
        Map<String, Object> parameters = Map.of(
            "workspaceId", 7,
            "orgWorkspaceIdsJson", "[7,9]",
            "keys", List.of(
                new DuplicateIdentityKey("email", "probe@example.com"),
                new DuplicateIdentityKey("phone", "+819012345678")),
            "perKeyLimit", 51);
        String people = sql(configuration, "findVisiblePersonIdentityMatches", parameters);
        String companies = sql(configuration, "findVisibleCompanyIdentityMatches", parameters);

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
        assertTrue(companies.indexOf("company_share cs") < companies.indexOf("WHERE match_rank <= ?"));
    }

    @Test
    void namePreflightUsesBoundParametersAndTheSameVisibilityCeiling() throws Exception {
        Configuration configuration = configuration();
        Map<String, Object> parameters = Map.of(
            "workspaceId", 7,
            "orgWorkspaceIdsJson", "[7,9]",
            "keys", List.of(
                new DuplicateNameKey("ada lovelace"),
                new DuplicateNameKey("山田 太郎")),
            "perKeyLimit", 51);
        String people = sql(configuration, "findVisiblePersonNameMatches", parameters);
        String companies = sql(configuration, "findVisibleCompanyNameMatches", parameters);

        assertTrue(people.contains("WITH requested_names AS ( SELECT ? AS normalized_name"));
        assertTrue(people.contains("UNION ALL SELECT ? AS normalized_name"));
        assertTrue(people.contains(
            "JOIN person p ON p.normalized_name = requested_names.normalized_name"));
        assertTrue(people.contains("p.suspended_at IS NULL"));
        assertTrue(people.contains("p.provision_ceased_at IS NULL"));
        assertTrue(people.contains("person_share ps"));
        assertTrue(people.contains(
            "JOIN JSON_TABLE(?, '$[*]' COLUMNS(id INT PATH '$')) org_workspace "
                + "ON org_workspace.id = p.workspace_id"));
        assertFalse(people.contains("JOIN workspace "));
        assertTrue(companies.contains(
            "JOIN company c ON c.normalized_name = requested_names.normalized_name"));
        assertTrue(companies.contains("company_share cs"));
        assertTrue(companies.contains(
            "JOIN JSON_TABLE(?, '$[*]' COLUMNS(id INT PATH '$')) org_workspace "
                + "ON org_workspace.id = c.workspace_id"));
        assertFalse(companies.contains("JOIN workspace "));
    }

    @Test
    void liveIntakeRevalidatesTheExactTenantParentAndLeavesPurposeUnassigned() throws Exception {
        Configuration configuration = configuration();
        Map<String, Object> parameters = Map.ofEntries(
            Map.entry("workspaceId", 7),
            Map.entry("personId", 11),
            Map.entry("companyId", 13),
            Map.entry("rawValue", "Probe@Example.com"),
            Map.entry("normalizedValue", "probe@example.com"),
            Map.entry("sourceSystem", "interactive_create"),
            Map.entry("sourceRowRef", "csv-row:1"),
            Map.entry("acquiredAt", LocalDateTime.of(2026, 7, 26, 12, 0)),
            Map.entry("supersededAt", LocalDateTime.of(2026, 7, 26, 13, 0)));
        String personUpsert = sql(
            configuration, "upsertPersonEmailIdentity", parameters);
        String personSupersede = sql(
            configuration, "supersedePersonEmailIdentities", parameters);
        String companyUpsert = sql(
            configuration, "upsertCompanyDomainIdentity", parameters);

        assertTrue(personUpsert.contains("WHERE p.workspace_id = ? AND p.id = ?"));
        assertTrue(personUpsert.contains(
            "CAST(p.email AS BINARY) = CAST(? AS BINARY)"));
        assertTrue(personUpsert.contains("p.suspended_at IS NULL"));
        assertTrue(personUpsert.contains("p.provision_ceased_at IS NULL"));
        assertTrue(personUpsert.contains("?, NULL FROM person p"));
        assertTrue(personUpsert.endsWith(
            "ON DUPLICATE KEY UPDATE superseded_at = NULL"));
        assertTrue(personSupersede.contains(
            "pi.workspace_id = ? AND pi.person_id = ?"));
        assertTrue(personSupersede.contains("pi.superseded_at IS NULL"));
        assertTrue(companyUpsert.contains("WHERE c.workspace_id = ? AND c.id = ?"));
        assertTrue(companyUpsert.contains(
            "CAST(c.website AS BINARY) = CAST(? AS BINARY)"));
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
