package ooo.klae.connex.backend.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.apache.ibatis.scripting.defaults.DefaultParameterHandler;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.jdbc.core.ColumnMapRowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.mockito.ArgumentCaptor;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import ooo.klae.connex.backend.beans.Activity;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.dto.DealRiskMatchDto;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.MemberScope;
import ooo.klae.connex.backend.exceptions.BadRequestException;

/** Exercises complete risk-filter counts and bounded assessment beyond the old candidate ceiling. */
class DealRiskPagingTest extends AbstractServiceTest {
    @Autowired private DealService service;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private SqlSessionFactory sqlSessionFactory;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoSpyBean private DealRiskService risks;

    @Test
    void largeRiskListPagesCountsMetricsAndExportsWithoutCandidateRefusal() {
        var pipeline = newPipeline();
        var stage = newStage(pipeline, 0);
        var company = newCompany();
        String prefix = "Risk paging " + unique();
        List<Deal> deals = IntStream.range(0, 1205).mapToObj(index -> {
            Deal deal = new Deal();
            deal.setWorkspaceId(workspace.getId());
            deal.setOwnerId(currentUser.getId());
            deal.setName(prefix + String.format(" %04d", index));
            deal.setValue(BigDecimal.TEN);
            deal.setCurrency("JPY");
            deal.setPipelineId(pipeline.getId());
            deal.setStageId(stage.getId());
            deal.setCompanyId(company.getId());
            deal.setExpectedCloseDate(index < 995 ? "2000-01-01" : "2200-01-01");
            return deal;
        }).toList();
        dealMapper.insertBatch(deals);
        Person stakeholder = newPerson(company);
        jdbc.update("INSERT INTO deal_person (deal_id, person_id, role) SELECT id, ?, 'Champion' FROM deal WHERE workspace_id = ? AND name LIKE ? AND expected_close_date = '2000-01-01'",
            stakeholder.getId(), workspace.getId(), prefix + "%");
        var activity = new Activity();
        activity.setWorkspaceId(workspace.getId());
        activity.setPerson(stakeholder);
        activity.setType("meeting");
        activity.setSubject("Past interaction");
        activity.setCreatedBy(currentUser);
        activity.setTimestamp("2000-01-01 12:00:00");
        activityMapper.insert(activity);
        jdbc.update("UPDATE deal SET risk_excluded = TRUE WHERE id = ?", deals.get(990).getId());
        jdbc.update("UPDATE deal SET won = TRUE, closed_at = CURRENT_TIMESTAMP WHERE id = ?", deals.get(991).getId());
        jdbc.update("UPDATE deal SET owner_id = NULL WHERE id = ?", deals.get(992).getId());
        MemberScope me = MemberScope.fromRequest("me", null, currentUser.getId());
        String query = prefix + "%";

        var first = service.queryDealsPage(query, "name", "desc", "JPY", List.of(pipeline.getId()),
            List.of(stage.getId()), List.of(company.getId()), null, false, null, List.of("high"), me, 25, 0);
        var last = service.queryDealsPage(query, "name", "desc", "JPY", List.of(pipeline.getId()),
            List.of(stage.getId()), List.of(company.getId()), null, false, null, List.of("high"), me, 25, 990);
        ArgumentCaptor<List<Integer>> batches = ArgumentCaptor.captor();
        verify(risks, times(10)).assessDeals(eq(workspace.getId()), batches.capture(), isNull(), any());
        assertThat(batches.getAllValues()).allSatisfy(batch -> assertThat(batch).hasSizeLessThanOrEqualTo(250));
        assertThat(first.total()).isEqualTo(992);
        assertThat(first.items()).hasSize(25);
        assertThat(first.items().getFirst().getId()).isEqualTo(deals.get(994).getId());
        assertThat(last.total()).isEqualTo(992);
        assertThat(last.items()).extracting(Deal::getId).containsExactly(deals.get(1).getId(), deals.getFirst().getId());
        var empty = service.queryDealsPage(query, "name", "asc", "JPY", null, null, null, null,
            false, null, List.of("high"), me, 25, 2000);
        assertThat(empty.total()).isEqualTo(992);
        assertThat(empty.items()).isEmpty();
        var metrics = service.queryDealMetrics(query, "JPY", null, null, null, null,
            false, null, List.of("high"), me);
        assertThat(metrics.totalCount()).isEqualTo(992);
        assertThat(metrics.byCurrency()).singleElement().satisfies(currency -> {
            assertThat(currency.openCount()).isEqualTo(992);
            assertThat(currency.openValue()).isEqualTo(9920);
            assertThat(currency.closedCount()).isZero();
        });
        assertThat(service.queryDealsForExport(query, "JPY", null, null, null, null,
            false, null, List.of("high"), me)).hasSize(992);
        assertThat(service.getMatchingDealIds(query, "JPY", null, null, null, null,
            false, null, List.of("high"), me)).hasSize(992);
        var none = service.queryDealsPage(query, "name", "asc", "JPY", null, null, null, null,
            false, null, List.of("none"), me, 25, 0);
        assertThat(none.total()).isEqualTo(2);
        assertThat(none.items()).extracting(Deal::getId)
            .containsExactly(deals.get(990).getId(), deals.get(991).getId());
    }

