package ooo.klae.connex.backend.services;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import ooo.klae.connex.backend.beans.Activity;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.dto.DealRiskDto;
import ooo.klae.connex.backend.dto.DealRiskFactor;
import ooo.klae.connex.backend.dto.RelationshipTemperatureDto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * The fixed clock sits far in the future so the database's wall-clock {@code created_at} is always
 * in the past relative to it; deal recency is then driven entirely by the activity timestamps the
 * tests set, keeping the creation-floor from masking the stalled/quiet signals under test.
 */
class DealRiskServiceTest extends AbstractServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2126-06-23T15:30:00Z"), ZoneOffset.UTC);

    private ScoringService scoring;
    private DealRiskService service;
    private Pipeline pipeline;
    private Stage stage;
    private Company company;

    @BeforeEach
    void setUpService() {
        scoring = Mockito.mock(ScoringService.class);
        when(scoring.scoreContacts(workspace.getId())).thenReturn(List.of());
        service = new DealRiskService(dealMapper, activityMapper, noteMapper, taskMapper, scoring, CLOCK);
    }

    private Deal openDeal() {
        if (pipeline == null) {
            pipeline = newPipeline();
            stage = newStage(pipeline, 0);
            company = newCompany();
        }
        return newDeal(pipeline, stage, company);
    }

    private void touch(Deal deal, String timestamp) {
        Activity activity = new Activity();
        activity.setWorkspaceId(workspace.getId());
        activity.setType("email");
        activity.setSubject("subj_" + unique());
        activity.setDeal(deal);
        activity.setCreatedBy(currentUser);
        activity.setTimestamp(timestamp);
        activityMapper.insert(activity);
    }

    private void closeDateOf(Deal deal, String date) {
        deal.setExpectedCloseDate(date);
        dealMapper.update(deal);
    }

    private void warmth(int personId, int score, String band, String trend, int daysSinceTouch) {
        when(scoring.scoreContacts(workspace.getId())).thenReturn(List.of(
            new RelationshipTemperatureDto(personId, score, band, trend, "2126-05-01 10:00:00",
                daysSinceTouch, 0, null, null)));
    }

    private DealRiskFactor factor(DealRiskDto dto, String code) {
        return dto.getFactors().stream().filter(f -> code.equals(f.getCode())).findFirst().orElse(null);
    }

    @Test
    void overdueCloseDateFlagsHigh() {
        Deal deal = openDeal();
        closeDateOf(deal, "2126-06-01");
        touch(deal, "2126-06-20 10:00:00");

        DealRiskDto risk = service.assessDeal(workspace.getId(), deal.getId());

        assertThat(risk.getLevel()).isEqualTo("high");
        assertThat(factor(risk, "close_overdue")).isNotNull();
        assertThat(factor(risk, "close_overdue").getParams()).containsEntry("daysOverdue", 22L);
    }

    @Test
    void closingSoonWhileQuietFlagsHigh() {
        Deal deal = openDeal();
        closeDateOf(deal, "2126-06-30");
        touch(deal, "2126-05-01 10:00:00");

        DealRiskDto risk = service.assessDeal(workspace.getId(), deal.getId());

        assertThat(risk.getLevel()).isEqualTo("high");
        assertThat(factor(risk, "closing_soon_quiet")).isNotNull();
    }

    @Test
    void quietDealFlagsStalled() {
        Deal deal = openDeal();
        touch(deal, "2126-05-01 10:00:00");

        DealRiskDto risk = service.assessDeal(workspace.getId(), deal.getId());

        assertThat(factor(risk, "stalled")).isNotNull();
        assertThat(factor(risk, "stalled").getParams()).containsKey("daysSinceTouch");
        assertThat(risk.getLevel()).isEqualTo("medium");
    }

    @Test
    void coldChampionFlagsHigh() {
        Deal deal = openDeal();
        touch(deal, "2126-06-20 10:00:00");
        Person champion = newPerson(company);
        dealMapper.addPerson(workspace.getId(), deal.getId(), champion.getId(), "Champion");
        warmth(champion.getId(), 8, "cold", "cooling", 40);

        DealRiskDto risk = service.assessDeal(workspace.getId(), deal.getId());

        assertThat(risk.getLevel()).isEqualTo("high");
        DealRiskFactor cold = factor(risk, "stakeholder_cold");
        assertThat(cold).isNotNull();
        assertThat(cold.getParams()).containsEntry("personId", champion.getId());
        assertThat(cold.getParams()).containsEntry("role", "Champion");
    }

    @Test
    void warmStakeholderDoesNotFlag() {
        Deal deal = openDeal();
        closeDateOf(deal, "2126-12-31");
        touch(deal, "2126-06-20 10:00:00");
        Person contact = newPerson(company);
        dealMapper.addPerson(workspace.getId(), deal.getId(), contact.getId(), "Champion");
        warmth(contact.getId(), 80, "hot", "rising", 3);

        DealRiskDto risk = service.assessDeal(workspace.getId(), deal.getId());

        assertThat(risk.getLevel()).isEqualTo("none");
        assertThat(risk.getFactors()).isEmpty();
    }

    @Test
    void openDealWithoutStakeholdersFlagsLow() {
        Deal deal = openDeal();
        closeDateOf(deal, "2126-12-31");
        touch(deal, "2126-06-20 10:00:00");

        DealRiskDto risk = service.assessDeal(workspace.getId(), deal.getId());

        assertThat(risk.getLevel()).isEqualTo("low");
        assertThat(factor(risk, "no_stakeholders")).isNotNull();
    }

    @Test
    void closedDealIsNotAssessed() {
        Deal deal = openDeal();
        deal.setExpectedCloseDate("2126-06-01");
        deal.setClosedAt("2126-06-10 00:00:00");
        deal.setWon(true);
        dealMapper.update(deal);

        DealRiskDto single = service.assessDeal(workspace.getId(), deal.getId());
        assertThat(single.getLevel()).isEqualTo("none");

        assertThat(service.assessWorkspace(workspace.getId()))
            .noneMatch(risk -> risk.getDealId() == deal.getId());
    }

    @Test
    void assessWorkspaceReturnsAtRiskDealsHighestFirst() {
        Deal overdue = openDeal();
        closeDateOf(overdue, "2126-06-01");
        touch(overdue, "2126-06-20 10:00:00");

        Deal healthy = openDeal();
        closeDateOf(healthy, "2126-12-31");
        touch(healthy, "2126-06-20 10:00:00");
        Person contact = newPerson(company);
        dealMapper.addPerson(workspace.getId(), healthy.getId(), contact.getId(), "Champion");
        warmth(contact.getId(), 80, "hot", "rising", 3);

        List<DealRiskDto> risks = service.assessWorkspace(workspace.getId());

        assertThat(risks).isNotEmpty();
        assertThat(risks.get(0).getScore())
            .isGreaterThanOrEqualTo(risks.get(risks.size() - 1).getScore());
        assertThat(risks).anyMatch(risk -> risk.getDealId() == overdue.getId());
        assertThat(risks).noneMatch(risk -> risk.getDealId() == healthy.getId());
    }
}
