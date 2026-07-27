package ooo.klae.connex.backend.services;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.dto.DealRiskDto;
import ooo.klae.connex.backend.dto.DashboardDealRiskResult;
import ooo.klae.connex.backend.dto.RelationshipDashboardDto;
import ooo.klae.connex.backend.dto.RelationshipTemperatureDto;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RelationshipDashboardServiceTest {
    @Test
    void dashboardReusesOneWarmthSnapshotAndBatchHydratesBoundedRecords() {
        int workspaceId = 7;
        ScoringService scoring = mock(ScoringService.class);
        DealRiskService riskService = mock(DealRiskService.class);
        PersonMapper personMapper = mock(PersonMapper.class);
        CompanyMapper companyMapper = mock(CompanyMapper.class);
        DealMapper dealMapper = mock(DealMapper.class);
        RelationshipTemperatureDto coolingContact = temperature(1, "cooling", 40);
        RelationshipTemperatureDto steadyContact = temperature(2, "steady", 2);
        RelationshipTemperatureDto coolingCompany = temperature(10, "cooling", 30);
        when(scoring.scoreWorkspace(workspaceId)).thenReturn(new ScoringService.WorkspaceScores(
            List.of(coolingContact, steadyContact), List.of(coolingCompany)));
        when(scoring.summarizeScores(
            List.of(coolingContact, steadyContact), List.of(coolingCompany)))
            .thenReturn(new ooo.klae.connex.backend.dto.WarmthSummaryDto(
                new ooo.klae.connex.backend.dto.BandCounts(0, 0, 1, 1),
                new ooo.klae.connex.backend.dto.BandCounts(0, 0, 1, 0),
                new ooo.klae.connex.backend.dto.TrendCounts(0, 1, 1),
                new ooo.klae.connex.backend.dto.DecayCounts(0, 0, 0)));
        DealRiskDto risk = new DealRiskDto(20, 500, "USD", "high", 50, List.of(), "2026-07-01 00:00:00");
        when(riskService.assessDashboard(
            org.mockito.ArgumentMatchers.eq(workspaceId),
            argThat(map -> map.size() == 2 && map.get(1) == coolingContact),
            org.mockito.ArgumentMatchers.eq(6)))
            .thenReturn(new DashboardDealRiskResult(List.of(risk), true));
        Person person = new Person();
        person.setId(1);
        person.setName("Cooling contact");
        Company company = new Company();
        company.setId(10);
        company.setName("Cooling company");
        Deal deal = new Deal();
        deal.setId(20);
        deal.setName("At-risk deal");
        deal.setCurrency("USD");
        deal.setPipelineId(1);
        deal.setStageId(1);
        deal.setCompanyId(10);
        when(personMapper.getByIds(workspaceId, List.of(1))).thenReturn(List.of(person));
        when(dealMapper.getByIds(workspaceId, List.of(20))).thenReturn(List.of(deal));
        when(companyMapper.getByIds(workspaceId, List.of(10))).thenReturn(List.of(company));
        RelationshipDashboardService service = new RelationshipDashboardService(
            scoring, riskService, personMapper, companyMapper, dealMapper);

        RelationshipDashboardDto dashboard = service.getDashboard(workspaceId);

        assertEquals(1, dashboard.coolingContacts().size());
        assertEquals(1, dashboard.coolingCompanies().size());
        assertEquals(1, dashboard.dealRisks().size());
        assertEquals(true, dashboard.dealRisksTruncated());
        assertEquals("At-risk deal", dashboard.dealRisks().getFirst().deal().getName());
        verify(scoring, times(1)).scoreWorkspace(workspaceId);
        verify(riskService, times(1)).assessDashboard(
            org.mockito.ArgumentMatchers.eq(workspaceId),
            org.mockito.ArgumentMatchers.anyMap(),
            org.mockito.ArgumentMatchers.eq(6));
    }

    private static RelationshipTemperatureDto temperature(int id, String trend, int daysSinceTouch) {
        return new RelationshipTemperatureDto(
            id, 20, "cool", trend, "2026-06-01 00:00:00", daysSinceTouch, 1, null, null,
            "test-model", Instant.EPOCH);
    }
}
