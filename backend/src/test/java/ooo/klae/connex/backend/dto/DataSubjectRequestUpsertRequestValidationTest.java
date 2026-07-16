package ooo.klae.connex.backend.dto;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

class DataSubjectRequestUpsertRequestValidationTest {
    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = FACTORY.getValidator();

    @AfterAll
    static void closeValidatorFactory() {
        FACTORY.close();
    }

    @Test
    void acceptsSupportedOrOmittedMysqlDatetimeYears() {
        assertTrue(violations(null, null, null).isEmpty());
        assertTrue(violations(LocalDateTime.of(1000, 1, 1, 0, 0), null, null).isEmpty());
        assertTrue(violations(LocalDateTime.of(9999, 12, 31, 23, 59), null, null).isEmpty());
    }

    @Test
    void rejectsDatetimeYearsOutsideMysqlRange() {
        assertFalse(violations(LocalDateTime.of(999, 12, 31, 23, 59), null, null).isEmpty());
        assertFalse(violations(LocalDateTime.of(10000, 1, 1, 0, 0), null, null).isEmpty());
    }

    @Test
    void rejectsFractionalSecondsThatRoundPastYear9999() {
        assertFalse(violations(
            LocalDateTime.of(9999, 12, 31, 23, 59, 59, 900_000_000), null, null).isEmpty());
        assertTrue(violations(
            LocalDateTime.of(9999, 12, 31, 23, 59, 59, 400_000_000), null, null).isEmpty());
    }

    @Test
    void validatesEveryTimestampField() {
        List<BiConsumer<DataSubjectRequestUpsertRequest, LocalDateTime>> setters = List.of(
            DataSubjectRequestUpsertRequest::setReceivedAt,
            DataSubjectRequestUpsertRequest::setIdentityVerifiedAt,
            DataSubjectRequestUpsertRequest::setDueAt,
            DataSubjectRequestUpsertRequest::setRespondedAt,
            DataSubjectRequestUpsertRequest::setClosedAt);
        for (BiConsumer<DataSubjectRequestUpsertRequest, LocalDateTime> setter : setters) {
            DataSubjectRequestUpsertRequest request = validRequest();
            setter.accept(request, LocalDateTime.of(999, 1, 1, 0, 0));
            assertFalse(VALIDATOR.validate(request).isEmpty());
        }
    }

    @Test
    void requiresBothSubjectLinkIdentifiersOrNeither() {
        assertFalse(violations(null, 3, null).isEmpty());
        assertFalse(violations(null, null, 9).isEmpty());
        assertTrue(violations(null, 3, 9).isEmpty());
    }

    @Test
    void validatesRequiredNamesAndEmail() {
        DataSubjectRequestUpsertRequest request = validRequest();
        request.setRequesterName(" ");
        request.setSubjectName("");
        request.setSubjectEmail("not-an-email");

        assertFalse(VALIDATOR.validate(request).isEmpty());
    }

    private static Set<ConstraintViolation<DataSubjectRequestUpsertRequest>> violations(
            LocalDateTime timestamp, Integer workspaceId, Integer personId) {
        DataSubjectRequestUpsertRequest request = validRequest();
        request.setReceivedAt(timestamp);
        request.setSubjectWorkspaceId(workspaceId);
        request.setSubjectPersonId(personId);
        return VALIDATOR.validate(request);
    }

    private static DataSubjectRequestUpsertRequest validRequest() {
        DataSubjectRequestUpsertRequest request = new DataSubjectRequestUpsertRequest();
        request.setRequestType("disclosure");
        request.setRequesterName("Requester");
        request.setSubjectName("Subject");
        return request;
    }
}
