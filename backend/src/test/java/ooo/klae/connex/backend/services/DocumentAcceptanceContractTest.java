package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import ooo.klae.connex.backend.config.ValidationConfig;
import ooo.klae.connex.backend.dto.AcceptDocumentRequest;
import ooo.klae.connex.backend.dto.DeclineDocumentRequest;
import ooo.klae.connex.backend.services.DocumentAcceptanceService.GrantedLink;

class DocumentAcceptanceContractTest {
    private static final LocalValidatorFactoryBean validator =
        createValidator();

    private static LocalValidatorFactoryBean createValidator() {
        var config = new ValidationConfig();
        var validator = config.defaultValidator(config.validationMessageSource());
        validator.afterPropertiesSet();
        return validator;
    }

    @AfterAll
    static void closeValidator() {
        validator.close();
    }

    @Test
    void declineReasonValidationMatchesThePersistedTerminationWidth() {
        assertTrue(validator.validate(
            new DeclineDocumentRequest("f".repeat(64), "a".repeat(500))).isEmpty());
        assertFalse(validator.validate(
            new DeclineDocumentRequest("f".repeat(64), "a".repeat(501))).isEmpty());
    }

    @Test
    void decisionBodiesRequireAWellFormedFlowIdentity() {
        assertTrue(validator.validate(new AcceptDocumentRequest("f".repeat(64), "Signer")).isEmpty());
        assertFalse(validator.validate(new AcceptDocumentRequest("", "Signer")).isEmpty());
        assertFalse(validator.validate(new AcceptDocumentRequest("F".repeat(64), "Signer")).isEmpty());
        assertFalse(validator.validate(new AcceptDocumentRequest("f".repeat(63), "Signer")).isEmpty());
        assertFalse(validator.validate(new DeclineDocumentRequest("", "Reason")).isEmpty());
    }

    @Test
    void publicEntryPointsAreNotTransactional() throws Exception {
        List<Method> entries = List.of(
            DocumentAcceptanceService.class.getMethod("exchange", String.class, String.class),
            DocumentAcceptanceService.class.getMethod(
                "admitGrant", HttpServletRequest.class, String.class),
            DocumentAcceptanceService.class.getMethod(
                "preview", GrantedLink.class, String.class),
            DocumentAcceptanceService.class.getMethod(
                "markViewed", GrantedLink.class, String.class),
            DocumentAcceptanceService.class.getMethod(
                "accept", GrantedLink.class, AcceptDocumentRequest.class,
                String.class, String.class),
            DocumentAcceptanceService.class.getMethod(
                "decline", GrantedLink.class, DeclineDocumentRequest.class,
                String.class, String.class));

        for (Method method : entries) {
            assertFalse(method.isAnnotationPresent(Transactional.class), method.getName());
        }
        assertFalse(DocumentAcceptanceService.class.isAnnotationPresent(Transactional.class));
    }
}
