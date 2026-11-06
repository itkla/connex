package ooo.klae.connex.backend.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Person;

class PersonReferenceDtoTest {
    @Test
    void serializesOnlyContactIdentityAndVisibleEmployerId() {
        Person person = new Person();
        person.setId(7);
        person.setName("Visible contact");
        person.setEmail("private@example.test");
        person.setPhone("private phone");
        person.setQualificationNotes("Private qualification");
        person.setLeadSourceDetail("Private source");
        person.setReferrerPersonId(9);
        Company company = new Company();
        company.setId(11);
        company.setName("Employer");
        person.setCompany(company);

        var mapper = new ObjectMapper();
        var json = mapper.readTree(mapper.writeValueAsString(PersonReferenceDto.from(person)));

        assertEquals(3, json.size());
        assertEquals(7, json.path("id").asInt());
        assertEquals("Visible contact", json.path("name").asText());
        assertEquals(11, json.path("companyId").asInt());
        for (String field : java.util.List.of("email", "phone", "company", "qualificationNotes", "leadSourceDetail", "referrerPersonId")) {
            assertFalse(json.has(field), field);
        }
    }

    @Test
    void doesNotReconstructAnEmployerMaskedByTheMapper() {
        Person person = new Person();
        person.setId(7);
        person.setName("Shared contact");

        assertNull(PersonReferenceDto.from(person).companyId());
    }
}
