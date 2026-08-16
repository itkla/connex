package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;

import ooo.klae.connex.backend.beans.PersonLeadSource;

/**
 * A requested replacement of a contact's source provenance (#559). All fields are optional: a
 * request with every field null clears the provenance, recording that the origin is unknown.
 */
@Data
@NoArgsConstructor
public class PersonProvenanceRequest {
    private PersonLeadSource leadSource;

    @Size(max = 255)
    private String leadSourceDetail;

    private Integer referrerPersonId;
}
