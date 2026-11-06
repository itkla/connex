package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.ContactChannelConsentDto;
import ooo.klae.connex.backend.dto.ContactChannelConsentRequest;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.StaleRecordException;

class ConsentServiceTest extends AbstractServiceTest {
    @Autowired private ConsentService consentService;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void setUpsertsCurrentStateAndAppendsEveryEvent() {
        Person person = newPerson(newCompany());
        ContactChannelConsentDto granted = consentService.setForPerson(
                person.getId(), request("granted", "initial"));
        ContactChannelConsentDto revoked = consentService.setForPerson(
                person.getId(), request("revoked", "preference-center"));

        assertEquals(granted.id(), revoked.id());
        assertEquals("revoked", consentService.getForPerson(person.getId()).getFirst().status());
        Integer events = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM contact_channel_consent_event WHERE workspace_id = ? AND consent_id = ?",
                Integer.class, workspace.getId(), granted.id());
        assertEquals(2, events);
    }

    @Test
    void consentRequiresConsentManagePermission() {
        Person person = newPerson(newCompany());
        User member = newUser();
        authenticateAs(member, workspace.getId());

        assertThrows(ForbiddenException.class, () -> consentService.getForPerson(person.getId()));
        assertThrows(ForbiddenException.class,
                () -> consentService.setForPerson(person.getId(), request("granted", "manual")));
        assertThrows(ForbiddenException.class,
                () -> consentService.setForPersonConditionally(person.getId(), conditional("granted", 0)));
    }

    @Test
    void aStaleAbsentSnapshotCannotReplaceAnotherWritersRevocationAndEmitsNoHistory() {
        Person person = newPerson(newCompany());
        ContactChannelConsentDto revoked = consentService.setForPerson(person.getId(), request("revoked", "unsubscribe"));
        StaleRecordException conflict = assertThrows(StaleRecordException.class, () ->
                consentService.setForPersonConditionally(person.getId(), conditional("granted", 0)));
        assertEquals(revoked.version(), conflict.getCurrentVersion());
        assertEquals("revoked", consentService.getForPerson(person.getId()).getFirst().status());
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM contact_channel_consent_event WHERE workspace_id = ? AND person_id = ?",
                Integer.class, workspace.getId(), person.getId()));
    }

    @Test
    void everyTrustedRevocationAdvancesTheTokenEvenWhenTheTimestampAndStatusMatch() {
        Person person = newPerson(newCompany());
        ContactChannelConsentDto first = consentService.setForPerson(person.getId(), request("revoked", "unsubscribe"));
        ContactChannelConsentDto second = consentService.setForPerson(person.getId(), request("revoked", "unsubscribe"));
        assertEquals(first.version() + 1, second.version());
        assertThrows(StaleRecordException.class, () -> consentService.setForPersonConditionally(
                person.getId(), conditional("granted", first.version())));
        ContactChannelConsentDto renewed = consentService.setForPersonConditionally(
                person.getId(), conditional("granted", second.version()));
        assertEquals(second.version() + 1, renewed.version());
    }

    @Test
    void manualWritesRequireAnExplicitExpectedVersionIncludingExplicitAbsence() {
        Person person = newPerson(newCompany());
        assertThrows(BadRequestException.class, () -> consentService.setForPersonConditionally(
                person.getId(), request("granted", "manual")));
        ContactChannelConsentDto created = consentService.setForPersonConditionally(person.getId(), conditional("unknown", 0));
        assertEquals(1, created.version());
    }

    private ContactChannelConsentRequest conditional(String status, long expectedVersion) {
        return new ContactChannelConsentRequest("email", "marketing", status, "manual", null, null, expectedVersion);
    }

    private ContactChannelConsentRequest request(String status, String source) {
        return new ContactChannelConsentRequest(
                "email", "marketing", status, source, "evidence", null);
    }
}
