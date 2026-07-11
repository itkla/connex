package ooo.klae.connex.backend.services;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.dto.SegmentDefinition;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.TagMapper;

class CompanySegmentServiceTest {

    private final CompanyMapper companyMapper = mock(CompanyMapper.class);
    private final WorkspaceService workspaceService = mock(WorkspaceService.class);
    private final SegmentService segmentService = mock(SegmentService.class);
    private final CompanyService companyService = new CompanyService(
        companyMapper,
        mock(TagMapper.class),
        mock(PersonMapper.class),
        mock(DealMapper.class),
        mock(AuditService.class),
        mock(RuleTriggerPublisher.class),
        workspaceService,
        mock(CustomFieldValueService.class),
        segmentService,
        mock(AuthService.class)
    );

    @Test
    void segmentPageReturnsEmptyWithoutQueryingCompaniesWhenNothingMatches() {
        SegmentDefinition definition = mock(SegmentDefinition.class);
        when(segmentService.evaluate("company", definition)).thenReturn(List.of());

        var result = companyService.getSegmentCompaniesPage(
            definition, "%Target%", "name", "asc", null, false, 25, 0);

        assertTrue(result.items().isEmpty());
        assertEquals(0, result.total());
        verifyNoInteractions(companyMapper);
    }

    @Test
    void segmentPageScansFilteredRowsWithoutExpandingIdsIntoTheQuery() {
        SegmentDefinition definition = mock(SegmentDefinition.class);
        List<Integer> ids = List.of(3, 5);
        List<String> industries = List.of("Technology");
        Company first = company(3);
        Company excluded = company(4);
        Company second = company(5);
        when(segmentService.evaluate("company", definition)).thenReturn(ids);
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(7);
        when(companyMapper.getCompaniesPage(
            7, "%Target%", "name", "desc", industries, true, null, 250, 0))
            .thenReturn(List.of(first, excluded, second));
        when(companyMapper.getCompaniesPage(
            7, "%Target%", "name", "desc", industries, true, null, 250, 3))
            .thenReturn(List.of());

        var result = companyService.getSegmentCompaniesPage(
            definition, "%Target%", "name", "desc", industries, true, 25, 0);

        assertEquals(List.of(first, second), result.items());
        assertEquals(2, result.total());
        verify(companyMapper).getCompaniesPage(
            7, "%Target%", "name", "desc", industries, true, null, 250, 0);
    }

    @Test
    void matchingSegmentIdsApplyTheBulkLimitThroughTheWorkspaceScopedQuery() {
        SegmentDefinition definition = mock(SegmentDefinition.class);
        List<Integer> segmentIds = List.of(3, 5);
        List<Integer> matchingIds = List.of(5);
        when(segmentService.evaluate("company", definition)).thenReturn(segmentIds);
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(7);
        when(companyMapper.getCompanyIdsFiltered(7, "%Target%", null, false, null, 250, 0))
            .thenReturn(List.of(2, 5, 7));
        when(companyMapper.getCompanyIdsFiltered(7, "%Target%", null, false, null, 250, 3))
            .thenReturn(List.of());

        assertEquals(matchingIds, companyService.getMatchingSegmentCompanyIds(
            definition, "%Target%", null, false));

        verify(companyMapper).getCompanyIdsFiltered(7, "%Target%", null, false, null, 250, 0);
    }

    private static Company company(int id) {
        Company company = new Company();
        company.setId(id);
        return company;
    }
}
