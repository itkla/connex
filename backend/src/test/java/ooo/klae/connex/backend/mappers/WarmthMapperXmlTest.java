package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Note;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Tag;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.MemberScope;
import ooo.klae.connex.backend.dto.WarmthFilter;
import ooo.klae.connex.backend.warmth.RelationshipWarmthModel;

/** Verifies warmth aggregate and evidence mapper statements bind the shared model contract. */
class WarmthMapperXmlTest {
    private static final Instant NOW = Instant.parse("2026-06-30T00:00:00Z");

    @Test
    void warmthStatementsParseAndBindModelParameters() throws Exception {
        Configuration configuration = configuration();
        LocalDateTime reference = LocalDateTime.of(2026, 6, 30, 0, 0);
        Map<String, Object> personParameters = Map.of(
            "workspaceId", 7,
            "personId", 11,
            "reference", reference,
            "model", RelationshipWarmthModel.current().sqlParameters(),
            "sourceLimit", 100_001,
            "limit", 20
        );
        Map<String, Object> companyParameters = Map.of(
            "workspaceId", 7,
            "companyId", 13,
            "reference", reference,
            "model", RelationshipWarmthModel.current().sqlParameters(),
            "sourceLimit", 100_001,
            "limit", 20
        );

        String personAggregate = sql(
            configuration, PersonMapper.class, "getRelationshipScoreAggregates", personParameters);
        String companyAggregate = sql(
            configuration, CompanyMapper.class, "getRelationshipScoreAggregates", companyParameters);
        String personEvidenceTotals = sql(
            configuration, PersonMapper.class, "getRelationshipEvidenceTotals", personParameters);
        String companyEvidenceTotals = sql(
            configuration, CompanyMapper.class, "getRelationshipEvidenceTotals", companyParameters);
        String personEvidence = sql(
            configuration, PersonMapper.class, "getRelationshipEvidenceContributors", personParameters);
        String companyEvidence = sql(
            configuration, CompanyMapper.class, "getRelationshipEvidenceContributors", companyParameters);
        String personPrivateNotes = sql(
            configuration,
            NoteMapper.class,
            "countOwnPrivateNotesForPersonEvidence",
            Map.of(
                "workspaceId", 7,
                "personId", 11,
                "currentUserId", 17,
                "reference", reference,
                "sourceLimit", 100_001
            )
        );
        String companyPrivateNotes = sql(
            configuration,
            NoteMapper.class,
            "countOwnPrivateNotesForCompanyEvidence",
            Map.of(
                "workspaceId", 7,
                "companyId", 13,
                "currentUserId", 17,
                "reference", reference,
                "sourceLimit", 100_001
            )
        );

        assertTrue(personAggregate.contains("POW(?, -touch.age_days / ?)"));
        assertTrue(companyAggregate.contains("POW(?, -touch.age_days / ?)"));
        assertTrue(personEvidenceTotals.contains("POW(?, -capped.age_days / ?)"));
        assertTrue(companyEvidenceTotals.contains("POW(?, -capped.age_days / ?)"));
        assertTrue(personEvidence.contains("POW(?, -aged.age_days / ?)"));
        assertTrue(companyEvidence.contains("POW(?, -aged.age_days / ?)"));
        assertTrue(personEvidence.contains("LIMIT ?"));
        assertTrue(companyEvidence.contains("LIMIT ?"));
        assertTrue(personEvidence.contains("n.visibility = 'workspace'"));
        assertTrue(companyEvidence.contains("n.visibility = 'workspace'"));
        assertTrue(personEvidenceTotals.contains("n.visibility = 'workspace'"));
        assertTrue(companyEvidenceTotals.contains("n.visibility = 'workspace'"));
        assertTrue(personPrivateNotes.contains("n.author_id = ?"));
        assertTrue(personPrivateNotes.contains("n.visibility = 'private'"));
        assertTrue(personPrivateNotes.contains("LIMIT ?"));
        assertTrue(companyPrivateNotes.contains("n.author_id = ?"));
        assertTrue(companyPrivateNotes.contains("n.visibility = 'private'"));
        assertTrue(companyPrivateNotes.contains("LIMIT ?"));
        assertEquals(10, timeout(
            configuration, NoteMapper.class, "countOwnPrivateNotesForPersonEvidence"));
        assertEquals(10, timeout(
            configuration, NoteMapper.class, "countOwnPrivateNotesForCompanyEvidence"));
    }

