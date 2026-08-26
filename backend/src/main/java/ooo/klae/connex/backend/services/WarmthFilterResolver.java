package ooo.klae.connex.backend.services;

import java.time.Clock;
import java.util.List;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.dto.WarmthFilter;

/**
 * Resolves canonical relationship-warmth filters for the contact and company browsers.
 *
 * <p>Warmth decays continuously, so every filter is evaluated against one server-resolved instant
 * rather than a persisted score: the resolver owns that clock so a page, its total, its id
 * selection, and its export all read the same reference.
 */
@Service
@RequiredArgsConstructor
public class WarmthFilterResolver {
    private final Clock clock;

    /**
     * Resolves request parameters into a validated warmth filter.
     *
     * @param bands raw requested band keys
     * @param noWarmth raw no-history flag
     * @param goesColdWithinDays raw decay horizon in whole days
     * @param sort raw requested sort key
     * @return canonical filter, or null when the request needs no warmth computation
     */
    public WarmthFilter resolve(
            List<String> bands, boolean noWarmth, Integer goesColdWithinDays, String sort) {
        return WarmthFilter.fromRequest(bands, noWarmth, goesColdWithinDays, sort, clock.instant());
    }

    /** Returns the unrestricted filter that scores every visible record for a facet. */
    public WarmthFilter forFacets() {
        return WarmthFilter.forScoring(clock.instant());
    }
}
