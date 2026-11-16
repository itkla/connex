package ooo.klae.connex.backend.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import ooo.klae.connex.backend.validation.CurrencyCodes;
import tools.jackson.databind.json.JsonMapper;

class WorkspaceCurrencyRequestTest {
    @Test
    void realRequestBindingAcceptsSupportedValuesAndReportsTheCurrencyField() {
        var mapper = JsonMapper.builder().build();
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            var valid = mapper.readValue("""
                {"defaultCurrency":"EUR","expectedIdentityVersion":0}
                """, UpdateWorkspaceCurrencyRequest.class);
            assertEquals("EUR", valid.getDefaultCurrency());
            assertEquals(0L, valid.getExpectedIdentityVersion());
            assertTrue(validator.validate(valid).isEmpty());
            for (String code : java.util.List.of("KWD", "CLF", " usd ", "kwd")) {
                valid.setDefaultCurrency(code);
                assertTrue(CurrencyCodes.isSupported(code));
                assertTrue(validator.validate(valid).isEmpty());
            }
            for (String invalid : java.util.List.of("YEN", "XXX", "XTS", "XAU", "EURUSD", "", " ")) {
                valid.setDefaultCurrency(invalid);
                assertTrue(validator.validate(valid).stream()
                    .anyMatch(error -> error.getPropertyPath().toString().equals("defaultCurrency")));
            }
            valid.setDefaultCurrency("EUR");
            valid.setExpectedIdentityVersion(null);
            assertTrue(validator.validate(valid).stream()
                .anyMatch(error -> error.getPropertyPath().toString().equals("expectedIdentityVersion")));
        }
    }
}
