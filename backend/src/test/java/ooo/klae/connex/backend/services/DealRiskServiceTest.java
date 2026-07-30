package ooo.klae.connex.backend.services;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import ooo.klae.connex.backend.beans.Activity;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Note;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.DealRiskDto;
import ooo.klae.connex.backend.dto.DealRiskFactor;
import ooo.klae.connex.backend.dto.RelationshipTemperatureDto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
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
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUpService() {
        scoring = Mockito.mock(ScoringService.class);
        when(scoring.scoreContacts(workspace.getId())).thenReturn(List.of());
        when(scoring.scoreContacts(eq(workspace.getId()), anySet())).thenReturn(List.of());
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

    private void touch(Person person, String timestamp) {
        Activity activity = new Activity();
        activity.setWorkspaceId(workspace.getId());
        activity.setType("meeting");
        activity.setSubject("subj_" + unique());
        activity.setPerson(person);
        activity.setCreatedBy(currentUser);
        activity.setTimestamp(timestamp);
        activityMapper.insert(activity);
    }

    private void closeDateOf(Deal deal, String date) {
        deal.setExpectedCloseDate(date);
        dealMapper.update(deal);
    }

    private void warmth(int personId, int score, String band, String trend, int daysSinceTouch) {
        warmthList(new RelationshipTemperatureDto(personId, score, band, trend, "2126-05-01 10:00:00",
            daysSinceTouch, 0, null, null, "test-model", Instant.EPOCH));
    }

    private RelationshipTemperatureDto temp(int personId, String band, String trend) {
        return new RelationshipTemperatureDto(
            personId, 0, band, trend, "2126-05-01 10:00:00", 40, 0, null, null,
            "test-model", Instant.EPOCH);
    }

    private void warmthList(RelationshipTemperatureDto... temps) {
        when(scoring.scoreContacts(workspace.getId())).thenReturn(List.of(temps));
        when(scoring.scoreContacts(eq(workspace.getId()), anySet())).thenReturn(List.of(temps));
    }

    private Workspace newWorkspace() {
        Workspace other = new Workspace();
        other.setName("Other " + unique());
        other.setSlug("other-" + unique());
        workspaceMapper.insert(other);
        return other;
    }

    private Deal overdueDealInWorkspace(int workspaceId) {
        Pipeline p = new Pipeline();
        p.setName("Pipeline " + unique());
        p.setWorkspaceId(workspaceId);
        pipelineMapper.insertPipeline(p);
        Stage s = new Stage();
        s.setName("Stage " + unique());
        s.setPipeline(p);
        s.setPosition(0);
        s.setWorkspaceId(workspaceId);
        pipelineMapper.insertStage(s);
        Company c = new Company();
        c.setName("Company " + unique());
        c.setWorkspaceId(workspaceId);
        companyMapper.insert(c);
        Deal deal = new Deal();
        deal.setName("Deal " + unique());
        deal.setWorkspaceId(workspaceId);
        deal.setValue(1000.0);
        deal.setCurrency("JPY");
        deal.setPipelineId(p.getId());
        deal.setStageId(s.getId());
        deal.setCompanyId(c.getId());
        deal.setExpectedCloseDate("2126-06-01");
        dealMapper.insert(deal);
        return deal;
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
    void assessDealsScopesBatchedInputsToRequestedWorkspaceDeals() {
        Deal requested = openDeal();
        closeDateOf(requested, "2126-06-01");
        Deal omitted = openDeal();
        closeDateOf(omitted, "2126-06-01");
        Deal foreign = overdueDealInWorkspace(newWorkspace().getId());

        List<DealRiskDto> risks = service.assessDeals(
            workspace.getId(), List.of(requested.getId(), foreign.getId()));

        assertThat(risks).extracting(DealRiskDto::getDealId)
            .containsExactly(requested.getId())
            .doesNotContain(omitted.getId(), foreign.getId());
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
    void privateDealNoteDoesNotRefreshSharedRiskRecency() {
        Deal deal = openDeal();
        Note note = new Note();
        note.setWorkspaceId(workspace.getId());
        note.setContent("Private touch");
        note.setVisibility("private");
        note.setAuthor(currentUser);
        note.setDeal(deal);
        noteMapper.insert(note);
        jdbcTemplate.update(
            "UPDATE note SET created_at = ? WHERE id = ?",
            java.sql.Timestamp.valueOf("2126-06-22 10:00:00"),
            note.getId());

        DealRiskDto single = service.assessDeal(workspace.getId(), deal.getId());
        DealRiskDto batched = service.assessDeals(workspace.getId(), List.of(deal.getId())).getFirst();

        assertThat(factor(single, "stalled")).isNotNull();
        assertThat(factor(batched, "stalled")).isNotNull();
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
        assertThat(risk.getValue()).isEqualTo(deal.getValue());
        assertThat(risk.getCurrency()).isEqualTo(deal.getCurrency());
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
        assertThat(single.getValue()).isEqualTo(deal.getValue());
        assertThat(single.getCurrency()).isEqualTo(deal.getCurrency());

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
        assertThat(risks).filteredOn(risk -> risk.getDealId() == overdue.getId())
            .allMatch(risk -> risk.getValue() == overdue.getValue()
                && overdue.getCurrency().equals(risk.getCurrency()));
        assertThat(risks).noneMatch(risk -> risk.getDealId() == healthy.getId());
    }

    @Test
    void notificationSourceStateStaysStableAcrossClockOnlyRiskBoundary() {
        Deal deal = openDeal();
        closeDateOf(deal, "2126-07-08");
        touch(deal, "2126-06-01 10:00:00");
        Clock beforeClosingSoon = Clock.fixed(
            Instant.parse("2126-06-23T15:30:00Z"), ZoneOffset.UTC);
        Clock closingSoon = Clock.fixed(
            Instant.parse("2126-06-24T15:30:00Z"), ZoneOffset.UTC);
        DealRiskService beforeService = new DealRiskService(
            dealMapper,
            activityMapper,
            noteMapper,
            taskMapper,
            scoring,
            beforeClosingSoon);
        DealRiskService afterService = new DealRiskService(
            dealMapper,
            activityMapper,
            noteMapper,
            taskMapper,
            scoring,
            closingSoon);

        DealRiskService.NotificationRiskState before =
            beforeService.assessWorkspaceNotificationStates(
                workspace.getId(), Map.of(), Map.of()).stream()
                .filter(state -> state.assessment().getDealId() == deal.getId())
                .findFirst()
                .orElseThrow();
        DealRiskService.NotificationRiskState after =
            afterService.assessWorkspaceNotificationStates(
                workspace.getId(), Map.of(), Map.of()).stream()
                .filter(state -> state.assessment().getDealId() == deal.getId())
                .findFirst()
                .orElseThrow();
        touch(deal, "2126-05-01 10:00:00");
        DealRiskService.NotificationRiskState changed =
            afterService.assessWorkspaceNotificationStates(
                workspace.getId(), Map.of(), Map.of()).stream()
                .filter(state -> state.assessment().getDealId() == deal.getId())
                .findFirst()
                .orElseThrow();

        assertThat(before.assessment().getLevel()).isEqualTo("low");
        assertThat(after.assessment().getLevel()).isEqualTo("high");
        assertThat(before.sourceStateHash()).isEqualTo(after.sourceStateHash());
        assertThat(changed.sourceStateHash())
            .isNotEqualTo(after.sourceStateHash());
    }

    @Test
    void nonKeyRoleColdStakeholderFlagsMedium() {
        Deal deal = openDeal();
        closeDateOf(deal, "2126-12-31");
        touch(deal, "2126-06-20 10:00:00");
        Person contact = newPerson(company);
        dealMapper.addPerson(workspace.getId(), deal.getId(), contact.getId(), "Influencer");
        warmthList(temp(contact.getId(), "cold", "cooling"));

        DealRiskDto risk = service.assessDeal(workspace.getId(), deal.getId());

        assertThat(factor(risk, "stakeholder_cold").getSeverity()).isEqualTo("medium");
        assertThat(risk.getLevel()).isEqualTo("medium");
    }

    @Test
    void coolingKeyRoleFlagsMediumAndNonKeyFlagsLow() {
        Deal keyDeal = openDeal();
        closeDateOf(keyDeal, "2126-12-31");
        touch(keyDeal, "2126-06-20 10:00:00");
        Person champion = newPerson(company);
        dealMapper.addPerson(workspace.getId(), keyDeal.getId(), champion.getId(), "Champion");
        warmthList(temp(champion.getId(), "cool", "cooling"));
        assertThat(service.assessDeal(workspace.getId(), keyDeal.getId()).getLevel()).isEqualTo("medium");

        Deal plainDeal = openDeal();
        closeDateOf(plainDeal, "2126-12-31");
        touch(plainDeal, "2126-06-20 10:00:00");
        Person contact = newPerson(company);
        dealMapper.addPerson(workspace.getId(), plainDeal.getId(), contact.getId(), "Influencer");
        warmthList(temp(contact.getId(), "cool", "cooling"));
        assertThat(service.assessDeal(workspace.getId(), plainDeal.getId()).getLevel()).isEqualTo("low");
    }

    @Test
    void stakeholderWithoutWarmthDoesNotFlag() {
        Deal deal = openDeal();
        closeDateOf(deal, "2126-12-31");
        touch(deal, "2126-06-20 10:00:00");
        Person contact = newPerson(company);
        dealMapper.addPerson(workspace.getId(), deal.getId(), contact.getId(), "Champion");
        warmthList();

        DealRiskDto risk = service.assessDeal(workspace.getId(), deal.getId());

        assertThat(factor(risk, "stakeholder_cold")).isNull();
        assertThat(risk.getLevel()).isEqualTo("none");
    }

    @Test
    void riskExcludedDealIsSkippedByBothAssessments() {
        Deal deal = openDeal();
        closeDateOf(deal, "2126-06-01");
        dealMapper.updateRiskExcluded(workspace.getId(), deal.getId(), true);

        assertThat(service.assessDeal(workspace.getId(), deal.getId()).getLevel()).isEqualTo("none");
        assertThat(service.assessWorkspace(workspace.getId()))
            .noneMatch(risk -> risk.getDealId() == deal.getId());

        dealMapper.updateRiskExcluded(workspace.getId(), deal.getId(), false);
        assertThat(service.assessDeal(workspace.getId(), deal.getId()).getLevel()).isEqualTo("high");
    }

    @Test
    void riskExcludedStakeholderContributesNoColdFactorButStillCountsAsStakeholder() {
        Deal deal = openDeal();
        closeDateOf(deal, "2126-12-31");
        touch(deal, "2126-06-20 10:00:00");
        Person contact = newPerson(company);
        dealMapper.addPerson(workspace.getId(), deal.getId(), contact.getId(), "Champion");
        warmth(contact.getId(), 4, "cold", "steady", 60);
        personMapper.updateEvaluationExclusions(workspace.getId(), contact.getId(), true, null);

        DealRiskDto risk = service.assessDeal(workspace.getId(), deal.getId());

        assertThat(factor(risk, "stakeholder_cold")).isNull();
        assertThat(factor(risk, "no_stakeholders")).isNull();
        assertThat(risk.getLevel()).isEqualTo("none");
    }

    @Test
    void multipleColdStakeholdersScoreCappedToHighest() {
        Deal deal = openDeal();
        closeDateOf(deal, "2126-12-31");
        touch(deal, "2126-06-20 10:00:00");
        Person champion = newPerson(company);
        Person contact = newPerson(company);
        dealMapper.addPerson(workspace.getId(), deal.getId(), champion.getId(), "Champion");
        dealMapper.addPerson(workspace.getId(), deal.getId(), contact.getId(), "Influencer");
        warmthList(temp(champion.getId(), "cold", "cooling"), temp(contact.getId(), "cold", "cooling"));

        DealRiskDto risk = service.assessDeal(workspace.getId(), deal.getId());

        assertThat(risk.getFactors()).filteredOn(f -> "stakeholder_cold".equals(f.getCode())).hasSize(2);
        assertThat(risk.getScore()).isEqualTo(50);
        assertThat(risk.getLevel()).isEqualTo("high");
    }

    @Test
    void futureDatedTouchDoesNotMaskStaleness() {
        Deal deal = openDeal();
        touch(deal, "2126-05-01 10:00:00");
        touch(deal, "2130-01-01 10:00:00");

        DealRiskDto risk = service.assessDeal(workspace.getId(), deal.getId());

        assertThat(factor(risk, "stalled")).isNotNull();
    }

    @Test
    void futureStakeholderTouchLeavesColdRiskWhileExactBoundaryTouchClearsIt() {
        Deal deal = openDeal();
        closeDateOf(deal, "2126-12-31");
        touch(deal, "2126-06-20 10:00:00");
        Person stakeholder = newPerson(company);
        dealMapper.addPerson(workspace.getId(), deal.getId(), stakeholder.getId(), "Champion");
        touch(stakeholder, "2126-06-23 15:30:01");
        ScoringService actualScoring = new ScoringService(
            personMapper, companyMapper, dealMapper, activityMapper, noteMapper, taskMapper, CLOCK);
        DealRiskService actualService = new DealRiskService(
            dealMapper, activityMapper, noteMapper, taskMapper, actualScoring, CLOCK);

        RelationshipTemperatureDto futureOnly = actualScoring
            .scoreContacts(workspace.getId(), java.util.Set.of(stakeholder.getId()))
            .getFirst();
        DealRiskDto futureRisk = actualService.assessDeal(workspace.getId(), deal.getId());

        assertThat(futureOnly.getBand()).isEqualTo("cold");
        assertThat(futureOnly.getTouchCount()).isZero();
        assertThat(factor(futureRisk, "stakeholder_cold")).isNotNull();

        touch(stakeholder, "2126-06-23 15:30:00");

        RelationshipTemperatureDto boundary = actualScoring
            .scoreContacts(workspace.getId(), java.util.Set.of(stakeholder.getId()))
            .getFirst();
        DealRiskDto boundaryRisk = actualService.assessDeal(workspace.getId(), deal.getId());

        assertThat(boundary.getBand()).isNotEqualTo("cold");
        assertThat(boundary.getTouchCount()).isEqualTo(1);
        assertThat(factor(boundaryRisk, "stakeholder_cold")).isNull();
    }

    @Test
    void assessWorkspaceIncludesLowOnlyDealBelowHigh() {
        Deal overdue = openDeal();
        closeDateOf(overdue, "2126-06-01");
        touch(overdue, "2126-06-20 10:00:00");
        Person warm = newPerson(company);
        dealMapper.addPerson(workspace.getId(), overdue.getId(), warm.getId(), "Champion");

        Deal lowOnly = openDeal();
        closeDateOf(lowOnly, "2126-12-31");
        touch(lowOnly, "2126-06-20 10:00:00");
        warmthList(temp(warm.getId(), "hot", "rising"));

        List<DealRiskDto> risks = service.assessWorkspace(workspace.getId());

        DealRiskDto lowRisk = risks.stream().filter(r -> r.getDealId() == lowOnly.getId()).findFirst().orElseThrow();
        assertThat(lowRisk.getLevel()).isEqualTo("low");
        int highIndex = indexOfDeal(risks, overdue.getId());
        int lowIndex = indexOfDeal(risks, lowOnly.getId());
        assertThat(highIndex).isLessThan(lowIndex);
    }

    @Test
    void doesNotLeakAcrossWorkspaces() {
        Workspace other = newWorkspace();
        Deal foreign = overdueDealInWorkspace(other.getId());

        assertThat(service.assessWorkspace(workspace.getId()))
            .noneMatch(risk -> risk.getDealId() == foreign.getId());
        DealRiskDto hidden = service.assessDeal(workspace.getId(), foreign.getId());
        assertThat(hidden.getLevel()).isEqualTo("none");
        assertThat(hidden.getValue()).isZero();
        assertThat(hidden.getCurrency()).isNull();
    }

    private static int indexOfDeal(List<DealRiskDto> risks, int dealId) {
        for (int i = 0; i < risks.size(); i++) {
            if (risks.get(i).getDealId() == dealId) {
                return i;
            }
        }
        return -1;
    }
}
