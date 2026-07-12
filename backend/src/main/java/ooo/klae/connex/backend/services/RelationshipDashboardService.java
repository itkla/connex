package ooo.klae.connex.backend.services;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.dto.CompanyDto;
import ooo.klae.connex.backend.dto.DashboardCompanyTemperatureDto;
import ooo.klae.connex.backend.dto.DashboardContactTemperatureDto;
import ooo.klae.connex.backend.dto.DashboardDealRiskDto;
import ooo.klae.connex.backend.dto.DealDto;
import ooo.klae.connex.backend.dto.DealRiskDto;
import ooo.klae.connex.backend.dto.PersonDto;
import ooo.klae.connex.backend.dto.RelationshipDashboardDto;
import ooo.klae.connex.backend.dto.RelationshipTemperatureDto;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;

/** Builds the dashboard relationship snapshot with one warmth pass per entity type. */
@Service
@RequiredArgsConstructor
public class RelationshipDashboardService {
    private final ScoringService scoringService;
    private final DealRiskService dealRiskService;
    private final PersonMapper personMapper;
    private final CompanyMapper companyMapper;
    private final DealMapper dealMapper;

    private static final int DASHBOARD_LIMIT = 6;

    /** Computes and hydrates the bounded dashboard relationship snapshot. */
    public RelationshipDashboardDto getDashboard(int workspaceId) {
        ScoringService.WorkspaceScores scores = scoringService.scoreWorkspace(workspaceId);
        List<RelationshipTemperatureDto> contactScores = scores.contacts();
        List<RelationshipTemperatureDto> companyScores = scores.companies();
        List<RelationshipTemperatureDto> coolingContacts = cooling(contactScores);
        List<RelationshipTemperatureDto> coolingCompanies = cooling(companyScores);
        Map<Integer, RelationshipTemperatureDto> warmth = new HashMap<>();
        for (RelationshipTemperatureDto score : contactScores) warmth.put(score.getId(), score);
        List<DealRiskDto> risks = dealRiskService.assessDashboard(
            workspaceId, warmth, DASHBOARD_LIMIT);

        Map<Integer, Person> people = peopleById(workspaceId, coolingContacts);
        Map<Integer, Deal> deals = dealsById(workspaceId, risks);
        Set<Integer> companyIds = new LinkedHashSet<>();
        for (RelationshipTemperatureDto score : coolingCompanies) companyIds.add(score.getId());
        for (Deal deal : deals.values()) {
            if (deal.getCompanyId() != null) companyIds.add(deal.getCompanyId());
        }
        Map<Integer, Company> companies = companiesById(workspaceId, companyIds);

        List<DashboardContactTemperatureDto> contactItems = coolingContacts.stream()
            .filter(score -> people.containsKey(score.getId()))
            .map(score -> new DashboardContactTemperatureDto(
                PersonDto.from(people.get(score.getId())), score))
            .toList();
        List<DashboardCompanyTemperatureDto> companyItems = coolingCompanies.stream()
            .filter(score -> companies.containsKey(score.getId()))
            .map(score -> new DashboardCompanyTemperatureDto(
                CompanyDto.from(companies.get(score.getId())), score))
            .toList();
        List<DashboardDealRiskDto> riskItems = new ArrayList<>();
        for (DealRiskDto risk : risks) {
            Deal deal = deals.get(risk.getDealId());
            if (deal == null) continue;
            Company company = deal.getCompanyId() == null ? null : companies.get(deal.getCompanyId());
            riskItems.add(new DashboardDealRiskDto(
                DealDto.from(deal), CompanyDto.from(company), risk));
        }
        return new RelationshipDashboardDto(
            scoringService.summarizeScores(contactScores, companyScores),
            contactItems,
            companyItems,
            riskItems
        );
    }

    private static List<RelationshipTemperatureDto> cooling(List<RelationshipTemperatureDto> scores) {
        return scores.stream()
            .filter(score -> "cooling".equals(score.getTrend()))
            .sorted(Comparator.comparingInt(RelationshipDashboardService::daysSinceTouch).reversed())
            .limit(DASHBOARD_LIMIT)
            .toList();
    }

    private static int daysSinceTouch(RelationshipTemperatureDto score) {
        return score.getDaysSinceTouch() == null ? 0 : score.getDaysSinceTouch();
    }

    private Map<Integer, Person> peopleById(
            int workspaceId, List<RelationshipTemperatureDto> temperatures) {
        List<Integer> ids = temperatures.stream().map(RelationshipTemperatureDto::getId).toList();
        if (ids.isEmpty()) return Map.of();
        Map<Integer, Person> result = new HashMap<>();
        for (Person person : personMapper.getByIds(workspaceId, ids)) result.put(person.getId(), person);
        return result;
    }

    private Map<Integer, Deal> dealsById(int workspaceId, List<DealRiskDto> risks) {
        List<Integer> ids = risks.stream().map(DealRiskDto::getDealId).toList();
        if (ids.isEmpty()) return Map.of();
        Map<Integer, Deal> result = new HashMap<>();
        for (Deal deal : dealMapper.getByIds(workspaceId, ids)) result.put(deal.getId(), deal);
        return result;
    }

    private Map<Integer, Company> companiesById(int workspaceId, Set<Integer> ids) {
        if (ids.isEmpty()) return Map.of();
        Map<Integer, Company> result = new HashMap<>();
        for (Company company : companyMapper.getByIds(workspaceId, List.copyOf(ids))) {
            result.put(company.getId(), company);
        }
        return result;
    }
}
