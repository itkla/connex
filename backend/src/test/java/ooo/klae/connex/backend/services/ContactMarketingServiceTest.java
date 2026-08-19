package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.ContactChannelConsent;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.SuppressionEntry;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.ContactChannelMarketingStatusDto;
import ooo.klae.connex.backend.dto.ContactMarketingStatusDto;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.ConsentMapper;
import ooo.klae.connex.backend.mappers.SuppressionMapper;

/**
 * Contact contactability: marketing opt-out and privacy hold must stay distinguishable, and the
 * dual-arm suppression match the send path uses must be reproduced.
 */
class ContactMarketingServiceTest extends AbstractServiceTest {

    @Autowired private ContactMarketingService contactMarketingService;
    @Autowired private SuppressionMapper suppressionMapper;
    @Autowired private ConsentMapper consentMapper;

    @Test
    void aContactWithNoExclusionIsContactableOnEveryChannel() {
        Person person = newPerson(newCompany());

        ContactMarketingStatusDto status = contactMarketingService.getStatus(person.getId());

        assertFalse(status.privacyHold());
        assertEquals(4, status.channels().size());
        assertTrue(status.channels().stream().allMatch(channel -> channel.state() == null));
        assertTrue(channel(status, "email").addressable());
        assertFalse(channel(status, "line").addressable());
    }

    @Test
    void anUnsubscribeIsReportedAsOptedOutOnItsOwnChannelOnly() {
        Person person = newPerson(newCompany());
        suppress(person, "email", person.getEmail(), "unsubscribe");

        ContactMarketingStatusDto status = contactMarketingService.getStatus(person.getId());

        assertEquals("opted_out", channel(status, "email").state());
        assertTrue(channel(status, "email").optedOut());
        assertFalse(channel(status, "email").doNotContact());
        assertNotNull(channel(status, "email").since());
        assertNull(channel(status, "sms").state());
    }

    @Test
    void anExplicitDoNotContactOutranksAnOptOut() {
        Person person = newPerson(newCompany());
        suppress(person, "email", person.getEmail(), "unsubscribe");
        suppress(person, "email", person.getEmail(), "do_not_contact");

        ContactChannelMarketingStatusDto email =
                channel(contactMarketingService.getStatus(person.getId()), "email");

        assertEquals("do_not_contact", email.state());
        assertTrue(email.doNotContact());
        assertTrue(email.optedOut());
    }

    @Test
    void anAddressOnlySuppressionWithNoContactLinkStillMatches() {
        Person person = newPerson(newCompany());
        SuppressionEntry entry = new SuppressionEntry();
        entry.setWorkspaceId(workspace.getId());
        entry.setScope("workspace");
        entry.setChannel("email");
        entry.setAddress(person.getEmail());
        entry.setPersonId(null);
        entry.setReason("hard_bounce");
        suppressionMapper.insert(entry);

        assertEquals("opted_out",
                channel(contactMarketingService.getStatus(person.getId()), "email").state());
    }

    @Test
    void revokedMarketingConsentReadsAsOptedOut() {
        Person person = newPerson(newCompany());
        ContactChannelConsent consent = new ContactChannelConsent();
        consent.setWorkspaceId(workspace.getId());
        consent.setPersonId(person.getId());
        consent.setChannel("email");
        consent.setPurpose("marketing");
        consent.setStatus("revoked");
        consent.setSource("manual");
        consentMapper.upsert(consent);

        ContactChannelMarketingStatusDto email =
                channel(contactMarketingService.getStatus(person.getId()), "email");

        assertTrue(email.consentRevoked());
        assertTrue(email.optedOut());
        assertEquals("opted_out", email.state());
    }

    /** A privacy hold is an APPI restriction and must never be reported as a marketing opt-out. */
    @Test
    void aPrivacyHoldIsReportedSeparatelyFromMarketingOptOut() {
        Person person = newPerson(newCompany());
        personMapper.updateProcessingRestrictions(
                workspace.getId(), person.getId(), false, true);

        ContactMarketingStatusDto status = contactMarketingService.getStatus(person.getId());

        assertTrue(status.privacyHold());
        assertNotNull(status.provisionCeasedAt());
        assertNull(status.suspendedAt());
        assertTrue(status.channels().stream().allMatch(channel -> channel.state() == null));
    }

    @Test
    void anotherWorkspaceCannotReadTheStatus() {
        Person person = newPerson(newCompany());
        suppress(person, "email", person.getEmail(), "unsubscribe");

        Workspace sibling = newSiblingWorkspace();
        authenticateAs(currentUser, sibling.getId());

        assertThrows(ResourceNotFoundException.class,
                () -> contactMarketingService.getStatus(person.getId()));
    }

    @Test
    void aSuppressionOwnedByAnotherWorkspaceDoesNotLeakIn() {
        Person person = newPerson(newCompany());
        Workspace sibling = newSiblingWorkspace();
        SuppressionEntry foreign = new SuppressionEntry();
        foreign.setWorkspaceId(sibling.getId());
        foreign.setScope("workspace");
        foreign.setChannel("email");
        foreign.setAddress(person.getEmail());
        foreign.setReason("unsubscribe");
        suppressionMapper.insert(foreign);

        assertNull(channel(contactMarketingService.getStatus(person.getId()), "email").state());
    }

    private static ContactChannelMarketingStatusDto channel(
            ContactMarketingStatusDto status, String channel) {
        return status.channels().stream()
                .filter(row -> channel.equals(row.channel()))
                .findFirst()
                .orElseThrow();
    }

    private void suppress(Person person, String channel, String address, String reason) {
        SuppressionEntry entry = new SuppressionEntry();
        entry.setWorkspaceId(workspace.getId());
        entry.setScope("workspace");
        entry.setChannel(channel);
        entry.setAddress(address);
        entry.setPersonId(person.getId());
        entry.setReason(reason);
        suppressionMapper.insert(entry);
    }

    private Workspace newSiblingWorkspace() {
        Workspace sibling = new Workspace();
        sibling.setName("Sibling " + unique());
        sibling.setSlug("sibling-" + unique());
        sibling.setOrgId(workspaceMapper.getOrgId(workspace.getId()));
        workspaceMapper.insert(sibling);
        workspaceMapper.addMember(sibling.getId(), currentUser.getId(), "owner");
        return sibling;
    }
}
