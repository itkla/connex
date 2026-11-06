package ooo.klae.connex.backend.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import ooo.klae.connex.backend.beans.Activity;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.MemberScope;
import ooo.klae.connex.backend.exceptions.BadRequestException;

/** Exercises complete risk-filter counts and bounded assessment beyond the old candidate ceiling. */
class DealRiskPagingTest extends AbstractServiceTest {
    @Autowired private DealService service;
    @Autowired private JdbcTemplate jdbc;
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
            deal.setExpectedCloseDate(index < 1105 ? "2000-01-01" : "2200-01-01");
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
        jdbc.update("UPDATE deal SET risk_excluded = TRUE WHERE id = ?", deals.get(1100).getId());
        jdbc.update("UPDATE deal SET won = TRUE, closed_at = CURRENT_TIMESTAMP WHERE id = ?", deals.get(1101).getId());
        jdbc.update("UPDATE deal SET owner_id = NULL WHERE id = ?", deals.get(1102).getId());
        MemberScope me = MemberScope.fromRequest("me", null, currentUser.getId());
        String query = prefix + "%";

        var first = service.queryDealsPage(query, "name", "desc", "JPY", List.of(pipeline.getId()),
            List.of(stage.getId()), List.of(company.getId()), null, false, null, List.of("high"), me, 25, 0);
        var last = service.queryDealsPage(query, "name", "desc", "JPY", List.of(pipeline.getId()),
            List.of(stage.getId()), List.of(company.getId()), null, false, null, List.of("high"), me, 25, 1100);
        ArgumentCaptor<List<Integer>> batches = ArgumentCaptor.captor();
        verify(risks, times(10)).assessDeals(eq(workspace.getId()), batches.capture(), isNull(), any());
        assertThat(batches.getAllValues()).allSatisfy(batch -> assertThat(batch).hasSizeLessThanOrEqualTo(250));
        assertThat(first.total()).isEqualTo(1102);
        assertThat(first.items()).hasSize(25);
        assertThat(first.items().getFirst().getId()).isEqualTo(deals.get(1104).getId());
        assertThat(last.total()).isEqualTo(1102);
        assertThat(last.items()).extracting(Deal::getId).containsExactly(deals.get(1).getId(), deals.getFirst().getId());
        var empty = service.queryDealsPage(query, "name", "asc", "JPY", null, null, null, null,
            false, null, List.of("high"), me, 25, 2000);
        assertThat(empty.total()).isEqualTo(1102);
        assertThat(empty.items()).isEmpty();
        var metrics = service.queryDealMetrics(query, "JPY", null, null, null, null,
            false, null, List.of("high"), me);
        assertThat(metrics.totalCount()).isEqualTo(1102);
        assertThat(metrics.byCurrency()).singleElement().satisfies(currency -> {
            assertThat(currency.openCount()).isEqualTo(1102);
            assertThat(currency.openValue()).isEqualTo(11020);
            assertThat(currency.closedCount()).isZero();
        });
        assertThat(service.queryDealsForExport(query, "JPY", null, null, null, null,
            false, null, List.of("high"), me)).hasSize(1102);
        assertThatThrownBy(() -> service.getMatchingDealIds(query, "JPY", null, null, null, null,
            false, null, List.of("high"), me)).isInstanceOf(BadRequestException.class)
            .hasMessage("Too many matching deals; narrow the filters before selecting all");
        var none = service.queryDealsPage(query, "name", "asc", "JPY", null, null, null, null,
            false, null, List.of("none"), me, 25, 0);
        assertThat(none.total()).isEqualTo(2);
        assertThat(none.items()).extracting(Deal::getId)
            .containsExactly(deals.get(1100).getId(), deals.get(1101).getId());
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
        Deal foreign = new Deal();
        foreign.setWorkspaceId(other.getId());
        foreign.setName("Foreign " + unique());
        foreign.setCurrency("JPY");
        foreign.setValue(BigDecimal.TEN);
        foreign.setExpectedCloseDate("2000-01-01");
        dealMapper.insert(foreign);
        String segment = "[" + local.getId() + "," + foreign.getId() + "]";
        var all = MemberScope.allTeam();

        assertThat(dealMapper.getRiskFilterCandidateIds(workspace.getId(), dealScope(workspace.getId()),
            segment, null, "name", "asc", null, null, null, null, List.of(contact.getId()), false,
            null, true, all, 250, 0)).containsExactly(local.getId());
        assertThat(dealMapper.getRiskFilterCandidateIds(workspace.getId(), dealScope(workspace.getId()),
            segment, null, "name", "asc", null, null, null, null, List.of(hidden.getId()), false,
            null, true, all, 250, 0)).isEmpty();
        jdbc.update("UPDATE person SET archived_at = CURRENT_TIMESTAMP WHERE id = ?", contact.getId());
        assertThat(service.queryDealsPage(null, null, null, null, null, null, null, List.of(contact.getId()),
            false, null, List.of("high"), all, 25, 0).total()).isZero();
        assertThat(dealMapper.getRiskFilterCandidateIds(workspace.getId(), dealScope(workspace.getId()),
            "[" + foreign.getId() + "]", null, null, null, null, null, null, null, null, false,
            null, true, all, 250, 0)).isEmpty();
        assertThat(dealMapper.getRiskFilterCandidateIds(workspace.getId(), dealScope(workspace.getId()),
            segment, null, null, null, null, null, null, null, null, false,
            null, true, MemberScope.fromRequest("unassigned", null, currentUser.getId()), 250, 0)).isEmpty();
    }
}
