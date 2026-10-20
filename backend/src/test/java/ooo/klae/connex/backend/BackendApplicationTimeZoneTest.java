package ooo.klae.connex.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.ZoneId;
import java.util.TimeZone;

import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;

class BackendApplicationTimeZoneTest {

    @Test
    void pinsUtcFromNonUtcDefaultsAndIsIdempotent() {
        TimeZone original = TimeZone.getDefault();
        try {
            for (String hostZone : new String[] {"Asia/Tokyo", "Pacific/Honolulu"}) {
                TimeZone.setDefault(TimeZone.getTimeZone(hostZone));

                BackendApplication.pinDefaultTimeZone();
                assertEquals("UTC", TimeZone.getDefault().getID());
                assertEquals(ZoneId.of("UTC"), ZoneId.systemDefault());

                BackendApplication.pinDefaultTimeZone();
                assertEquals("UTC", TimeZone.getDefault().getID());
                assertEquals(ZoneId.of("UTC"), ZoneId.systemDefault());
            }
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test
    void warConfigurationPinsUtcBeforeConfiguringSpring() {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"));

            new ServletInitializer().configure(new SpringApplicationBuilder());

            assertEquals("UTC", TimeZone.getDefault().getID());
        } finally {
            TimeZone.setDefault(original);
        }
    }
}