    @Test
    void riskCandidateQueryEnforcesTenantSegmentMemberAndContactVisibility() {
        var pipeline = newPipeline();
        var stage = newStage(pipeline, 0);
        var company = newCompany();
        Deal local = newDeal(pipeline, stage, company);
        local.setExpectedCloseDate("2000-01-01");
        dealMapper.update(local);
        Person contact = newPerson(company);
        dealMapper.addPerson(workspace.getId(), dealScope(workspace.getId()), local.getId(), contact.getId(), "Champion");
        Workspace other = new Workspace();
        other.setName("Other " + unique());
        other.setSlug("other-" + unique());
        workspaceMapper.insert(other);
        Person hidden = new Person();
        hidden.setWorkspaceId(other.getId());
        hidden.setName("Hidden " + unique());
        personMapper.insert(hidden);
        jdbc.update("INSERT INTO deal_person (deal_id, person_id, role) VALUES (?, ?, ?)", local.getId(), hidden.getId(), "Champion");
        Pipeline foreignPipeline = new Pipeline();
        foreignPipeline.setWorkspaceId(other.getId());
        foreignPipeline.setName("Foreign pipeline " + unique());
        pipelineMapper.insertPipeline(foreignPipeline);
        Stage foreignStage = new Stage();
        foreignStage.setWorkspaceId(other.getId());
        foreignStage.setPipeline(foreignPipeline);
        foreignStage.setName("Foreign stage " + unique());
        foreignStage.setPosition(0);
        pipelineMapper.insertStage(foreignStage);
        Deal foreign = new Deal();
        foreign.setPipelineId(foreignPipeline.getId());
        foreign.setStageId(foreignStage.getId());
        foreign.setWorkspaceId(other.getId());
        foreign.setName("Foreign " + unique());
        foreign.setCurrency("JPY");
        foreign.setValue(BigDecimal.TEN);
        foreign.setExpectedCloseDate("2000-01-01");
        dealMapper.insert(foreign);
        assertThat(dealMapper.getRiskFilterMatches(workspace.getId(), dealScope(workspace.getId()),
            List.of(local.getId(), foreign.getId()), "name"))
            .extracting(row -> row.getDeal().getId()).containsExactly(local.getId());
        String segment = "[" + local.getId() + "," + foreign.getId() + "]";
        var all = MemberScope.allTeam();

        assertThat(dealMapper.getRiskFilterCandidateIds(workspace.getId(), dealScope(workspace.getId()),
            segment, null, null, null, null, null, List.of(contact.getId()), false,
            null, true, all, 0, foreign.getId(), 250)).containsExactly(local.getId());
        assertThat(dealMapper.getRiskFilterCandidateIds(workspace.getId(), dealScope(workspace.getId()),
            segment, null, null, null, null, null, List.of(hidden.getId()), false,
            null, true, all, 0, foreign.getId(), 250)).isEmpty();
        jdbc.update("UPDATE person SET archived_at = CURRENT_TIMESTAMP WHERE id = ?", contact.getId());
        assertThat(service.queryDealsPage(null, null, null, null, null, null, null, List.of(contact.getId()),
            false, null, List.of("high"), all, 25, 0).total()).isZero();
        assertThat(dealMapper.getRiskFilterCandidateIds(workspace.getId(), dealScope(workspace.getId()),
            "[" + foreign.getId() + "]", null, null, null, null, null, null, false,
            null, true, all, 0, foreign.getId(), 250)).isEmpty();
        assertThat(dealMapper.getRiskFilterCandidateIds(workspace.getId(), dealScope(workspace.getId()),
            segment, null, null, null, null, null, null, false,
            null, true, MemberScope.fromRequest("unassigned", null, currentUser.getId()), 0, foreign.getId(), 250)).isEmpty();
    }

