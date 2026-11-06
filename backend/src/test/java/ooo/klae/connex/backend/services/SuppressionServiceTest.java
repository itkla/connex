package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.SuppressionEntryDto;
import ooo.klae.connex.backend.dto.SuppressionEntryRequest;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;

class SuppressionServiceTest extends AbstractServiceTest {
    @Autowired private SuppressionService suppressionService;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void contactListingHidesArchivedSubjectsAndRestoresTheirRetainedSuppressions() {
        Person person = newPerson(newCompany());
        var sticky = suppressionService.add(new SuppressionEntryRequest(
                "workspace", "email", "previous@example.test", person.getId(), "do_not_contact", null));
        var addressOnly = suppressionService.add(new SuppressionEntryRequest(
                "workspace", "email", person.getEmail(), null, "unsubscribe", null));
        var expectedIds = java.util.List.of(sticky.id(), addressOnly.id());
        assertEquals(expectedIds, suppressionService.listForPerson(person.getId(), 0)
                .items().stream().map(entry -> entry.id()).toList());

        assertEquals(1, personMapper.archive(workspace.getId(), person.getId()));

        assertThrows(ResourceNotFoundException.class,
                () -> suppressionService.listForPerson(person.getId(), 0));
        assertTrue(suppressionService.list().stream().map(SuppressionEntryDto::id).toList()
                .containsAll(expectedIds));

        assertEquals(1, personMapper.restore(workspace.getId(), person.getId()));
        assertEquals(expectedIds, suppressionService.listForPerson(person.getId(), 0)
                .items().stream().map(entry -> entry.id()).toList());
    }

    @Test
    void contactListingMinimizesAndBoundsPersonStickyAndCanonicalAddressMatches() {
        Person person = newPerson(newCompany());
        person.setEmail("matching@example.test");
        person.setPhone("+81 90-1234-5678");
        personMapper.update(person);
        var sticky = suppressionService.add(new SuppressionEntryRequest("workspace", "email", "old@example.test", person.getId(), "do_not_contact", "private operator note"));
        var addressOnly = suppressionService.add(new SuppressionEntryRequest("workspace", "email", "MATCHING@example.test", null, "unsubscribe", "private operator note"));
        var sms = suppressionService.add(new SuppressionEntryRequest("workspace", "sms", "+81 (90) 1234-5678", null, "manual", null));
        var unrelated = suppressionService.add(new SuppressionEntryRequest("workspace", "email", "unrelated@example.test", null, "complaint", "unrelated private note"));
        var page = suppressionService.listForPerson(person.getId(), 0);
        assertEquals(java.util.List.of(sticky.id(), addressOnly.id(), sms.id()), page.items().stream().map(entry -> entry.id()).toList());
        org.junit.jupiter.api.Assertions.assertNull(page.nextAfterId());
        assertEquals(java.util.Set.of("id", "channel", "address", "reason"),
                java.util.Arrays.stream(ooo.klae.connex.backend.dto.ContactSuppressionDto.class.getRecordComponents())
                        .map(java.lang.reflect.RecordComponent::getName).collect(java.util.stream.Collectors.toSet()));
        for (int index = 0; index < 52; index++) {
            suppressionService.add(new SuppressionEntryRequest("workspace", "email", "old@example.test", person.getId(), "manual", null));
        }
        var first = suppressionService.listForPerson(person.getId(), 0);
        assertEquals(50, first.items().size());
        org.junit.jupiter.api.Assertions.assertNotNull(first.nextAfterId());
        var last = suppressionService.listForPerson(person.getId(), first.nextAfterId());
        assertEquals(5, last.items().size());
        org.junit.jupiter.api.Assertions.assertNull(last.nextAfterId());
        assertTrue(suppressionService.list().stream().anyMatch(entry -> entry.id() == unrelated.id()));
    }

    @Test
    void contactListingRejectsForeignSubjectsAndNeverReturnsForeignWorkspaceMatches() {
        Person person = newPerson(newCompany());
        var foreign = new ooo.klae.connex.backend.beans.Workspace();
        foreign.setName("Foreign suppression workspace");
        foreign.setSlug("foreign-suppression-" + java.util.UUID.randomUUID());
        foreign.setOrgId(workspace.getOrgId());
        workspaceMapper.insert(foreign);
        jdbcTemplate.update("INSERT INTO suppression_entry (workspace_id,scope,channel,address,reason) VALUES (?,'workspace','email',?,'manual')",
                foreign.getId(), person.getEmail());
        jdbcTemplate.update("INSERT INTO suppression_entry (workspace_id,scope,channel,address,person_id,reason) VALUES (?,'workspace','email',?,?, 'manual')",
                foreign.getId(), "foreign-old@example.test", person.getId());
        assertTrue(suppressionService.listForPerson(person.getId(), 0).items().isEmpty());
        var foreignPerson = new Person();
        foreignPerson.setWorkspaceId(foreign.getId());
        foreignPerson.setName("Foreign subject");
        personMapper.insert(foreignPerson);
        assertThrows(ResourceNotFoundException.class, () -> suppressionService.listForPerson(foreignPerson.getId(), 0));
        User member = newUser();
        authenticateAs(member, workspace.getId());
        assertThrows(ForbiddenException.class, () -> suppressionService.listForPerson(person.getId(), 0));
    }

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
