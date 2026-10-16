package ooo.klae.connex.backend.publicapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.web.method.annotation.ExceptionHandlerMethodResolver;

import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.PrivilegedMfaAttestationRequiredException;
import ooo.klae.connex.backend.exceptions.PrivilegedMfaEnrollmentRequiredException;
import ooo.klae.connex.backend.observability.CorrelationIds;

class PublicApiErrorAdviceTest {
    @BeforeEach
    void bindRequestCorrelationId() {
        MDC.put(CorrelationIds.MDC_KEY, "locked-mfa-request-123");
    }

    @AfterEach
    void clearRequestCorrelationId() {
        MDC.remove(CorrelationIds.MDC_KEY);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void lockedConfinementKeepsTheFiltersUnauthorizedEnvelope(boolean attestation) throws Exception {
        ForbiddenException refusal = attestation ? new PrivilegedMfaAttestationRequiredException()
            : new PrivilegedMfaEnrollmentRequiredException();
        var handler = new ExceptionHandlerMethodResolver(PublicApiErrorAdvice.class).resolveMethodByThrowable(refusal);
        assertNotNull(handler);
        assertEquals("privilegedMfaRequired", handler.getName());
        var response = new PublicApiErrorAdvice().privilegedMfaRequired(refusal);
        assertEquals(401, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals(refusal.getCode().toLowerCase(java.util.Locale.ROOT), response.getBody().error().code());
        assertEquals(refusal.getMessage(), response.getBody().error().message());
        assertEquals(CorrelationIds.current(), response.getBody().error().requestId());
    }
}
