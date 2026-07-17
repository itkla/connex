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
    }

    private ContactChannelConsentRequest request(String status, String source) {
        return new ContactChannelConsentRequest(
                "email", "marketing", status, source, "evidence", null);
    }
}
