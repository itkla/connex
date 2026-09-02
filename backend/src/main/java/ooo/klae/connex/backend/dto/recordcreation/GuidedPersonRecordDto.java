package ooo.klae.connex.backend.dto.recordcreation;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.PersonLeadSource;

public record GuidedPersonRecordDto(
    @NotBlank @Size(max = 255) String name,
    @Email @Size(max = 255) String email,
    @Size(max = 64) String phone,
    @Positive Integer companyId,
    @Size(max = 128) String title,
    PersonLeadSource leadSource,
    @Size(max = 255) String leadSourceDetail,
    @Positive Integer referrerPersonId,
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    @Pattern(regexp = "^[0-9a-f]{64}$") String duplicateReviewToken
) {
    public Person toBean() {
        Person person = new Person();
        person.setName(name);
        person.setEmail(email);
        person.setPhone(phone);
        if (companyId != null) {
            Company company = new Company();
            company.setId(companyId);
            person.setCompany(company);
        }
        person.setTitle(title);
        person.setLeadSource(leadSource);
        person.setLeadSourceDetail(leadSourceDetail);
        person.setReferrerPersonId(referrerPersonId);
        return person;
    }
}
