package ooo.klae.connex.backend.support;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ooo.klae.connex.backend.dto.MemberScope;
import ooo.klae.connex.backend.dto.WarmthFilter;
import ooo.klae.connex.backend.warmth.RelationshipWarmthModel;

/** Complete invocation manifest shared by frozen Person parity and tenant-catalog execution. */
public final class PersonVisibilityParity {
    public static final List<String> STATEMENTS = List.of(
        "getAllPersons",
        "getProcessablePersons",
        "getPersonsForNetworkReport",
        "getRelationshipScoreAggregates",
        "getRelationshipScoreAggregatesByIds",
        "getRelationshipScoreAggregatesExcludingHistoryImports",
        "getRelationshipEvidenceTotals",
        "getRelationshipEvidenceContributors",
        "getPersonsByCompanyId",
        "getPersonsByTagId",
        "getPersonsByDealId",
        "getPersonById",
        "getAssistantPrimaryPersonById",
        "getVisiblePersonByIdForUpdate",
        "getVisiblePersonByIdForShare",
        "getOwnedArchivedPersonById",
        "getProcessablePersonIds",
        "getByIds",
        "exists",
        "getVisibleIdsIn",
        "getPersonsPage",
        "getPersonIdsFiltered",
        "countPersons",
        "getPersonsFiltered",
        "countsByWarmthBand",
        "distinctCompanies",
        "hasPersonWithoutCompany",
        "getEngagedPersonIds",
        "search");
    private static final Set<String> UNORDERED = Set.of("getProcessablePersonIds", "getEngagedPersonIds", "countsByWarmthBand");

    private PersonVisibilityParity() { }

    /** One original statement with all of its dynamic parameters. */
    public record ReadCase(String statement, Map<String, Object> parameters) { }

    /** Supplies every statement with one person from the ownership, share and lifecycle matrix. */
    public static List<ReadCase> cases(RecordVisibilityPlaneFixture fixture, int index) {
        List<ReadCase> cases = new ArrayList<>();
        for (String statement : STATEMENTS) {
            Map<String, Object> parameters = parameters();
            parameters.put("workspaceId", fixture.workspaceId);
            if ("search".equals(statement)) parameters.put("query", "%");
            parameters.put("id", fixture.personIds.get(index));
            parameters.put("personId", fixture.personIds.get(index));
            parameters.put("ids", fixture.personIds);
            parameters.put("companyId", fixture.companyIds.get(Math.min(index, 5)));
            parameters.put("tagId", fixture.reportTagId);
            parameters.put("dealId", fixture.dealIds.getFirst());
            cases.add(new ReadCase(statement, parameters));
        }
        return cases;
    }

    /** Parameters also used by the database-free frozen SQL checker. */
    public static Map<String, Object> parameters() {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("workspaceId", 7);
        parameters.put("orgWorkspaceIdsJson", "[7,8,9]");
        for (String key : List.of("id", "personId", "companyId", "tagId", "dealId")) {
            parameters.put(key, 41);
        }
        parameters.put("ids", List.of(41, 42));
        for (String key : List.of("query", "companies", "titles", "lifecycleStages", "leadSources", "firstResponseStates")) {
            parameters.put(key, null);
        }
        for (String key : List.of("noCompany", "noLifecycle", "noLeadSource", "noFirstResponse", "archived")) {
            parameters.put(key, false);
        }
        parameters.put("sort", "name");
        parameters.put("dir", "asc");
        parameters.put("memberScope", MemberScope.allTeam());
        parameters.put("warmth", WarmthFilter.fromRequest(null, false, null, "warmth",
            java.time.Instant.parse("2026-10-01T00:00:00Z")));
        parameters.put("reference", java.time.LocalDateTime.of(2026, 10, 1, 0, 0));
        parameters.put("model", RelationshipWarmthModel.current().sqlParameters());
        parameters.put("sourceLimit", 100);
        parameters.put("limit", 100);
        parameters.put("offset", 0);
        for (String key : List.of("excludedActivityIds", "excludedNoteIds", "excludedTaskIds")) {
            parameters.put(key, List.of());
        }
        return parameters;
    }

    /** Captures original results before the tenant transaction can retain locks. */
    public static List<?> legacy(RecordVisibilityPlaneFixture fixture, ReadCase read) {
        return ordered(read.statement(), fixture.legacy.selectList("PersonMapper." + read.statement(), read.parameters()));
    }

    /** Executes only the production statement with the trusted unfiltered organization ceiling. */
    public static List<?> tenant(RecordVisibilityPlaneFixture fixture, ReadCase read, String scope) {
        Map<String, Object> parameters = new HashMap<>(read.parameters());
        parameters.put("orgWorkspaceIdsJson", scope);
        return ordered(read.statement(), fixture.personStatements.selectList(
            "ooo.klae.connex.backend.mappers.PersonMapper." + read.statement(), parameters));
    }

    /** Normalizes only statements whose SQL has no ordering contract. */
    public static List<?> ordered(String statement, List<?> rows) {
        return UNORDERED.contains(statement) ? rows.stream().map(Object::toString).sorted().toList() : rows;
    }
}