    @Test
    void evidenceStatementsCapTheirSourceSetAndRunWithoutWindowAggregates() throws Exception {
        Configuration configuration = configuration();
        LocalDateTime reference = LocalDateTime.of(2026, 6, 30, 0, 0);
        Map<String, Object> personParameters = Map.of(
            "workspaceId", 7,
            "personId", 11,
            "reference", reference,
            "model", RelationshipWarmthModel.current().sqlParameters(),
            "sourceLimit", 100_001,
            "limit", 20
        );
        Map<String, Object> companyParameters = Map.of(
            "workspaceId", 7,
            "companyId", 13,
            "reference", reference,
            "model", RelationshipWarmthModel.current().sqlParameters(),
            "sourceLimit", 100_001,
            "limit", 20
        );

        for (String statement : new String[] {
                "getRelationshipEvidenceTotals",
                "getRelationshipEvidenceContributors"}) {
            String personSql = sql(configuration, PersonMapper.class, statement, personParameters);
            String companySql = sql(configuration, CompanyMapper.class, statement, companyParameters);
            assertFalse(personSql.contains("OVER ()"), statement + " still uses window aggregates");
            assertFalse(companySql.contains("OVER ()"), statement + " still uses window aggregates");
            assertTrue(personSql.contains("LIMIT ?"), statement + " does not cap its source set");
            assertTrue(companySql.contains("LIMIT ?"), statement + " does not cap its source set");
            assertEquals(10, timeout(configuration, PersonMapper.class, statement));
            assertEquals(10, timeout(configuration, CompanyMapper.class, statement));
        }
    }

    /**
     * An ordinary browser page must not pay for the warmth aggregate, and a warmth page must bind
     * the model boundaries rather than inlining tuned literals into the mapper.
     */
    @Test
    void browserStatementsJoinTheWarmthAggregateOnlyWhenWarmthWasRequested() throws Exception {
        Configuration configuration = configuration();
        Map<String, Object> unfiltered = browserParameters(null);
        Map<String, Object> sorted = browserParameters(
            WarmthFilter.fromRequest(null, false, null, "warmth", NOW));
        Map<String, Object> banded = browserParameters(
            WarmthFilter.fromRequest(List.of("hot", "cold"), true, 30, "warmth", NOW));

        for (String statement : new String[] {
                "getPersonsPage", "countPersons", "getPersonIdsFiltered", "getPersonsFiltered"}) {
            assertFalse(sql(configuration, PersonMapper.class, statement, unfiltered)
                .contains("raw_weight"), statement + " scores an unfiltered page");
            assertTrue(sql(configuration, PersonMapper.class, statement, sorted)
                .contains("raw_weight"), statement + " does not join the aggregate");
        }
        for (String statement : new String[] {
                "getCompaniesPage", "countCompanies", "getCompanyIdsFiltered", "getCompaniesFiltered"}) {
            assertFalse(sql(configuration, CompanyMapper.class, statement, unfiltered)
                .contains("raw_weight"), statement + " scores an unfiltered page");
            assertTrue(sql(configuration, CompanyMapper.class, statement, sorted)
                .contains("raw_weight"), statement + " does not join the aggregate");
        }

        String personSorted = collapsed(
            sql(configuration, PersonMapper.class, "getPersonsPage", sorted));
        assertTrue(personSorted.contains("ORDER BY COALESCE(w.raw_weight, 0.0) DESC"));
        assertTrue(personSorted.contains("POW(?, -touch.age_days / ?)"));

        String personBanded = collapsed(
            sql(configuration, PersonMapper.class, "getPersonIdsFiltered", banded));
        assertTrue(personBanded.contains("WHEN w.last_touch_at IS NULL THEN '__none__'"));
        assertTrue(personBanded.contains("OR w.last_touch_at IS NULL"));
        assertTrue(personBanded.contains("ROUND(? * LOG(?, w.raw_weight / ?)) <= ?"));
        assertFalse(personBanded.contains("0.913"), "band boundaries must stay model-bound");

        String companyBanded = collapsed(
            sql(configuration, CompanyMapper.class, "getCompanyIdsFiltered", banded));
        assertTrue(companyBanded.contains("WHEN w.last_touch_at IS NULL THEN '__none__'"));
        assertTrue(companyBanded.contains("ROUND(? * LOG(?, w.raw_weight / ?)) <= ?"));
    }

