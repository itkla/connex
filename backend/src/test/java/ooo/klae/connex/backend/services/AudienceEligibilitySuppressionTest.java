package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.dto.CampaignAudienceRequest;
import ooo.klae.connex.backend.dto.CampaignAudienceSnapshotDto;
import ooo.klae.connex.backend.dto.CampaignDto;
import ooo.klae.connex.backend.dto.CampaignRequest;
import ooo.klae.connex.backend.dto.SegmentCondition;
import ooo.klae.connex.backend.dto.SegmentDefinition;
import ooo.klae.connex.backend.dto.SuppressionEntryRequest;

/**
 * Proves the audience snapshot matches suppressions on the address the channel actually contacts,
 * against a real database, so the snapshot classification cannot drift from the dispatch re-check.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AudienceEligibilitySuppressionTest extends CampaignRealDbTestSupport {

    private static final String PURPOSE = "marketing";

    @Autowired private AudienceEligibilityService audienceEligibilityService;
    @Autowired private CampaignService campaignService;
    @Autowired private SuppressionService suppressionService;

    @Test
    void smsSnapshotExcludesAPersonWhoseSmsSuppressionIsStoredInADifferentPhoneFormat() {
        Person person = newPersonWith("sms.blocked@example.com", "+81 (90) 1234-5678");
        suppressionService.add(new SuppressionEntryRequest(
                "workspace", "sms", "+819012345678", person.getId(), "do_not_contact", null));

        CampaignAudienceSnapshotDto snapshot = snapshotFor(person, "sms");

        assertEquals(0, snapshot.estimatedIncluded());
        assertEquals(1, snapshot.excludedSuppressed());
        assertEquals("suppressed", snapshot.members().getFirst().exclusionReason());
    }

    @Test
    void anEmailSuppressionDoesNotExcludeThePersonFromAnSmsSnapshot() {
        Person person = newPersonWith("email.blocked@example.com", "+81 90 8765 4321");
        suppressionService.add(new SuppressionEntryRequest(
                "workspace", "email", "email.blocked@example.com", person.getId(), "unsubscribe", null));

        CampaignAudienceSnapshotDto snapshot = snapshotFor(person, "sms");

        assertEquals("sms", snapshot.channel());
        assertEquals(0, snapshot.excludedSuppressed());
        assertEquals(1, snapshot.estimatedIncluded());
        assertNull(snapshot.members().getFirst().exclusionReason());
    }

    @Test
    void anSmsSuppressionDoesNotExcludeThePersonFromAnEmailSnapshot() {
        Person person = newPersonWith("still.mailable@example.com", "+81 90 5555 4321");
        suppressionService.add(new SuppressionEntryRequest(
                "workspace", "sms", "+819055554321", person.getId(), "do_not_contact", null));

        CampaignAudienceSnapshotDto snapshot = snapshotFor(person, "email");

        assertEquals("email", snapshot.channel());
        assertEquals(0, snapshot.excludedSuppressed());
        assertEquals(1, snapshot.estimatedIncluded());
        assertNull(snapshot.members().getFirst().exclusionReason());
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
    void smsSnapshotStillExcludesAPersonWhosePhoneWasReformattedAfterTheyUnsubscribed() {
        Person person = newPersonWith("reformat.blocked@example.com", "+819012345678");
        suppressionService.add(new SuppressionEntryRequest(
                "workspace", "sms", "+819012345678", person.getId(), "unsubscribe", null));
        person.setPhone("09012345678");
        personMapper.update(person);

        AudienceEligibilityService.AudienceClassification result = audienceEligibilityService.classify(
                workspace.getId(), List.of(person.getId()), "sms", PURPOSE);

        assertEquals(Set.of(person.getId()), result.suppressed());
        assertTrue(result.includedIds().isEmpty());
        assertEquals("suppressed", result.reasonFor(person.getId()));
    }

    @Test
    void aManualAddressOnlySmsSuppressionStillExcludesByAddressWithoutAPersonLink() {
        Person person = newPersonWith("manual.addr@example.com", "+819044443333");
        suppressionService.add(new SuppressionEntryRequest(
                "workspace", "sms", "+81 90 4444 3333", null, "manual", null));

        AudienceEligibilityService.AudienceClassification result = audienceEligibilityService.classify(
                workspace.getId(), List.of(person.getId()), "sms", PURPOSE);

        assertEquals(Set.of(person.getId()), result.suppressed());
        assertTrue(result.includedIds().isEmpty());
        assertEquals("suppressed", result.reasonFor(person.getId()));
    }

    @Test
    void anEmailPersonRefSuppressionDoesNotExcludeThePersonFromAnSmsSnapshot() {
        Person person = newPersonWith("emailref.blocked@example.com", "+819022221111");
        suppressionService.add(new SuppressionEntryRequest(
                "workspace", "email", "emailref.blocked@example.com", person.getId(), "unsubscribe", null));

        AudienceEligibilityService.AudienceClassification result = audienceEligibilityService.classify(
                workspace.getId(), List.of(person.getId()), "sms", PURPOSE);

        assertTrue(result.suppressed().isEmpty());
        assertEquals(List.of(person.getId()), result.includedIds());
        assertNull(result.reasonFor(person.getId()));
    }

    @Test
    void aPersonWithNoPhoneIsNotSuppressionMatchedOnTheSmsChannel() {
        Person person = newPersonWith("no.phone@example.com", null);
        suppressionService.add(new SuppressionEntryRequest(
                "workspace", "email", "no.phone@example.com", person.getId(), "unsubscribe", null));

        AudienceEligibilityService.AudienceClassification result = audienceEligibilityService.classify(
                workspace.getId(), List.of(person.getId()), "sms", PURPOSE);

        assertTrue(result.suppressed().isEmpty());
        assertEquals(Set.of(person.getId()), result.noAddress());
        assertTrue(result.includedIds().isEmpty());
        assertEquals("no_address", result.reasonFor(person.getId()));
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

    private CampaignAudienceSnapshotDto snapshotFor(Person person, String channel) {
        CampaignDto campaign = campaignService.create(new CampaignRequest(
                "Campaign " + unique(), null, "email", null, currentUser.getId(),
                null, null, null, null, null));
        SegmentCondition condition = new SegmentCondition();
        condition.setType("field");
        condition.setField("name");
        condition.setOp("starts_with");
        condition.setValue(person.getName());
        SegmentDefinition definition = new SegmentDefinition();
        definition.setMatch("all");
        definition.setConditions(List.of(condition));
        campaignService.setAudience(campaign.id(), new CampaignAudienceRequest(
                "person", definition, channel, PURPOSE));
        return campaignService.snapshotAudience(campaign.id());
    }
}
