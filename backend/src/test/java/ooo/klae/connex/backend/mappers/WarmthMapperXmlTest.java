package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.time.LocalDateTime;
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
import ooo.klae.connex.backend.warmth.RelationshipWarmthModel;

/** Verifies warmth aggregate and evidence mapper statements bind the shared model contract. */
class WarmthMapperXmlTest {

    @Test
    void warmthStatementsParseAndBindModelParameters() throws Exception {
        Configuration configuration = configuration();
        LocalDateTime reference = LocalDateTime.of(2026, 6, 30, 0, 0);
        Map<String, Object> personParameters = Map.of(
            "workspaceId", 7,
            "personId", 11,
            "reference", reference,
            "model", RelationshipWarmthModel.current().sqlParameters(),
            "limit", 20
        );
        Map<String, Object> companyParameters = Map.of(
            "workspaceId", 7,
            "companyId", 13,
            "reference", reference,
            "model", RelationshipWarmthModel.current().sqlParameters(),
            "limit", 20
        );

        String personAggregate = sql(
            configuration, PersonMapper.class, "getRelationshipScoreAggregates", personParameters);
        String companyAggregate = sql(
            configuration, CompanyMapper.class, "getRelationshipScoreAggregates", companyParameters);
        String personEvidence = sql(
            configuration, PersonMapper.class, "getRelationshipEvidence", personParameters);
        String companyEvidence = sql(
            configuration, CompanyMapper.class, "getRelationshipEvidence", companyParameters);
        String personPrivateNotes = sql(
            configuration,
            NoteMapper.class,
            "countOwnPrivateNotesForPersonEvidence",
            Map.of(
                "workspaceId", 7,
                "personId", 11,
                "currentUserId", 17,
                "reference", reference
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
                "reference", reference
            )
        );

        assertTrue(personAggregate.contains("POW(?, -touch.age_days / ?)"));
        assertTrue(companyAggregate.contains("POW(?, -touch.age_days / ?)"));
        assertTrue(personEvidence.contains("POW(?, -aged.age_days / ?)"));
        assertTrue(companyEvidence.contains("POW(?, -aged.age_days / ?)"));
        assertTrue(personEvidence.contains("LIMIT ?"));
        assertTrue(companyEvidence.contains("LIMIT ?"));
        assertTrue(personEvidence.contains("n.visibility = 'workspace'"));
        assertTrue(companyEvidence.contains("n.visibility = 'workspace'"));
        assertTrue(personPrivateNotes.contains("n.author_id = ?"));
        assertTrue(personPrivateNotes.contains("n.visibility = 'private'"));
        assertTrue(companyPrivateNotes.contains("n.author_id = ?"));
        assertTrue(companyPrivateNotes.contains("n.visibility = 'private'"));
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
