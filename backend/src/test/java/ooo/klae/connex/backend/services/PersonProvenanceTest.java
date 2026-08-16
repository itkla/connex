package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import ooo.klae.connex.backend.ai.AiRestrictionEpoch;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.PersonLeadSource;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.FacetCount;
import ooo.klae.connex.backend.dto.MemberScope;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.ShareMapper;
import ooo.klae.connex.backend.notifications.NotificationChangePublisher;

/**
 * Record-level lead-source provenance (#559 increment 3): validation of the source/detail/referrer
 * pairings, creation-path defaults, owner-workspace confinement, and the browser filter and facet.
 */
class PersonProvenanceTest extends AbstractServiceTest {

    @Autowired PersonService personService;
    @Autowired ShareMapper shareMapper;
    @MockitoBean AiRestrictionEpoch aiRestrictionEpoch;
    @MockitoBean RuleTriggerPublisher ruleTriggers;
    @MockitoBean NotificationChangePublisher notificationChanges;

    @Test
    void provenanceIsCapturedAtCreationAndReplacedWithAudit() {
        Person referrer = newPerson(newCompany());
        Person person = new Person();
        person.setName("Prospect " + unique());
        person.setLeadSource(PersonLeadSource.REFERRAL);
        person.setLeadSourceDetail("Introduced at the Osaka seminar");
        person.setReferrerPersonId(referrer.getId());

        Person created = personService.create(person);
        assertEquals(PersonLeadSource.REFERRAL, created.getLeadSource());
        assertEquals(referrer.getId(), created.getReferrerPersonId());

        Person corrected = personService.updateProvenance(
            created.getId(), PersonLeadSource.EVENT, "Osaka seminar", null);
        assertEquals(PersonLeadSource.EVENT, corrected.getLeadSource());
        assertNull(corrected.getReferrerPersonId());

        Person cleared = personService.updateProvenance(created.getId(), null, null, null);
        assertNull(cleared.getLeadSource());
        assertNull(cleared.getLeadSourceDetail());
    }

    @Test
    void invalidPairingsAreRejected() {
        Person person = newPerson(newCompany());
        Person other = newPerson(newCompany());

        assertThrows(BadRequestException.class, () -> personService.updateProvenance(
            person.getId(), null, "detail without a source", null));
        assertThrows(BadRequestException.class, () -> personService.updateProvenance(
            person.getId(), PersonLeadSource.WEB, null, other.getId()));
        assertThrows(BadRequestException.class, () -> personService.updateProvenance(
            person.getId(), PersonLeadSource.REFERRAL, null, person.getId()));
        assertThrows(BadRequestException.class, () -> personService.updateProvenance(
            person.getId(), PersonLeadSource.REFERRAL, null, 999_999));
    }

    @Test
    void aReferrerMustBeOwnedNotMerelySharedIn() {
        Person person = newPerson(newCompany());

        Workspace sibling = new Workspace();
        sibling.setName("Sibling " + unique());
        sibling.setSlug("sibling-" + unique());
        sibling.setOrgId(workspaceMapper.getOrgId(workspace.getId()));
        workspaceMapper.insert(sibling);
        User siblingOwner = newUser();
        workspaceMapper.addMember(sibling.getId(), siblingOwner.getId(), "owner");

        authenticateAs(siblingOwner, sibling.getId());
        Person foreign = new Person();
        foreign.setName("Foreign " + unique());
        foreign.setWorkspaceId(sibling.getId());
        personMapper.insert(foreign);
        shareMapper.sharePerson(
            foreign.getId(), sibling.getId(), workspace.getId(), siblingOwner.getId(), false);

        authenticateAs(currentUser, workspace.getId());
        assertThrows(BadRequestException.class, () -> personService.updateProvenance(
            person.getId(), PersonLeadSource.REFERRAL, null, foreign.getId()));
    }

    @Test
    void provenanceStaysInsideTheOwningWorkspace() {
        Person person = newPerson(newCompany());
        personService.updateProvenance(
            person.getId(), PersonLeadSource.PARTNER, "Channel partner intro", null);

        Workspace grantee = new Workspace();
        grantee.setName("Grantee " + unique());
        grantee.setSlug("grantee-" + unique());
        grantee.setOrgId(workspaceMapper.getOrgId(workspace.getId()));
        workspaceMapper.insert(grantee);
        shareMapper.sharePerson(
            person.getId(), workspace.getId(), grantee.getId(), currentUser.getId(), false);
        User outsider = newUser();
        workspaceMapper.addMember(grantee.getId(), outsider.getId(), "owner");
        authenticateAs(outsider, grantee.getId());

        Person shared = personMapper.getPersonById(grantee.getId(), person.getId());
        assertNotNull(shared, "the share itself must still be visible");
        assertNull(shared.getLeadSource());
        assertNull(shared.getLeadSourceDetail());
        assertNull(shared.getReferrerPersonId());
        assertThrows(ResourceNotFoundException.class, () -> personService.updateProvenance(
            person.getId(), PersonLeadSource.WEB, null, null));

        assertEquals(0L, personService.countPersons(null, null, null, false,
            MemberScope.allTeam(), null, false, List.of(PersonLeadSource.PARTNER), false, false));
    }

    @Test
    void theBrowserFilterAndFacetSeparateSourcedContactsFromTheRest() {
        Person sourced = newPerson(newCompany());
        personService.updateProvenance(sourced.getId(), PersonLeadSource.EVENT, null, null);
        Person unsourced = newPerson(newCompany());

        List<Integer> fromEvents = personService.getPersonsPage(null, null, null, null, null, false,
                MemberScope.allTeam(), null, false, List.of(PersonLeadSource.EVENT), false, false, 100, 0)
            .stream().map(Person::getId).toList();
        assertEquals(List.of(sourced.getId()), fromEvents);

        List<Integer> unknownOrigin = personService.getPersonsPage(null, null, null, null, null, false,
                MemberScope.allTeam(), null, false, null, true, false, 100, 0)
            .stream().map(Person::getId).toList();
        assertEquals(List.of(unsourced.getId()), unknownOrigin);

        Map<String, Long> facets = personService.countsByLeadSource().stream()
            .collect(java.util.stream.Collectors.toMap(FacetCount::getKey, FacetCount::getCount));
        assertEquals(1L, facets.get(PersonLeadSource.EVENT.name()));
        assertEquals(1L, facets.get("__none__"));
    }
}
