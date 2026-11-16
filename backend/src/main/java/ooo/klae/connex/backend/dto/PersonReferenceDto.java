package ooo.klae.connex.backend.dto;

import ooo.klae.connex.backend.beans.Person;

/** Minimal visible contact identity for list labels and company-scoped task editing. */
public record PersonReferenceDto(int id, String name, Integer companyId) {
    /** Keeps the employer visibility already applied by the contact mapper. */
    public static PersonReferenceDto from(Person person) {
        return new PersonReferenceDto(person.getId(), person.getName(),
            person.getCompany() == null ? null : person.getCompany().getId());
    }
}
