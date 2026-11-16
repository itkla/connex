package ooo.klae.connex.backend.support;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionTemplate;

import ooo.klae.connex.backend.support.LegacyRecordVisibilitySql.WriteResult;

/** Runs the frozen Deal statements and their tenant-only replacements with identical parameters. */
public final class DealVisibilityParity {
    public static final List<String> READS = List.of(
        "getDealPeopleByDealId", "getPrimaryContactsByDealIds", "getAllDealStakeholders",
        "getDealStakeholdersByDealId", "getDealStakeholdersByDealIds", "getDealsPageFiltered",
        "getDealsFiltered", "countDealsFiltered", "dealMetricsFiltered", "getFilteredDealIds",
        "countsByCompany", "countsByPerson", "getDealsByPersonId", "search", "getStageOutcome",
        "getLastNormalStageId");
    public static final List<String> WRITES = List.of("addPerson", "addPersonIfAbsent", "updatePersonRole");
    private static final Set<String> UNORDERED = Set.of("countsByCompany", "countsByPerson", "getDealsByPersonId");

    private DealVisibilityParity() { }

    /** One original statement and the complete parameter map used by both executions. */
    public record ReadCase(String statement, Map<String, Object> parameters) { }

    /** Captures all sixteen live reads for one relationship visibility case. */
    public static List<ReadCase> cases(RecordVisibilityPlaneFixture fixture, int index) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("workspaceId", fixture.workspaceId);
        parameters.put("dealId", fixture.dealIds.get(index));
        parameters.put("dealIds", fixture.dealIds);
        parameters.put("personId", fixture.personIds.get(index));
        parameters.put("pipelineId", fixture.pipelineIds.get(Math.min(index, 4)));
        parameters.put("stageId", fixture.stageIds.get(Math.min(index, 4) * 2));
        parameters.put("query", null);
        parameters.put("sort", null);
        parameters.put("dir", null);
        parameters.put("currency", null);
        parameters.put("pipelineIds", null);
        parameters.put("stageIds", null);
        parameters.put("companyIds", null);
        parameters.put("personIds", null);
        parameters.put("noCompany", false);
        parameters.put("statuses", null);
        parameters.put("riskIds", null);
        parameters.put("memberScope", null);
        parameters.put("segmentIdsJson", null);
        parameters.put("limit", 100);
        parameters.put("offset", 0);
        List<ReadCase> cases = new ArrayList<>();
        for (String statement : READS) {
            cases.add(new ReadCase(statement, new HashMap<>(parameters)));
        }
        return cases;
    }

    /** Executes only the frozen default-catalog SQL. */
    public static List<?> legacy(RecordVisibilityPlaneFixture fixture, ReadCase read) {
        return ordered(read.statement(), fixture.legacy.selectList("DealMapper." + read.statement(), read.parameters()));
    }

    /** Executes the production mapped statement against the selected tenant catalog. */
    public static List<?> tenant(RecordVisibilityPlaneFixture fixture, ReadCase read, String scope) {
        Map<String, Object> parameters = new HashMap<>(read.parameters());
        parameters.put("orgWorkspaceIdsJson", scope);
        return ordered(read.statement(), fixture.dealStatements.selectList(
            "ooo.klae.connex.backend.mappers.DealMapper." + read.statement(), parameters));
    }

    /** Captures production DML and its exact state before rolling back the tenant transaction. */
    public static WriteResult tenantWrite(
            RecordVisibilityPlaneFixture fixture, String statement, Map<String, Object> parameters, String scope) {
        Map<String, Object> scoped = new HashMap<>(parameters);
        scoped.put("orgWorkspaceIdsJson", scope);
        return new TransactionTemplate(fixture.transactionManager).execute(status -> {
            status.setRollbackOnly();
            int rows = fixture.dealStatements.update("ooo.klae.connex.backend.mappers.DealMapper." + statement, scoped);
            return new WriteResult(rows, LegacyRecordVisibilitySql.dealLinks(
                DataSourceUtils.getConnection(fixture.routing), (Integer) parameters.get("dealId")));
        });
    }

    private static List<?> ordered(String statement, List<?> rows) {
        return UNORDERED.contains(statement)
            ? rows.stream().map(Object::toString).sorted().toList() : rows;
    }
}
