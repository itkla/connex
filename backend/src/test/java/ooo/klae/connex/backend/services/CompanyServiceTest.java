package ooo.klae.connex.backend.services;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.TagMapper;

class CompanyServiceTest extends AbstractServiceTest {

    @Autowired CompanyService companyService;

    @Test
    void getPersonsByCompanyId_returnsOnlyMatchingPeople() {
        Company company1 = newCompany();
        Company company2 = newCompany();
        Person p1 = newPerson(company1);
        Person p2 = newPerson(company2);

        List<Person> people = companyService.getPersonsByCompanyId(company1.getId());

        assertTrue(people.stream().anyMatch(x -> x.getId() == p1.getId()));
        assertTrue(people.stream().noneMatch(x -> x.getId() == p2.getId()));
    }

    @Test
    void getPersonsByCompanyId_throwsWhenCompanyMissing() {
        assertThrows(ResourceNotFoundException.class, () -> companyService.getPersonsByCompanyId(-1));
    }

    @Test
    void getDealsByCompanyId_returnsOnlyMatchingDeals() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Company company1 = newCompany();
        Company company2 = newCompany();
        Deal d1 = newDeal(pipeline, stage, company1);
        Deal d2 = newDeal(pipeline, stage, company2);

        List<Deal> deals = companyService.getDealsByCompanyId(company1.getId());

        assertTrue(deals.stream().anyMatch(x -> x.getId() == d1.getId()));
        assertTrue(deals.stream().noneMatch(x -> x.getId() == d2.getId()));
    }

    @Test
    void getDealsByCompanyId_throwsWhenCompanyMissing() {
        assertThrows(ResourceNotFoundException.class, () -> companyService.getDealsByCompanyId(-1));
    }

    @Test
    void getMatchingCompanyIdsRejectsRequestsWithoutFilters() {
        assertThrows(BadRequestException.class,
            () -> companyService.getMatchingCompanyIds(null, null, false, null));
    }

    @Test
    void getMatchingCompanyIdsForwardsEveryFilterWithinTheCurrentWorkspace() {
        CompanyMapper mapper = mock(CompanyMapper.class);
        WorkspaceService workspaceService = mock(WorkspaceService.class);
        CompanyService service = companyService(mapper, workspaceService);
        List<String> industry = List.of("Technology");
        List<Integer> requestedIds = List.of(3, 5);
        List<Integer> matchingIds = List.of(3);
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(7);
        when(mapper.countCompanies(7, "%Target%", industry, true, requestedIds)).thenReturn(1L);
        when(mapper.getCompanyIdsFiltered(7, "%Target%", industry, true, requestedIds, 1000))
            .thenReturn(matchingIds);

        assertEquals(matchingIds,
            service.getMatchingCompanyIds("%Target%", industry, true, requestedIds));

        verify(mapper).countCompanies(7, "%Target%", industry, true, requestedIds);
        verify(mapper).getCompanyIdsFiltered(7, "%Target%", industry, true, requestedIds, 1000);
    }

    @Test
    void getMatchingCompanyIdsRejectsTooManyMatchesBeforeFetchingIds() {
        CompanyMapper mapper = mock(CompanyMapper.class);
        WorkspaceService workspaceService = mock(WorkspaceService.class);
        CompanyService service = companyService(mapper, workspaceService);
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(7);
        when(mapper.countCompanies(7, "%Target%", null, false, null)).thenReturn(1001L);

        assertThrows(BadRequestException.class,
            () -> service.getMatchingCompanyIds("%Target%", null, false, null));

        verify(mapper, never()).getCompanyIdsFiltered(7, "%Target%", null, false, null, 1000);
    }

    private CompanyService companyService(CompanyMapper mapper, WorkspaceService workspaceService) {
        return new CompanyService(
            mapper,
            mock(TagMapper.class),
            mock(PersonMapper.class),
            mock(DealMapper.class),
            mock(AuditService.class),
            mock(RuleTriggerPublisher.class),
            workspaceService,
            mock(CustomFieldValueService.class),
            mock(ReferenceService.class)
        );
    }
}
