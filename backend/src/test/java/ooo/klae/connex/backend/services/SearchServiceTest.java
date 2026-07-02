package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.dto.SearchResultsDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;

class SearchServiceTest extends AbstractServiceTest {

    @Autowired private SearchService searchService;

    @Test
    void blankQueryReturnsEmptyResults() {
        SearchResultsDto results = searchService.search("   ");
        assertTrue(results.getCompanies().isEmpty());
    }

    @Test
    void overlongQueryIsRejected() {
        String tooLong = "a".repeat(201);
        assertThrows(BadRequestException.class, () -> searchService.search(tooLong));
    }

    @Test
    void matchingCompanyIsFoundInTheActiveWorkspace() {
        Company company = newCompany();
        SearchResultsDto results = searchService.search(company.getName());
        assertTrue(results.getCompanies().stream().anyMatch(c -> c.getId() == company.getId()));
    }
}
