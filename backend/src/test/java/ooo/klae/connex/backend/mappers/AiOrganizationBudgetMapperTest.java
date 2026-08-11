package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.Organization;

class AiOrganizationBudgetMapperTest extends AbstractMapperTest {
    @Autowired private AiOrganizationBudgetMapper budgetMapper;
    @Autowired private OrganizationMapper organizationMapper;

    @Test
    void sharedOrganizationBudgetAndReservationsRoundTrip() {
        Organization organization = new Organization();
        organization.setName("Budget Organization " + unique());
        organization.setSlug("budget-org-" + unique());
        organizationMapper.insert(organization);
        int orgId = organization.getId();
        LocalDate day = LocalDate.of(2026, 8, 10);
        budgetMapper.upsert(orgId, 1_000);
        budgetMapper.ensureUsage(orgId, day);
        budgetMapper.insertReservation(
                "2cf6d5a4-e640-4c67-9908-726933adaad2",
                orgId,
                day,
                300,
                LocalDateTime.of(2026, 8, 10, 1, 0));

        assertEquals(1_000, budgetMapper.getForUpdate(orgId).getDailyTokenLimit());
        assertNotNull(budgetMapper.getUsageForUpdate(orgId, day));
        assertEquals(300, budgetMapper.sumReservedTokens(orgId, day));

        budgetMapper.addConsumedTokens(orgId, day, 125);

        assertEquals(125, budgetMapper.getConsumedTokens(orgId, day));
    }
}
