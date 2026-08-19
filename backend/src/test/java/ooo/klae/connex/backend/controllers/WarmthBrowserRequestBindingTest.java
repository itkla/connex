package ooo.klae.connex.backend.controllers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import ooo.klae.connex.backend.dto.FacetCount;
import ooo.klae.connex.backend.dto.MemberScope;
import ooo.klae.connex.backend.dto.WarmthFilter;
import ooo.klae.connex.backend.exceptions.GlobalExceptionHandler;
import ooo.klae.connex.backend.observability.ErrorReporter;
import ooo.klae.connex.backend.services.BulkOperationService;
import ooo.klae.connex.backend.services.CompanyService;
import ooo.klae.connex.backend.services.ConnectionService;
import ooo.klae.connex.backend.services.EmploymentService;
import ooo.klae.connex.backend.services.MemberScopeResolver;
import ooo.klae.connex.backend.services.PersonLifecycleService;
import ooo.klae.connex.backend.services.PersonQualificationService;
import ooo.klae.connex.backend.services.PersonService;
import ooo.klae.connex.backend.services.WarmthFilterResolver;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.tenant.TenantContext;

/**
 * Binds the browsers' new warmth query parameters over real HTTP so the contract the records
 * browser sends is validated end to end rather than only at the Java call boundary.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WarmthBrowserRequestBindingTest {
    private static final Instant NOW = Instant.parse("2026-08-18T00:00:00Z");

    @Mock private PersonService personService;
    @Mock private PersonLifecycleService personLifecycleService;
    @Mock private PersonQualificationService personQualificationService;
    @Mock private EmploymentService employmentService;
    @Mock private ConnectionService connectionService;
    @Mock private BulkOperationService bulkOperationService;
    @Mock private CompanyService companyService;
    @Mock private WorkspaceService workspaceService;
    @Mock private MemberScopeResolver memberScopeResolver;
    @Mock private ErrorReporter errorReporter;
    @Mock private TenantContext tenantContext;

    private MockMvc persons;
    private MockMvc companies;

    @BeforeEach
    void setUp() {
        WarmthFilterResolver warmthFilterResolver =
            new WarmthFilterResolver(Clock.fixed(NOW, ZoneOffset.UTC));
        when(workspaceService.getCurrentUserId()).thenReturn(7);
        when(memberScopeResolver.resolve(any(), any(), eq(7))).thenReturn(MemberScope.allTeam());
        GlobalExceptionHandler exceptionHandler =
            new GlobalExceptionHandler(errorReporter, tenantContext);
        persons = MockMvcBuilders.standaloneSetup(new PersonController(
                personService, personLifecycleService, personQualificationService, employmentService,
                connectionService, bulkOperationService, workspaceService, memberScopeResolver,
                warmthFilterResolver))
            .setControllerAdvice(exceptionHandler)
            .build();
        companies = MockMvcBuilders.standaloneSetup(new CompanyController(
                companyService, bulkOperationService, workspaceService, memberScopeResolver,
                warmthFilterResolver))
            .setControllerAdvice(exceptionHandler)
            .build();
    }

    @Test
    void theContactsPageAcceptsWarmthBandsNoWarmthAndTheDecayHorizonTogether() throws Exception {
        persons.perform(get("/api/persons/page")
                .param("warmthBands", "hot", "cold")
                .param("noWarmth", "true")
                .param("goesColdWithinDays", "30")
                .param("sort", "warmth")
                .param("dir", "desc"))
            .andExpect(status().isOk());

        WarmthFilter warmth = capturedContactFilter();
        assertBands(warmth, Set.of("hot", "cold"), true, 30);
    }

    @Test
    void aWarmthSortNoLongerFailsTheContactsPage() throws Exception {
        persons.perform(get("/api/persons/page").param("sort", "warmth"))
            .andExpect(status().isOk());

        WarmthFilter warmth = capturedContactFilter();
        assertBands(warmth, Set.of(), false, null);
    }

    @Test
    void anOrdinaryContactsPageResolvesNoWarmthFilterAtAll() throws Exception {
        persons.perform(get("/api/persons/page").param("sort", "name"))
            .andExpect(status().isOk());

        assertNull(capturedContactFilter());
    }

    @Test
    void theNoHistoryFacetKeyIsAcceptedAsABandValue() throws Exception {
        persons.perform(get("/api/persons/page").param("warmthBands", "warm", "__none__"))
            .andExpect(status().isOk());

        WarmthFilter warmth = capturedContactFilter();
        assertBands(warmth, Set.of("warm"), true, null);
    }

    /** The browser serializes its facet selections as one comma-joined value, not repeated keys. */
    @Test
    void commaJoinedBandsBindAsSeparateValues() throws Exception {
        persons.perform(get("/api/persons/page").param("warmthBands", "hot,cool,__none__"))
            .andExpect(status().isOk());

        assertBands(capturedContactFilter(), Set.of("hot", "cool"), true, null);
    }

    @Test
    void unknownBandsAndOutOfRangeHorizonsFailBeforeAnyQueryRuns() throws Exception {
        persons.perform(get("/api/persons/page").param("warmthBands", "lukewarm"))
            .andExpect(status().isBadRequest());
        persons.perform(get("/api/persons/page").param("goesColdWithinDays", "0"))
            .andExpect(status().isBadRequest());

        verify(personService, never()).getPersonsPage(
            any(), any(), any(), any(), any(), anyBoolean(), any(),
            any(), anyBoolean(), any(),
            anyBoolean(), any(),
            anyBoolean(), anyBoolean(),
            any(), anyInt(), anyInt());
    }

    @Test
    void aWarmthBandIsEnoughOfAFilterToSelectMatchingContactIds() throws Exception {
        persons.perform(get("/api/persons/ids").param("warmthBands", "cold"))
            .andExpect(status().isOk());

        verify(personService).getMatchingPersonIds(
            eq(null), eq(null), eq(null), eq(false), any(), eq(null), eq(false), eq(null), eq(false),
            eq(null), eq(false), eq(false), any(WarmthFilter.class));
    }

    @Test
    void theContactFacetsResponseCarriesTheWarmthBandBuckets() throws Exception {
        when(personService.countsByWarmthBand(any())).thenReturn(List.of(
            facet("hot", 3), facet(WarmthFilter.NO_WARMTH_KEY, 5)));

        persons.perform(get("/api/persons/facets"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.warmthBands[0].key").value("hot"))
            .andExpect(jsonPath("$.warmthBands[0].count").value(3))
            .andExpect(jsonPath("$.warmthBands[1].key").value(WarmthFilter.NO_WARMTH_KEY));
    }

    @Test
    void theCompaniesPageAndFacetsExposeTheSameWarmthContract() throws Exception {
        when(companyService.countsByWarmthBand(any())).thenReturn(List.of(facet("cool", 2)));

        companies.perform(get("/api/companies/page")
                .param("warmthBands", "cool")
                .param("noWarmth", "true")
                .param("sort", "warmth"))
            .andExpect(status().isOk());
        companies.perform(get("/api/companies/facets"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.warmthBands[0].key").value("cool"));

        ArgumentCaptor<WarmthFilter> captor = ArgumentCaptor.forClass(WarmthFilter.class);
        verify(companyService).getCompaniesPage(
            any(), any(), any(), any(), anyBoolean(), any(), any(),
            anyBoolean(), captor.capture(),
            anyInt(), anyInt());
        assertBands(captor.getValue(), Set.of("cool"), true, null);
    }

    private WarmthFilter capturedContactFilter() {
        ArgumentCaptor<WarmthFilter> captor = ArgumentCaptor.forClass(WarmthFilter.class);
        verify(personService).getPersonsPage(
            any(), any(), any(), any(), any(), anyBoolean(), any(),
            any(), anyBoolean(), any(),
            anyBoolean(), any(),
            anyBoolean(), anyBoolean(),
            captor.capture(), anyInt(),
            anyInt());
        return captor.getValue();
    }

    private static void assertBands(
            WarmthFilter warmth, Set<String> bands, boolean noWarmth, Integer horizon) {
        assertNotNull(warmth);
        assertEquals(bands, warmth.bands());
        assertEquals(noWarmth, warmth.noWarmth());
        assertEquals(horizon, warmth.goesColdWithinDays());
    }

    private static FacetCount facet(String key, long count) {
        FacetCount facet = new FacetCount();
        facet.setKey(key);
        facet.setCount(count);
        return facet;
    }
}
