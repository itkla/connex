package ooo.klae.connex.backend.dto;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/** Bean-validation boundaries for mutable workspace and organization identity. */
class WorkspaceOrganizationSettingsRequestValidationTest {
    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = FACTORY.getValidator();

    @AfterAll
    static void closeValidator() {
        FACTORY.close();
    }

    @Test
    void workspaceIdentityRequiresBoundedNameAndAllowsNullTimezone() {
        UpdateWorkspaceIdentityRequest request = new UpdateWorkspaceIdentityRequest();
        request.setName("Workspace");
        request.setTimezone(null);
        request.setExpectedName("Workspace");
        request.setExpectedTimezone(null);

        assertTrue(VALIDATOR.validate(request).isEmpty());

        request.setName(null);
        assertFalse(VALIDATOR.validate(request).isEmpty());

        request.setName("   ");
        assertFalse(VALIDATOR.validate(request).isEmpty());

        request.setName("x".repeat(129));
        assertFalse(VALIDATOR.validate(request).isEmpty());
    }

    @Test
    void workspaceTimezoneRejectsOverlongInputAtTheBoundary() {
        UpdateWorkspaceIdentityRequest request = new UpdateWorkspaceIdentityRequest();
        request.setName("Workspace");
        request.setTimezone("x".repeat(65));
        request.setExpectedName("Workspace");

        assertFalse(VALIDATOR.validate(request).isEmpty());
    }

    @Test
    void workspaceIdentityRequiresBoundedExpectedNameAndTimezone() {
        UpdateWorkspaceIdentityRequest request = new UpdateWorkspaceIdentityRequest();
        request.setName("Workspace");
        request.setExpectedName(null);

        assertFalse(VALIDATOR.validate(request).isEmpty());

        request.setExpectedName("x".repeat(129));
        assertFalse(VALIDATOR.validate(request).isEmpty());

        request.setExpectedName("Workspace");
        request.setExpectedTimezone("x".repeat(65));
        assertFalse(VALIDATOR.validate(request).isEmpty());
    }

    @Test
    void organizationRenameRequiresBoundedName() {
        RenameOrganizationRequest request = new RenameOrganizationRequest();
        request.setName("Organization");
        assertTrue(VALIDATOR.validate(request).isEmpty());

        request.setName(null);
        assertFalse(VALIDATOR.validate(request).isEmpty());

        request.setName(" ");
        assertFalse(VALIDATOR.validate(request).isEmpty());

        request.setName("x".repeat(129));
        assertFalse(VALIDATOR.validate(request).isEmpty());
    }
}
