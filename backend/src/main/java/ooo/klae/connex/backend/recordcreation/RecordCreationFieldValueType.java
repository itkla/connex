package ooo.klae.connex.backend.recordcreation;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum RecordCreationFieldValueType {
    text,
    textarea,
    email,
    phone,
    url,
    decimal,
    date,
    @JsonProperty("boolean")
    BOOLEAN,
    single_select,
    multi_select,
    person_reference,
    company_reference,
    pipeline_reference,
    stage_reference,
    user_reference,
    tag_references,
    consent_disclosure
}
