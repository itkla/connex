package ooo.klae.connex.backend.password;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;

class BreachedPasswordStartupValidatorTest {
    @Test
    void offlineSourceIsVerifiedAtStartup() {
        BreachedPasswordProperties properties = properties("OFFLINE");
        OfflineBreachedPasswordLookup offline = mock(OfflineBreachedPasswordLookup.class);
        BreachedPasswordStartupValidator validator = new BreachedPasswordStartupValidator(
                properties, offline);

        validator.run(mock(ApplicationArguments.class));

        verify(offline).validate();
    }

    @Test
    void remoteSourceDoesNotAccessOfflineFile() {
        BreachedPasswordProperties properties = properties("REMOTE");
        OfflineBreachedPasswordLookup offline = mock(OfflineBreachedPasswordLookup.class);
        BreachedPasswordStartupValidator validator = new BreachedPasswordStartupValidator(
                properties, offline);

        validator.run(mock(ApplicationArguments.class));

        verify(offline, never()).validate();
    }

    private static BreachedPasswordProperties properties(String source) {
        BreachedPasswordProperties properties = new BreachedPasswordProperties();
        properties.setSource(source);
        return properties;
    }
}
