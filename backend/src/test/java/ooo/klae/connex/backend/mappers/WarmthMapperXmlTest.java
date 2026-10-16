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
    private static final String[] SEGMENT_STATEMENTS = {
        "getSegmentCompaniesPage", "countSegmentCompanies", "getSegmentCompanyIdsFiltered"};

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
            assertTrue(sql(configuration, PersonMapper.class, statement, banded)
                .contains("raw_weight"), statement + " does not join the aggregate when filtering");
        }
        for (String statement : new String[] {
                "getCompaniesPage", "countCompanies", "getCompanyIdsFiltered", "getCompaniesFiltered"}) {
            assertFalse(sql(configuration, CompanyMapper.class, statement, unfiltered)
                .contains("raw_weight"), statement + " scores an unfiltered page");
            assertTrue(sql(configuration, CompanyMapper.class, statement, banded)
                .contains("raw_weight"), statement + " does not join the aggregate when filtering");
        }

        assertTrue(sql(configuration, PersonMapper.class, "getPersonsPage", sorted)
            .contains("raw_weight"), "the page needs the column a warmth sort orders on");
        assertTrue(sql(configuration, CompanyMapper.class, "getCompaniesPage", sorted)
            .contains("raw_weight"), "the page needs the column a warmth sort orders on");
        for (String statement : new String[] {
                "countPersons", "getPersonIdsFiltered", "getPersonsFiltered"}) {
            assertFalse(sql(configuration, PersonMapper.class, statement, sorted)
                .contains("raw_weight"), statement + " pays for a sort it cannot observe");
        }
        for (String statement : new String[] {
                "countCompanies", "getCompanyIdsFiltered", "getCompaniesFiltered"}) {
            assertFalse(sql(configuration, CompanyMapper.class, statement, sorted)
                .contains("raw_weight"), statement + " pays for a sort it cannot observe");
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
     * The smart-segment company statements apply the same warmth contract as the plain browser:
     * the page joins the aggregate for a warmth filter or sort, while the count and id selection
     * join it only when a band, the no-history bucket, or a horizon narrows the rows.
     */
    @Test
    void segmentCompanyStatementsJoinTheWarmthAggregateOnlyWhenWarmthWasRequested() throws Exception {
        Configuration configuration = configuration();
        Map<String, Object> unfiltered = segmentParameters(null, MemberScope.allTeam());
        Map<String, Object> sorted = segmentParameters(
            WarmthFilter.fromRequest(null, false, null, "warmth", NOW), MemberScope.allTeam());
        Map<String, Object> banded = segmentParameters(
            WarmthFilter.fromRequest(List.of("hot", "cold"), true, 30, "warmth", NOW),
            MemberScope.allTeam());

        for (String statement : SEGMENT_STATEMENTS) {
            String unfilteredSql = sql(configuration, CompanyMapper.class, statement, unfiltered);
            assertFalse(unfilteredSql.contains("raw_weight"), statement + " scores an unfiltered segment");
            assertTrue(unfilteredSql.contains("JSON_TABLE"), statement + " lost the segment id join");
            assertTrue(sql(configuration, CompanyMapper.class, statement, banded)
                .contains("raw_weight"), statement + " does not join the aggregate when filtering");
        }
        assertTrue(collapsed(sql(configuration, CompanyMapper.class, "getSegmentCompaniesPage", sorted))
            .contains("ORDER BY COALESCE(w.raw_weight, 0.0) DESC"));
        for (String statement : new String[] {"countSegmentCompanies", "getSegmentCompanyIdsFiltered"}) {
            assertFalse(sql(configuration, CompanyMapper.class, statement, sorted)
                .contains("raw_weight"), statement + " pays for a sort it cannot observe");
        }

        String segmentBanded = collapsed(
            sql(configuration, CompanyMapper.class, "getSegmentCompanyIdsFiltered", banded));
        assertTrue(segmentBanded.contains("WHEN w.last_touch_at IS NULL THEN '__none__'"));
        assertTrue(segmentBanded.contains("OR w.last_touch_at IS NULL"));
        assertTrue(segmentBanded.contains("ROUND(? * LOG(?, w.raw_weight / ?)) <= ?"));
        assertTrue(segmentBanded.contains("c.workspace_id = ?"));
        assertFalse(segmentBanded.contains("0.913"), "band boundaries must stay model-bound");
    }

    /** Every smart-segment company statement narrows by the browser's owner scope. */
    @Test
    void segmentCompanyStatementsApplyTheOwnerScope() throws Exception {
        Configuration configuration = configuration();
        Map<String, Object> mine = segmentParameters(null, MemberScope.fromRequest("me", null, 17));
        Map<String, Object> members = segmentParameters(
            null, MemberScope.fromRequest("members", List.of(3, 5), 17));
        Map<String, Object> unassigned = segmentParameters(
            null, MemberScope.fromRequest("unassigned", null, 17));
        Map<String, Object> team = segmentParameters(null, MemberScope.allTeam());

        for (String statement : SEGMENT_STATEMENTS) {
            assertTrue(collapsed(sql(configuration, CompanyMapper.class, statement, mine))
                .contains("AND c.owner_id = ?"), statement);
            assertTrue(collapsed(sql(configuration, CompanyMapper.class, statement, members))
                .contains("AND c.owner_id IN"), statement);
            assertTrue(collapsed(sql(configuration, CompanyMapper.class, statement, unassigned))
                .contains("AND c.owner_id IS NULL"), statement);
            assertFalse(collapsed(sql(configuration, CompanyMapper.class, statement, team))
                .contains("AND c.owner_id"), statement);
        }
    }

    /**
     * The warmth sort column exists only when the aggregate was joined, so a warmth sort that
     * arrives without a resolved filter falls through to the name column instead of naming a
     * missing alias.
     */
    @Test
    void anUnresolvedWarmthSortFallsThroughToTheNameColumn() throws Exception {
        Configuration configuration = configuration();
        Map<String, Object> segment = segmentParameters(null, MemberScope.allTeam());
        segment.put("sort", "warmth");
        Map<String, Object> browser = browserParameters(null);
        browser.put("sort", "warmth");

        for (Map.Entry<String, Map<String, Object>> page : Map.of(
                "getSegmentCompaniesPage", segment, "getCompaniesPage", browser).entrySet()) {
            String sql = collapsed(sql(configuration, CompanyMapper.class, page.getKey(), page.getValue()));
            assertFalse(sql.contains("raw_weight"), page.getKey());
            assertTrue(sql.contains("ORDER BY c.name DESC"), page.getKey());
        }
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
            assertEquals(10, timeout(configuration, mapper, "countsByWarmthBand"),
                mapper.getSimpleName() + " runs the aggregate without a statement timeout");
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

    private static Map<String, Object> segmentParameters(WarmthFilter warmth, MemberScope memberScope) {
        Map<String, Object> parameters = browserParameters(warmth);
        parameters.put("segmentIdsJson", "[1,2]");
        parameters.put("memberScope", memberScope);
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
