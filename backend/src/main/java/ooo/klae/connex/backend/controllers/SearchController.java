package ooo.klae.connex.backend.controllers;

import java.util.List;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.dto.SearchResultsDto;
import ooo.klae.connex.backend.services.SearchService;

@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
public class SearchController {
    private final SearchService searchService;

    /**
     * Searches selected result groups, or all groups when {@code types} is omitted.
     *
     * @param query the search text
     * @param types repeated or comma-separated result-group names
     * @return the fixed-shape grouped search results
     */
    @GetMapping
    public SearchResultsDto search(
            @RequestParam String query,
            @RequestParam(required = false) List<String> types) {
        return searchService.search(query, SearchService.parseTypes(types));
    }
}
