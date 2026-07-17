package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.dto.SuppressionEntryRequest;

/**
 * Proves the audience snapshot matches suppressions on the address the channel actually contacts,
 * against a real database, so the snapshot classification cannot drift from the dispatch re-check.
 */
class AudienceEligibilitySuppressionTest extends AbstractServiceTest {

    private static final String PURPOSE = "marketing";

    @Autowired private AudienceEligibilityService audienceEligibilityService;
    @Autowired private SuppressionService suppressionService;

    @Test
    void smsSnapshotExcludesAPersonWhoseSmsSuppressionIsStoredInADifferentPhoneFormat() {
        Person person = newPersonWith("sms.blocked@example.com", "+81 (90) 1234-5678");
        suppressionService.add(new SuppressionEntryRequest(
                "workspace", "sms", "+819012345678", person.getId(), "do_not_contact", null));

        AudienceEligibilityService.AudienceClassification result = audienceEligibilityService.classify(
                workspace.getId(), List.of(person.getId()), "sms", PURPOSE);

        assertEquals(Set.of(person.getId()), result.suppressed());
        assertTrue(result.includedIds().isEmpty());
        assertEquals("suppressed", result.reasonFor(person.getId()));
    }

    @Test
    void anEmailSuppressionDoesNotExcludeThePersonFromAnSmsSnapshot() {
        Person person = newPersonWith("email.blocked@example.com", "+81 90 8765 4321");
        suppressionService.add(new SuppressionEntryRequest(
                "workspace", "email", "email.blocked@example.com", person.getId(), "unsubscribe", null));

        AudienceEligibilityService.AudienceClassification result = audienceEligibilityService.classify(
                workspace.getId(), List.of(person.getId()), "sms", PURPOSE);

        assertTrue(result.suppressed().isEmpty());
        assertEquals(List.of(person.getId()), result.includedIds());
        assertNull(result.reasonFor(person.getId()));
    }

    @Test
    void anSmsSuppressionDoesNotExcludeThePersonFromAnEmailSnapshot() {
        Person person = newPersonWith("still.mailable@example.com", "+81 90 5555 4321");
        suppressionService.add(new SuppressionEntryRequest(
                "workspace", "sms", "+819055554321", person.getId(), "do_not_contact", null));

        AudienceEligibilityService.AudienceClassification result = audienceEligibilityService.classify(
                workspace.getId(), List.of(person.getId()), "email", PURPOSE);

        assertTrue(result.suppressed().isEmpty());
        assertEquals(List.of(person.getId()), result.includedIds());
        assertNull(result.reasonFor(person.getId()));
    }

    @Test
    void emailSnapshotStillExcludesASuppressedEmailAddressRegardlessOfStoredCase() {
        Person person = newPersonWith("Mixed.Case@Example.COM", "+81 90 3333 4321");
        suppressionService.add(new SuppressionEntryRequest(
                "workspace", "email", "  MIXED.CASE@EXAMPLE.COM  ", person.getId(), "unsubscribe", null));

        AudienceEligibilityService.AudienceClassification result = audienceEligibilityService.classify(
                workspace.getId(), List.of(person.getId()), "email", PURPOSE);

        assertEquals(Set.of(person.getId()), result.suppressed());
        assertTrue(result.includedIds().isEmpty());
        assertEquals("suppressed", result.reasonFor(person.getId()));
    }

    @Test
    void aPersonWithNoPhoneIsNotSuppressionMatchedOnTheSmsChannel() {
        Person person = newPersonWith("no.phone@example.com", null);
        suppressionService.add(new SuppressionEntryRequest(
                "workspace", "email", "no.phone@example.com", person.getId(), "unsubscribe", null));

        AudienceEligibilityService.AudienceClassification result = audienceEligibilityService.classify(
                workspace.getId(), List.of(person.getId()), "sms", PURPOSE);

        assertTrue(result.suppressed().isEmpty());
        assertEquals(List.of(person.getId()), result.includedIds());
    }

    private Person newPersonWith(String email, String phone) {
        Company company = newCompany();
        Person person = new Person();
        person.setName("Person " + unique());
        person.setEmail(email);
        person.setPhone(phone);
        person.setTitle("Engineer");
        person.setCompany(company);
        person.setWorkspaceId(workspace.getId());
        personMapper.insert(person);
        return person;
    }
}
