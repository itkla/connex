package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

class IdentityBackfillTransactionTest {
    @Test
    void everyBackfillMutationBoundaryIsTransactional() throws Exception {
        Method person = IdentityBackfillTransaction.class.getMethod(
            "backfillPersonPage", String.class, int.class, int.class, int.class);
        Method company = IdentityBackfillTransaction.class.getMethod(
            "backfillCompanyPage", String.class, int.class, int.class, int.class);
        Method rebuild = IdentityBackfillTransaction.class.getMethod(
            "rebuildCollisionReport", String.class, int.class);

        assertNotNull(person.getAnnotation(Transactional.class));
        assertNotNull(company.getAnnotation(Transactional.class));
        assertNotNull(rebuild.getAnnotation(Transactional.class));
    }
}
