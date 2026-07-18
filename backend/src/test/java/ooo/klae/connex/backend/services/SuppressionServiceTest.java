package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.SuppressionEntryDto;
import ooo.klae.connex.backend.dto.SuppressionEntryRequest;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;

class SuppressionServiceTest extends AbstractServiceTest {
    @Autowired private SuppressionService suppressionService;

    @Test
    void addNormalizesAndRemoveDeletesTheEntry() {
        Person person = newPerson(newCompany());
        SuppressionEntryDto entry = suppressionService.add(new SuppressionEntryRequest(
                "global", "email", "  MIXED.CASE@EXAMPLE.COM  ", person.getId(),
                "unsubscribe", "requested"));

        assertEquals("mixed.case@example.com", entry.address());
        assertTrue(suppressionService.list().stream().anyMatch(candidate -> candidate.id() == entry.id()));

        suppressionService.remove(entry.id());
        assertThrows(ResourceNotFoundException.class, () -> suppressionService.remove(entry.id()));
    }

    @Test
    void addStoresAnSmsSuppressionInTheCanonicalPhoneFormRegardlessOfHowItWasTyped() {
        Person person = newPerson(newCompany());

        SuppressionEntryDto entry = suppressionService.add(new SuppressionEntryRequest(
                "workspace", "sms", " +81 (90) 1234-5678 ", person.getId(), "do_not_contact", null));

        assertEquals("+819012345678", entry.address());
    }

    @Test
    void addRejectsAnSmsAddressWithTooFewDigitsToBeAPhoneNumber() {
        assertThrows(BadRequestException.class, () -> suppressionService.add(new SuppressionEntryRequest(
                "workspace", "sms", "n/a", null, "manual", null)));
    }

    @Test
    void suppressionRequiresConsentManagePermission() {
        User member = newUser();
        authenticateAs(member, workspace.getId());

        assertThrows(ForbiddenException.class, suppressionService::list);
        assertThrows(ForbiddenException.class, () -> suppressionService.add(new SuppressionEntryRequest(
                "workspace", "email", "blocked@example.com", null, "manual", null)));
    }
}
