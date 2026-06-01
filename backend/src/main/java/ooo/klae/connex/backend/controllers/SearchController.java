package ooo.klae.connex.backend.controllers;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ooo.klae.connex.backend.dto.SearchResultsDto;
import ooo.klae.connex.backend.services.SearchService;

@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
@Slf4j
public class SearchController {
    private final SearchService searchService;

    @GetMapping
    public SearchResultsDto search(@RequestParam String query) {
        log.info("Searching for: {}", query);
        return searchService.search(query);
    }
}