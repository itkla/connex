package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.dto.FacetCount;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.mappers.DealMapper;

class CurrencyFilterServiceTest {
    private final DealMapper mapper = mock(DealMapper.class);
    private final WorkspaceService workspace = mock(WorkspaceService.class);
    private final CurrencyFilterService service = new CurrencyFilterService(mapper, workspace);

    @Test
    void resolvesEveryExistingBucketWithoutChangingItsStoredSpelling() {
        when(workspace.getCurrentWorkspaceId()).thenReturn(7);
        for (String currency : List.of("X-LOCAL", "US$", " jpy ", "usd", "XXX")) {
            when(mapper.countsByCurrency(7)).thenReturn(List.of(new FacetCount(currency, 1, null)));
            assertEquals(currency, service.resolve(currency));
            assertEquals(currency, service.resolve(currency.trim().toUpperCase(java.util.Locale.ROOT)));
        }
    }

    @Test
    void doesNotAcceptALegacyBucketFromAnotherWorkspaceOrCreateAnUnknownBucket() {
        when(workspace.getCurrentWorkspaceId()).thenReturn(7);
        when(mapper.countsByCurrency(7)).thenReturn(List.of(new FacetCount("JPY", 1, null)));
        assertThrows(BadRequestException.class, () -> service.resolve("X-LOCAL"));
        verify(mapper).countsByCurrency(7);
        assertEquals("KWD", service.resolve(" kwd "));
    }

    @Test
    void absentAndBlankFiltersNeverBecomeAnUnscopedMoneyQuery() {
        assertNull(service.resolve(null));
        assertThrows(BadRequestException.class, () -> service.resolve(" "));
        verifyNoInteractions(mapper, workspace);
    }
}
