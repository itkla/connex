package ooo.klae.connex.backend.support;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ooo.klae.connex.backend.dto.MemberScope;
import ooo.klae.connex.backend.dto.WarmthFilter;
import ooo.klae.connex.backend.warmth.RelationshipWarmthModel;

/** Shared invocation manifest for every Company crossing and its frozen pre-conversion statement. */
public final class CompanyVisibilityParity {
    public static final LocalDateTime REFERENCE = LocalDateTime.of(2026, 10, 1, 0, 0);
    public static final List<String> STATEMENTS = List.of(
        "getAllCompanies", "findVisibleNameCandidates", "getRelationshipScoreAggregates",
        "getRelationshipScoreAggregatesByIds", "getRelationshipEvidenceTotals",
        "getRelationshipEvidenceContributors", "getCompaniesPage", "countCompanies",
        "getSegmentCompaniesPage", "countSegmentCompanies", "getSegmentCompanyIdsFiltered",
        "getCompanyIdsFiltered", "getCompaniesFiltered", "countsByWarmthBand", "distinctIndustries",
        "hasCompanyWithoutIndustry", "getCompaniesByTagId", "getCompanyById",
        "getVisibleCompanyByIdForUpdate", "getByIds", "exists", "getVisibleIdsIn", "search");

    private CompanyVisibilityParity() { }

    /** One invocation, including the original dynamic SQL parameters, without a control scope. */
    public record ReadCase(String statement, Map<String, Object> parameters) { }

    /** Exercises every statement for one requested subject, including the statement's nested evidence ceiling. */
    public static List<ReadCase> cases(RecordVisibilityPlaneFixture fixture, int subjectId) {
        List<ReadCase> cases = new ArrayList<>();
        for (String statement : STATEMENTS) {
            Map<String, Object> parameters = new HashMap<>();
            parameters.put("workspaceId", fixture.workspaceId);
            parameters.put("id", subjectId);
            parameters.put("companyId", subjectId);
            parameters.put("ids", List.of(subjectId));
            parameters.put("segmentIdsJson", "[" + subjectId + "]");
            parameters.put("tagId", fixture.reportTagId);
            parameters.put("pattern", "%Company%");
            parameters.put("normalizedName", "company a owned");
            parameters.put("query", statement.equals("search") ? "%Company%" : null);
            parameters.put("sort", "name");
            parameters.put("dir", "asc");
            parameters.put("industry", null);
            parameters.put("noIndustry", false);
            parameters.put("memberScope", MemberScope.allTeam());
            parameters.put("archived", false);
            parameters.put("warmth", statement.equals("countsByWarmthBand") ? warmth() : null);
            parameters.put("reference", REFERENCE);
            parameters.put("model", RelationshipWarmthModel.current().sqlParameters());
            parameters.put("sourceLimit", 100);
            parameters.put("limit", 100);
            parameters.put("offset", 0);
            cases.add(new ReadCase(statement, parameters));
        }
        return cases;
    }

    /** Adds optional joins, archive branches, member scopes, filter combinations and boundary limits. */
    public static List<ReadCase> filteredCases(RecordVisibilityPlaneFixture fixture) {
        List<ReadCase> cases = new ArrayList<>();
        Set<String> pages = Set.of("getCompaniesPage", "countCompanies", "getCompanyIdsFiltered", "getCompaniesFiltered",
            "getSegmentCompaniesPage", "countSegmentCompanies", "getSegmentCompanyIdsFiltered");
        for (ReadCase base : cases(fixture, fixture.companyIds.getFirst())) {
            if (!pages.contains(base.statement())) {
                continue;
            }
            for (boolean archived : List.of(false, true)) {
                for (int limit : List.of(0, 1, 2, 100)) {
                    for (int offset : List.of(0, 1)) {
                        for (MemberScope members : List.of(MemberScope.allTeam(),
                                MemberScope.fromRequest("me", null, fixture.userId),
                                MemberScope.fromRequest("unassigned", null, fixture.userId))) {
                            for (WarmthFilter warmth : List.of(warmth(), new WarmthFilter(
                                    Set.of("hot", "warm", "cool", "cold"), true, null,
                                    REFERENCE, RelationshipWarmthModel.current().sqlParameters()))) {
                                Map<String, Object> parameters = new HashMap<>(base.parameters());
                                parameters.put("ids", fixture.companyIds);
                                parameters.put("segmentIdsJson", fixture.companyIds.toString());
                                parameters.put("memberScope", members);
                                parameters.put("archived", archived);
                                parameters.put("warmth", warmth);
                                parameters.put("sort", "warmth");
                                parameters.put("dir", "desc");
                                parameters.put("limit", limit);
                                parameters.put("offset", offset);
                                cases.add(new ReadCase(base.statement(), parameters));
                            }
                        }
                    }
                }
            }
        }
        return cases;
    }

    /** Captures legacy results before a tenant locking statement can retain row locks. */
    public static List<?> legacy(RecordVisibilityPlaneFixture fixture, ReadCase read) {
        return fixture.legacy.selectList("CompanyMapper." + read.statement(), read.parameters());
    }

    /** Executes the production MyBatis statement with the trusted scope bound as an ordinary parameter. */
    public static List<?> tenant(RecordVisibilityPlaneFixture fixture, String scope, ReadCase read) {
        Map<String, Object> parameters = new HashMap<>(read.parameters());
        parameters.put("orgWorkspaceIdsJson", scope);
        return fixture.companyStatements.selectList(
            "ooo.klae.connex.backend.mappers.CompanyMapper." + read.statement(), parameters);
    }

    /** Sort-only warmth emits the same aggregate without restricting matching records. */
    public static WarmthFilter warmth() {
        return WarmthFilter.fromRequest(null, false, null, "warmth", Instant.parse("2026-10-01T00:00:00Z"));
    }
}
