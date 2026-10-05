package ooo.klae.connex.backend.dto;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.beans.PersonDisqualificationReason;
import ooo.klae.connex.backend.beans.PersonLifecycleStage;

class PersonLifecycleRequestValidationTest {
    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = FACTORY.getValidator();

    @AfterAll
    static void closeValidator() {
        FACTORY.close();
    }

    @Test
    void lifecycleReasonDtoAcceptsOnlyCanonicalUppercaseAscii() {
        PersonLifecycleRequest canonical = request(
            PersonLifecycleStage.DISQUALIFIED, PersonDisqualificationReason.OTHER, null);
        assertTrue(VALIDATOR.validate(canonical).isEmpty());

        for (String code : List.of("other", " OTHER ", "ÖTHER")) {
            PersonLifecycleRequest invalid = request(
                PersonLifecycleStage.DISQUALIFIED, code, null);
            assertTrue(VALIDATOR.validate(invalid).stream()
                .anyMatch(violation -> violation.getPropertyPath().toString().equals("reason")));
        }
    }

    private static PersonLifecycleRequest request(
            PersonLifecycleStage stage, String reason, String note) {
        PersonLifecycleRequest request = new PersonLifecycleRequest();
        request.setStage(stage);
        request.setReason(reason);
        request.setNote(note);
        return request;
    }
}
