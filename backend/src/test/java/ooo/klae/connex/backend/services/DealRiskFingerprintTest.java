package ooo.klae.connex.backend.services;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.beans.Deal;

class DealRiskFingerprintTest {

    @Test
    void notificationFingerprintMatchesThePersistedLegacyDoubleFixture() throws Exception {
        Deal deal = new Deal();
        deal.setId(19);
        deal.setValue(new BigDecimal("125000.00"));
        deal.setCurrency("USD");
        deal.setExpectedCloseDate("2026-08-31");
        deal.setCreatedAt("2026-08-01 01:02:03");
        deal.setUpdatedAt("2026-08-02 04:05:06");
        Method method = DealRiskService.class.getDeclaredMethod(
            "notificationSourceStateHash",
            Deal.class,
            String.class,
            List.class,
            Map.class,
            Map.class);
        method.setAccessible(true);

        String hash = (String) method.invoke(
            null, deal, "touch-hash", List.of(), Map.of(), Map.of());

        assertThat(hash).isEqualTo(
            "0d2aef6d95e2517852e272d91d5442f2572a7fff9dcbd8a4cecd4c7c5c3d9e64");
    }
}
