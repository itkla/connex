package ooo.klae.connex.backend.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.ai.masking.EntityKind;
import ooo.klae.connex.backend.ai.masking.MaskingContext;
import ooo.klae.connex.backend.ai.masking.MaskingEngine;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.PersonEmployment;
import ooo.klae.connex.backend.dto.PersonConnectionDto;
import ooo.klae.connex.backend.services.CompanyService;
import ooo.klae.connex.backend.services.ConnectionService;
import ooo.klae.connex.backend.services.DealService;
import ooo.klae.connex.backend.services.PersonService;

@ExtendWith(MockitoExtension.class)
class AiRelationshipContextTest {
    private static final int COMPANY_ID = 5;
    private static final int CURRENT_DEAL_ID = 10;
    private static final int PERSON_ID = 73;

    @Mock private DealService dealService;
    @Mock private PersonService personService;
    @Mock private ConnectionService connectionService;
    @Mock private CompanyService companyService;

    private AiRelationshipContext context;

    @BeforeEach
    void setUp() {
        context = new AiRelationshipContext(
                dealService,
                personService,
                connectionService,
                companyService,
                Clock.fixed(Instant.parse("2026-07-11T00:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void appendAccountHistory_masksReasonsExcludesCurrentDealAndTagsOutcomes() {
        when(dealService.getAccountHistoryDeals(
            COMPANY_ID, CURRENT_DEAL_ID, AiRelationshipContext.MAX_ACCOUNT_DEALS)).thenReturn(List.of(
                deal(11, Boolean.TRUE, 0, 200000, "USD", "Strong executive sponsor", "2025-01-15"),
                deal(12, Boolean.FALSE, 150000, 0, "USD", "Lost on price to a competitor", "2024-06-01")));

        StringBuilder prompt = new StringBuilder();
        MaskingContext ctx = new MaskingContext();
        context.appendAccountHistory(prompt, COMPANY_ID, CURRENT_DEAL_ID, ctx);
        String out = prompt.toString();

        assertTrue(out.contains("Outcome: won"));
        assertTrue(out.contains("Outcome: lost"));
        assertTrue(out.contains("200000 USD"));
        assertTrue(out.contains("150000 USD"));
        assertTrue(out.contains("Lost on price to a competitor"));
    }

    @Test
    void appendAccountHistory_omitsSpecialCareReason() {
        when(dealService.getAccountHistoryDeals(
            COMPANY_ID, CURRENT_DEAL_ID, AiRelationshipContext.MAX_ACCOUNT_DEALS)).thenReturn(List.of(
                deal(11, Boolean.FALSE, 100000, 0, "USD", "Buyer disclosed a medical diagnosis", "2024-06-01")));

        StringBuilder prompt = new StringBuilder();
        context.appendAccountHistory(prompt, COMPANY_ID, CURRENT_DEAL_ID, new MaskingContext());
        String out = prompt.toString();

        assertTrue(out.contains(MaskingEngine.OMITTED_BY_POLICY));
        assertFalse(out.contains("medical diagnosis"));
    }

    @Test
    void appendAccountHistory_capsHistory() {
        List<Deal> many = new ArrayList<>();
        for (int i = 20; i < 40; i++) {
            many.add(deal(i, Boolean.TRUE, 0, 1000 + i, "USD", "Won deal " + i, "2025-01-0" + (i % 9 + 1)));
        }
        when(dealService.getAccountHistoryDeals(
            COMPANY_ID, CURRENT_DEAL_ID, AiRelationshipContext.MAX_ACCOUNT_DEALS)).thenReturn(many);

        StringBuilder prompt = new StringBuilder();
        context.appendAccountHistory(prompt, COMPANY_ID, CURRENT_DEAL_ID, new MaskingContext());

        assertEquals(AiRelationshipContext.MAX_ACCOUNT_DEALS,
                countOccurrences(prompt.toString(), "- Outcome:"));
    }

    @Test
    void appendAccountHistory_failsClosedToNoneWhenFetchThrows() {
        when(dealService.getAccountHistoryDeals(anyInt(), anyInt(), anyInt()))
            .thenThrow(new RuntimeException("boom"));

        StringBuilder prompt = new StringBuilder();
        context.appendAccountHistory(prompt, COMPANY_ID, CURRENT_DEAL_ID, new MaskingContext());

        assertTrue(prompt.toString().contains("ACCOUNT_HISTORY\n- none\n"));
    }

    @Test
    void appendStakeholderBackground_tokenizesEmployersAndConnectionsAndScreensSpecialCare() {
        MaskingContext ctx = new MaskingContext();
        String stakeholderToken = MaskingEngine.maskField(EntityKind.PERSON, "Champion Person", ctx);

        PersonEmployment employment = new PersonEmployment();
        employment.setCompanyName("Globex Corp");
        employment.setTitle("VP Sales");
        employment.setStartedAt("2020-01-01");
        employment.setEndedAt(null);
        when(personService.getEmploymentHistory(PERSON_ID)).thenReturn(List.of(employment));
        when(connectionService.getConnections(PERSON_ID)).thenReturn(List.of(
                connection("Jane Roe", "colleague", 8, "Trusted former teammate"),
                connection("Sam Poe", "knows", 3, "Mentioned a medical diagnosis")));

        StringBuilder prompt = new StringBuilder();
        context.appendStakeholderBackground(prompt, PERSON_ID, stakeholderToken, ctx);
        String out = prompt.toString();

        assertTrue(out.contains("- Person: " + stakeholderToken));
        assertTrue(out.contains("Employment: {{C1}}"));
        assertTrue(out.contains("Title: VP Sales"));
        assertTrue(out.contains("Status: current"));
        assertTrue(out.contains("Connection: {{P2}}"));
        assertTrue(out.contains("Trusted former teammate"));
        assertTrue(out.contains(MaskingEngine.OMITTED_BY_POLICY));
        assertFalse(out.contains("Globex Corp"));
        assertFalse(out.contains("Jane Roe"));
        assertFalse(out.contains("medical diagnosis"));
    }

    @Test
    void appendStakeholderBackground_capsConnections() {
        MaskingContext ctx = new MaskingContext();
        String stakeholderToken = MaskingEngine.maskField(EntityKind.PERSON, "Champion Person", ctx);
        List<PersonConnectionDto> connections = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            connections.add(connection("Person " + i, "knows", i, "note " + i));
        }
        when(personService.getEmploymentHistory(PERSON_ID)).thenReturn(List.of());
        when(connectionService.getConnections(PERSON_ID)).thenReturn(connections);

        StringBuilder prompt = new StringBuilder();
        context.appendStakeholderBackground(prompt, PERSON_ID, stakeholderToken, ctx);

        assertEquals(AiRelationshipContext.MAX_CONNECTIONS,
                countOccurrences(prompt.toString(), "Connection:"));
    }

    @Test
    void appendCompanyProfile_addsIndustryWhenPresent() {
        Company company = new Company();
        company.setIndustry("Manufacturing");
        when(companyService.getCompanyById(COMPANY_ID)).thenReturn(company);

        StringBuilder prompt = new StringBuilder();
        context.appendCompanyProfile(prompt, COMPANY_ID, new MaskingContext());

        assertTrue(prompt.toString().contains("Industry: Manufacturing"));
    }

    private static Deal deal(
            int id, Boolean won, double value, double actualValue, String currency, String reason, String closedAt) {
        Deal deal = new Deal();
        deal.setId(id);
        deal.setWon(won);
        deal.setValue(value);
        deal.setActualValue(actualValue);
        deal.setCurrency(currency);
        deal.setClosedReason(reason);
        deal.setClosedAt(closedAt);
        return deal;
    }

    private static PersonConnectionDto connection(String name, String type, int strength, String note) {
        PersonConnectionDto connection = new PersonConnectionDto();
        connection.setPersonName(name);
        connection.setType(type);
        connection.setStrength(strength);
        connection.setNote(note);
        return connection;
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int from = 0;
        while (true) {
            int index = haystack.indexOf(needle, from);
            if (index < 0) {
                return count;
            }
            count++;
            from = index + needle.length();
        }
    }
}
