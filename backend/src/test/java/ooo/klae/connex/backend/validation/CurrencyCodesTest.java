package ooo.klae.connex.backend.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import jakarta.validation.Validation;

import ooo.klae.connex.backend.dto.ApprovalPolicyDto;
import ooo.klae.connex.backend.dto.DealDto;
import ooo.klae.connex.backend.dto.recordcreation.GuidedDealRecordDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;

class CurrencyCodesTest {
    @ParameterizedTest
    @ValueSource(strings = {"JPY", "USD", "EUR", "KWD", "CLF", " jpy ", "usd"})
    void supportsAndCanonicalizesMonetaryCodes(String code) {
        assertTrue(CurrencyCodes.isSupported(code));
        assertEquals(code.trim().toUpperCase(Locale.ROOT), CurrencyCodes.required(code));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "yen", "JP", "JPYY", "JP1", "XXX", "XTS", "XAU", "ＪＰＹ", "ınr"})
    void rejectsMissingOrNonmonetaryCodes(String code) {
        assertFalse(CurrencyCodes.isSupported(code));
        assertThrows(BadRequestException.class, () -> CurrencyCodes.required(code));
    }

    @Test
    void onlyNullMeansAnAbsentOptionalCurrency() {
        assertNull(CurrencyCodes.optional(null));
        assertThrows(BadRequestException.class, () -> CurrencyCodes.optional(" "));
    }

    @Test
    void allDealAndPolicyDtosUseTheSameConstraintAndCanonicalization() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            DealDto deal = new DealDto();
            ApprovalPolicyDto policy = new ApprovalPolicyDto();
            for (String code : new String[]{"JPY", " jpy ", "yen", "", "XXX"}) {
                deal.setCurrency(code);
                policy.setCurrency(code);
                GuidedDealRecordDto guided = new GuidedDealRecordDto("Deal", BigDecimal.TEN, code, 1, 1, null, null, null);
                boolean valid = CurrencyCodes.isSupported(code);
                assertEquals(valid, validator.validateProperty(deal, "currency").isEmpty());
                assertEquals(valid, validator.validateProperty(policy, "currency").isEmpty());
                assertEquals(valid, validator.validateProperty(guided, "currency").isEmpty());
                if (valid) {
                    assertEquals("JPY", deal.toBean().getCurrency());
                    assertEquals("JPY", policy.toBean().getCurrency());
                    assertEquals("JPY", guided.toBean().getCurrency());
                }
            }
        }
    }
}
