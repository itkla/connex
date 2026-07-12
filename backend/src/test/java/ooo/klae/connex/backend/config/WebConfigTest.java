package ooo.klae.connex.backend.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.LocaleResolver;

import ooo.klae.connex.backend.tenant.TenantResolutionInterceptor;

class WebConfigTest {

    private final LocaleResolver localeResolver = new WebConfig(mock(TenantResolutionInterceptor.class))
            .localeResolver();

    @Test
    void localeResolver_defaultsToEnglishWithoutHeader() {
        assertEquals(Locale.ENGLISH, localeResolver.resolveLocale(new MockHttpServletRequest()));
    }

    @Test
    void localeResolver_defaultsToEnglishForUnsupportedLanguage() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Accept-Language", "fr-FR,fr;q=0.9");

        assertEquals(Locale.ENGLISH, localeResolver.resolveLocale(request));
    }

    @Test
    void localeResolver_acceptsJapanese() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Accept-Language", "ja");

        assertEquals(Locale.JAPANESE, localeResolver.resolveLocale(request));
    }
}
