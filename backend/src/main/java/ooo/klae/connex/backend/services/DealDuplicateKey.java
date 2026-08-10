package ooo.klae.connex.backend.services;

import java.util.Objects;

final class DealDuplicateKey {

    private DealDuplicateKey() {
    }

    static String of(String normalizedName, Integer companyId) {
        return Objects.requireNonNull(normalizedName, "normalized deal name")
            + " "
            + (companyId == null ? "" : companyId);
    }
}