    /** Each simulated request gets its own synchronization scope and MyBatis local cache. */
    @Test
    void renameAndInsertBetweenBatchesPreserveTheWatermarkedCohortWithoutATestSnapshot() {
        TransactionTemplate outside = new TransactionTemplate(transactionManager);
        outside.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        Pipeline pipeline = new Pipeline();
        pipeline.setWorkspaceId(workspace.getId());
        pipeline.setName("Cohort " + unique());
        Stage stage = new Stage();
        String prefix = "Cohort " + unique();
        List<Deal> cohort = new ArrayList<>();
        TransactionTemplate write = new TransactionTemplate(transactionManager);
        AtomicReference<Deal> inserted = new AtomicReference<>();
        try {
            outside.executeWithoutResult(ignored -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                write.executeWithoutResult(status -> {
                    pipelineMapper.insertPipeline(pipeline);
                    stage.setWorkspaceId(workspace.getId());
                    stage.setPipeline(pipeline);
                    stage.setName("Open");
                    stage.setPosition(0);
                    pipelineMapper.insertStage(stage);
                    for (int index = 0; index < 500; index++) {
                        cohort.add(cohortDeal(pipeline, stage, prefix + String.format(" %04d", index)));
                    }
                    dealMapper.insertBatch(cohort);
                    jdbc.update("UPDATE deal SET risk_excluded = TRUE WHERE id = ?", cohort.getLast().getId());
                });
                AtomicBoolean interleaved = new AtomicBoolean();
                List<Integer> assessed = new ArrayList<>();
                doAnswer(invocation -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                    List<Integer> ids = invocation.getArgument(1);
                    assessed.addAll(ids);
                    Object result = invocation.callRealMethod();
                    if (interleaved.compareAndSet(false, true)) {
                        assertThat(ids).hasSize(250);
                        write.executeWithoutResult(status -> {
                            jdbc.update("UPDATE deal SET name = ? WHERE workspace_id = ? AND id = ?",
                                "ZZZ renamed", workspace.getId(), cohort.getFirst().getId());
                            Deal added = cohortDeal(pipeline, stage, "AAA inserted");
                            dealMapper.insert(added);
                            inserted.set(added);
                        });
                    }
                    return result;
                }).when(risks).assessDeals(eq(workspace.getId()), anyList(), isNull(), any());

                var page = service.queryDealsPage(null, "name", "asc", "JPY", List.of(pipeline.getId()),
                    null, null, null, false, null, List.of("high"), MemberScope.allTeam(), 25, 0);
                assertThat(interleaved).isTrue();
                assertThat(page.total()).isEqualTo(499);
                assertThat(assessed).hasSize(499).doesNotHaveDuplicates();
                assertThat(assessed).containsExactlyElementsOf(cohort.subList(0, 499).stream().map(Deal::getId).toList());
                assertThat(inserted.get()).isNotNull();
                assertThat(inserted.get().getId()).isGreaterThan(cohort.getLast().getId());
                assertThat(assessed).doesNotContain(inserted.get().getId());
                assertThat(page.items()).extracting(Deal::getId)
                    .containsExactlyElementsOf(cohort.subList(1, 26).stream().map(Deal::getId).toList());
            });
            outside.executeWithoutResult(ignored -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                var nextRequest = service.queryDealsPage(null, "name", "desc", "JPY", List.of(pipeline.getId()),
                    null, null, null, false, null, List.of("high"), MemberScope.allTeam(), 25, 0);
                assertThat(nextRequest.total()).isEqualTo(500);
                assertThat(nextRequest.items().getFirst().getId()).isEqualTo(cohort.getFirst().getId());
            });
        } finally {
            if (pipeline.getId() > 0) {
                outside.executeWithoutResult(ignored -> {
                    write.executeWithoutResult(status -> {
                        jdbc.update("DELETE FROM deal WHERE workspace_id = ? AND pipeline_id = ?", workspace.getId(), pipeline.getId());
                        jdbc.update("DELETE FROM stage WHERE workspace_id = ? AND pipeline_id = ?", workspace.getId(), pipeline.getId());
                        jdbc.update("DELETE FROM pipeline WHERE workspace_id = ? AND id = ?", workspace.getId(), pipeline.getId());
                    });
                });
            }
        }
    }

    @Test
    void actualMatchingSetCapRefusesMoreThanOneThousandMatches() {
        var pipeline = newPipeline();
        var stage = newStage(pipeline, 0);
        String prefix = "Match cap " + unique();
        List<Deal> matches = IntStream.range(0, 1001)
            .mapToObj(index -> cohortDeal(pipeline, stage, prefix + index)).toList();
        dealMapper.insertBatch(matches);
        assertThatThrownBy(() -> service.queryDealsPage(null, "name", "asc", "JPY", List.of(pipeline.getId()),
            null, null, null, false, null, List.of("high"), MemberScope.allTeam(), 25, 0))
            .isInstanceOf(BadRequestException.class)
            .hasMessage("Risk filter matches too many deals; narrow the other filters before loading records");
        assertThatThrownBy(() -> service.getMatchingDealIds(null, "JPY", List.of(pipeline.getId()), null, null, null,
            false, null, List.of("high"), MemberScope.allTeam()))
            .isInstanceOf(BadRequestException.class)
            .hasMessage("Too many matching deals; narrow the filters before selecting all");
    }

    @Test
    void candidatePlanUsesTheWorkspaceIdRangeWithoutFilesort() {
        var pipeline = newPipeline();
        var stage = newStage(pipeline, 0);
        List<Deal> cohort = IntStream.range(0, 600)
            .mapToObj(index -> cohortDeal(pipeline, stage, "Plan " + index)).toList();
        dealMapper.insertBatch(cohort);
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("workspaceId", workspace.getId());
        parameters.put("orgWorkspaceIdsJson", dealScope(workspace.getId()));
        parameters.put("memberScope", MemberScope.allTeam());
        parameters.put("pipelineIds", List.of(pipeline.getId()));
        parameters.put("noCompany", false);
        parameters.put("eligibleOnly", true);
        parameters.put("afterId", cohort.get(249).getId());
        parameters.put("upperId", cohort.getLast().getId());
        parameters.put("limit", 250);
        var mapped = sqlSessionFactory.getConfiguration().getMappedStatement(DealMapper.class.getName() + ".getRiskFilterCandidateIds");
        for (boolean segmented : List.of(false, true)) {
            parameters.put("segmentIdsJson", segmented ? cohort.stream().map(Deal::getId).toList().toString() : null);
            var bound = mapped.getBoundSql(parameters);
            List<Map<String, Object>> plan = jdbc.query(connection -> {
                var explain = connection.prepareStatement("EXPLAIN " + bound.getSql());
                new DefaultParameterHandler(mapped, parameters, bound).setParameters(explain);
                return explain;
            }, new ColumnMapRowMapper());
            var access = plan.stream().filter(row -> "d".equals(row.get("table"))).findFirst().orElseThrow();
            assertThat(String.valueOf(access.get("key"))).isIn("PRIMARY", "uq_deal_workspace_id");
            assertThat(String.valueOf(access.get("type"))).isEqualTo("range");
            assertThat(plan).allSatisfy(row -> assertThat(String.valueOf(row.get("Extra"))).doesNotContain("filesort"));
        }
    }

    @Test
    void boundedInMemoryOrderingMatchesTheNativeSqlForEverySortAndDirection() {
        var pipeline = newPipeline();
        var stage = newStage(pipeline, 0);
        var alpha = newCompany();
        var beta = newCompany();
        jdbc.update("UPDATE company SET name = 'Alpha' WHERE id = ?", alpha.getId());
        jdbc.update("UPDATE company SET name = '東京' WHERE id = ?", beta.getId());
        List<Deal> cohort = new ArrayList<>();
        for (int index = 0; index < 18; index++) {
            Deal deal = cohortDeal(pipeline, stage, List.of("Álpha", "alpha", "東京", "あい", "Zulu", "Beta").get(index % 6));
            deal.setCompanyId(index % 3 == 0 ? null : index % 3 == 1 ? alpha.getId() : beta.getId());
            deal.setValue(BigDecimal.valueOf(index % 4));
            deal.setActualValue(BigDecimal.valueOf(index % 5));
            deal.setPosition(index % 2);
            cohort.add(deal);
        }
        dealMapper.insertBatch(cohort);
        for (String sort : List.of("name", "value", "actual_value", "expected_close_date", "closed_at", "created_at",
                "updated_at", "stage_id", "position", "company", "pipeline", "stage", "status", "")) {
            for (String direction : List.of("asc", "desc")) {
                var nativePage = dealMapper.getDealsPageFiltered(workspace.getId(), dealScope(workspace.getId()), null,
                    null, sort, direction, "JPY", List.of(pipeline.getId()), null, null, null, false,
                    null, null, MemberScope.allTeam(), 7, 3);
                var riskPage = service.queryDealsPage(null, sort, direction, "JPY", List.of(pipeline.getId()), null,
                    null, null, false, null, List.of("high"), MemberScope.allTeam(), 7, 3);
                assertThat(riskPage.total()).isEqualTo(18);
                assertThat(riskPage.items()).extracting(Deal::getId).containsExactlyElementsOf(nativePage.stream().map(Deal::getId).toList());
            }
        }
    }

    @Test
    void textSortWeightsRespectEachColumnsPaddingAndKeepTheNativeIdTieBreak() {
        var columnCollations = jdbc.queryForList("""
            SELECT name_column.TABLE_NAME, name_column.COLLATION_NAME, name_collation.PAD_ATTRIBUTE
            FROM information_schema.COLUMNS name_column
            JOIN information_schema.COLLATIONS name_collation
              ON name_collation.COLLATION_NAME = name_column.COLLATION_NAME
            WHERE name_column.TABLE_SCHEMA = DATABASE() AND name_column.COLUMN_NAME = 'name'
              AND name_column.TABLE_NAME IN ('deal', 'company', 'pipeline', 'stage')
            """);
        assertThat(columnCollations).extracting(row -> String.valueOf(row.get("TABLE_NAME")))
            .containsExactlyInAnyOrder("deal", "company", "pipeline", "stage");
        assertThat(columnCollations).allSatisfy(row -> {
            assertThat(row.get("COLLATION_NAME")).isNotNull();
            assertThat(String.valueOf(row.get("PAD_ATTRIBUTE"))).isIn("PAD SPACE", "NO PAD");
        });
        var mapped = sqlSessionFactory.getConfiguration()
            .getMappedStatement(DealMapper.class.getName() + ".getRiskFilterMatches");
        for (String sort : List.of("name", "company", "pipeline", "stage")) {
            Map<String, Object> parameters = new HashMap<>();
            parameters.put("workspaceId", workspace.getId());
            parameters.put("orgWorkspaceIdsJson", dealScope(workspace.getId()));
            parameters.put("ids", List.of(1, 2));
            parameters.put("sort", sort);
            String sql = mapped.getBoundSql(parameters).getSql().replaceAll("\\s+", " ").trim();
            int keyStart = sql.indexOf("WEIGHT_STRING(");
            int keyEnd = sql.indexOf(" AS risk_sort_key");
            assertThat(keyStart).isGreaterThan(0);
            assertThat(keyEnd).isGreaterThan(keyStart);
            String keyExpression = sql.substring(keyStart, keyEnd);
            String alias = switch (sort) {
                case "name" -> "d";
                case "company" -> "c";
                case "pipeline" -> "p";
                default -> "s";
            };
            for (String collation : List.of("utf8mb4_unicode_ci", "utf8mb4_0900_ai_ci")) {
                for (String direction : List.of("asc", "desc")) {
                    String query = """
                        SELECT id, name, %s AS risk_sort_key
                        FROM (
                            SELECT 1 AS id, CONVERT(? USING utf8mb4) COLLATE %s AS name
                            UNION ALL
                            SELECT 2 AS id, CONVERT(? USING utf8mb4) COLLATE %s AS name
                        ) %s
                        ORDER BY name %s, id ASC
                        """.formatted(keyExpression, collation, collation, alias, direction);
                    List<DealRiskMatchDto> nativeOrder = jdbc.query(query, (result, rowNumber) -> {
                        Deal deal = new Deal();
                        deal.setId(result.getInt("id"));
                        deal.setName(result.getString("name"));
                        DealRiskMatchDto row = new DealRiskMatchDto();
                        row.setDeal(deal);
                        row.setSortKey(result.getBytes("risk_sort_key"));
                        return row;
                    }, "Alpha ", "Alpha");
                    List<Integer> expected = "utf8mb4_unicode_ci".equals(collation) || "desc".equals(direction)
                        ? List.of(1, 2) : List.of(2, 1);
                    assertThat(nativeOrder).extracting(row -> row.getDeal().getId()).containsExactlyElementsOf(expected);
                    assertThat(nativeOrder.stream().sorted(DealRiskMatchOrder.comparator(sort, direction))
                        .map(row -> row.getDeal().getId()).toList()).containsExactlyElementsOf(expected);
                }
            }
        }
    }

    private Deal cohortDeal(Pipeline pipeline, Stage stage, String name) {
        Deal deal = new Deal();
        deal.setWorkspaceId(workspace.getId());
        deal.setName(name);
        deal.setCurrency("JPY");
        deal.setValue(BigDecimal.TEN);
        deal.setPipelineId(pipeline.getId());
        deal.setStageId(stage.getId());
        deal.setExpectedCloseDate("2000-01-01");
        return deal;
    }

}