    /**
     * The smart-segment company page shares the browser's sort whitelist but joins no warmth
     * aggregate, so an unsupported warmth sort there must fall through to the default column.
     */
    @Test
    void theSegmentCompanyPageNeverEmitsTheWarmthSortColumn() throws Exception {
        Configuration configuration = configuration();
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("workspaceId", 7);
        parameters.put("segmentIdsJson", "[1,2]");
        parameters.put("sort", "warmth");
        parameters.put("dir", "desc");
        parameters.put("limit", 25);
        parameters.put("offset", 0);

        String sql = collapsed(
            sql(configuration, CompanyMapper.class, "getSegmentCompaniesPage", parameters));

        assertFalse(sql.contains("raw_weight"));
        assertTrue(sql.contains("ORDER BY c.name DESC"));
    }

    @Test
    void theWarmthBandFacetGroupsEveryVisibleRecordWithoutInliningModelLiterals() throws Exception {
        Configuration configuration = configuration();
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("workspaceId", 7);
        parameters.put("warmth", WarmthFilter.forScoring(NOW));

        for (Class<?> mapper : new Class<?>[] {PersonMapper.class, CompanyMapper.class}) {
            String sql = collapsed(sql(configuration, mapper, "countsByWarmthBand", parameters));
            assertTrue(sql.contains("raw_weight"), mapper.getSimpleName());
            assertTrue(sql.contains("'__none__'"), mapper.getSimpleName());
            assertTrue(sql.contains("GROUP BY `key`"), mapper.getSimpleName());
            assertTrue(sql.contains("archived_at IS NULL"), mapper.getSimpleName());
        }
    }

    private static String collapsed(String sql) {
        return sql.replaceAll("\\s+", " ").trim();
    }

    private static Map<String, Object> browserParameters(WarmthFilter warmth) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("workspaceId", 7);
        parameters.put("memberScope", MemberScope.allTeam());
        parameters.put("archived", false);
        parameters.put("noCompany", false);
        parameters.put("noLifecycle", false);
        parameters.put("noLeadSource", false);
        parameters.put("noFirstResponse", false);
        parameters.put("noIndustry", false);
        parameters.put("sort", warmth == null ? "name" : "warmth");
        parameters.put("dir", "desc");
        parameters.put("warmth", warmth);
        parameters.put("limit", 25);
        parameters.put("offset", 0);
        return parameters;
    }

    private static Integer timeout(Configuration configuration, Class<?> mapper, String statement) {
        return configuration.getMappedStatement(mapper.getName() + "." + statement).getTimeout();
    }

    private Configuration configuration() throws Exception {
        Configuration configuration = new Configuration();
        configuration.getTypeAliasRegistry().registerAlias("Company", Company.class);
        configuration.getTypeAliasRegistry().registerAlias("Deal", Deal.class);
        configuration.getTypeAliasRegistry().registerAlias("Note", Note.class);
        configuration.getTypeAliasRegistry().registerAlias("Person", Person.class);
        configuration.getTypeAliasRegistry().registerAlias("Tag", Tag.class);
        configuration.getTypeAliasRegistry().registerAlias("User", User.class);
        for (String resource : new String[] {
                "mappers/PersonMapper.xml",
                "mappers/CompanyMapper.xml",
                "mappers/NoteMapper.xml"}) {
            try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
                assertNotNull(input);
                new XMLMapperBuilder(
                    input, configuration, resource, configuration.getSqlFragments()).parse();
            }
        }
        return configuration;
    }

    private static String sql(
            Configuration configuration,
            Class<?> mapper,
            String statement,
            Map<String, Object> parameters) {
        return configuration.getMappedStatement(mapper.getName() + "." + statement)
            .getBoundSql(parameters)
            .getSql();
    }
}
