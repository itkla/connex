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
    @ValueSource(strings = {" ", "yen", "JP", "JPYY", "JP1", "USDT", "XXX", "XTS", "XAU", "ＪＰＹ", "ınr"})
    void rejectsMissingOrNonmonetaryCodes(String code) {
        assertFalse(CurrencyCodes.isSupported(code));
        assertThrows(BadRequestException.class, () -> CurrencyCodes.required(code));
    }

    @ParameterizedTest
    @ValueSource(strings = {"X-LOCAL", "US$", "USDT", " jpy ", "usd"})
    void unchangedStoredCurrenciesSurviveNormalizedRoundTrips(String stored) {
        assertEquals(stored, CurrencyCodes.updated(stored.trim().toUpperCase(Locale.ROOT), stored));
        assertTrue(CurrencyCodes.equivalent(stored, stored.trim().toUpperCase(Locale.ROOT)));
        if (!CurrencyCodes.isSupported(stored)) {
            assertThrows(BadRequestException.class, () -> CurrencyCodes.required(stored));
            assertThrows(BadRequestException.class, () -> CurrencyCodes.updated(stored, "JPY"));
        }
    }

    @Test
    void onlyNullMeansAnAbsentOptionalCurrency() {
        assertNull(CurrencyCodes.optional(null));
        assertThrows(BadRequestException.class, () -> CurrencyCodes.optional(" "));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void missingDealCreationCurrencyDefaultsWithoutDefaultingOtherBoundaries(String value) {
        assertEquals("USD", CurrencyCodes.forDealCreation(value));
        assertThrows(BadRequestException.class, () -> CurrencyCodes.required(value));
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            DealDto deal = new DealDto();
            deal.setCurrency(value);
            var validator = factory.getValidator();
            assertTrue(validator.validateProperty(deal, "currency", ValidCurrency.OnCreate.class).isEmpty());
            assertFalse(validator.validateProperty(deal, "currency", ValidCurrency.OnUpdate.class).isEmpty());
            GuidedDealRecordDto guided = new GuidedDealRecordDto("Deal", BigDecimal.TEN, value, 1, 1, null, null, null);
            assertTrue(validator.validateProperty(guided, "currency").isEmpty());
            assertNull(guided.toBean().getCurrency());
        }
    }

    @Test
    void createDtosShareIsoValidationWhileUpdatesRetainTheirSubmittedValue() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            DealDto deal = new DealDto();
            ApprovalPolicyDto policy = new ApprovalPolicyDto();
            for (String code : new String[]{"JPY", " jpy ", "yen", "", "XXX"}) {
                deal.setCurrency(code);
                policy.setCurrency(code);
                GuidedDealRecordDto guided = new GuidedDealRecordDto("Deal", BigDecimal.TEN, code, 1, 1, null, null, null);
                boolean valid = CurrencyCodes.isSupported(code);
                assertEquals(valid || code.isBlank(), validator.validateProperty(deal, "currency", ValidCurrency.OnCreate.class).isEmpty());
                assertEquals(valid, validator.validateProperty(policy, "currency", ValidCurrency.OnCreate.class).isEmpty());
                assertEquals(valid || code.isBlank(), validator.validateProperty(guided, "currency").isEmpty());
                if (valid) {
                    assertEquals(code, deal.toBean().getCurrency());
                    assertEquals(code, policy.toBean().getCurrency());
                    assertEquals("JPY", guided.toBean().getCurrency());
                }
            }
        }
    }
}
