package ooo.klae.connex.backend.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import tools.jackson.databind.ObjectMapper;

class ContactPreferenceRequestValidationTest {
    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    private final ObjectMapper mapper = new ObjectMapper();

    @AfterAll
    static void closeValidator() { FACTORY.close(); }

    @Test
    void conditionalConsentRequiresAnExplicitNonnegativeVersionThroughJackson() {
        String fields = "\"channel\":\"email\",\"purpose\":\"marketing\",\"status\":\"granted\",\"source\":\"manual\"";
        var absent = mapper.readValue("{" + fields + "}", ContactChannelConsentRequest.class);
        assertNull(absent.expectedVersion());
        assertTrue(FACTORY.getValidator().validate(absent).stream()
                .anyMatch(violation -> violation.getPropertyPath().toString().equals("expectedVersion")));
        var initial = mapper.readValue("{" + fields + ",\"expectedVersion\":0}", ContactChannelConsentRequest.class);
        assertEquals(0L, initial.expectedVersion());
        assertTrue(FACTORY.getValidator().validate(initial).isEmpty());
        var negative = mapper.readValue("{" + fields + ",\"expectedVersion\":-1}", ContactChannelConsentRequest.class);
        assertTrue(FACTORY.getValidator().validate(negative).stream()
                .anyMatch(violation -> violation.getPropertyPath().toString().equals("expectedVersion")));
    }

    @Test
    void restrictionRequestsDistinguishUnchangedFlagsFromExplicitRemoval() {
        var patch = mapper.readValue("{\"provisionCeased\":true}", PersonRestrictionsDto.class);
        assertNull(patch.getSuspended());
        assertEquals(Boolean.TRUE, patch.getProvisionCeased());
        assertTrue(FACTORY.getValidator().validate(patch).isEmpty());
        var removal = mapper.readValue("{\"suspended\":false}", PersonRestrictionsDto.class);
        assertEquals(Boolean.FALSE, removal.getSuspended());
        assertNull(removal.getProvisionCeased());
    }
}
