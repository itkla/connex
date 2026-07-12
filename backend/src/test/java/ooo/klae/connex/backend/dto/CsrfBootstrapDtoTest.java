package ooo.klae.connex.backend.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.springframework.security.web.csrf.DefaultCsrfToken;

class CsrfBootstrapDtoTest {

    @Test
    void ofPreservesCsrfContractAndAddsOpaqueRequestIdentity() {
        CsrfBootstrapDto result = CsrfBootstrapDto.of(
                new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "csrf-value"), "opaque-generation");

        assertEquals("csrf-value", result.token());
        assertEquals("X-CSRF-TOKEN", result.headerName());
        assertEquals("_csrf", result.parameterName());
        assertEquals("opaque-generation", result.requestIdentity());
    }

    @Test
    void ofSupportsDisabledCsrfWithoutDroppingRequestIdentity() {
        CsrfBootstrapDto result = CsrfBootstrapDto.of(null, "opaque-generation");

        assertNull(result.token());
        assertNull(result.headerName());
        assertNull(result.parameterName());
        assertEquals("opaque-generation", result.requestIdentity());
    }
}
