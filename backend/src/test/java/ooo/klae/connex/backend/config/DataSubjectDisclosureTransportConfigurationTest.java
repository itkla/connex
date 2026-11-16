package ooo.klae.connex.backend.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;
import org.apache.catalina.connector.Connector;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import ooo.klae.connex.backend.services.DataSubjectDisclosureDelivery;

class DataSubjectDisclosureTransportConfigurationTest {
    @Test
    void usesTheCancellableNioProtocolWithoutChangingOtherConnectorConfiguration() {
        var factory = new TomcatServletWebServerFactory();
        var delivery = mock(DataSubjectDisclosureDelivery.class);
        factory.setPort(8123);
        new DataSubjectDisclosureTransportConfiguration().disclosureTransport(delivery).customize(factory);
        verifyNoInteractions(delivery);
        factory.getConnectorCustomizers().forEach(customizer -> customizer.customize(new Connector()));
        verify(delivery).requireTransport();
        assertEquals(DataSubjectDisclosureTransportConfiguration.DisclosureProtocol.class.getName(), factory.getProtocol());
        assertEquals(8123, factory.getPort());
    }

    @Test
    void unlimitedConnectorWritesAreRefusedBeforeTheConnectorStarts() {
        var protocol = new DataSubjectDisclosureTransportConfiguration.DisclosureProtocol();
        for (int timeout : new int[] {0, -1}) {
            protocol.setConnectionTimeout(timeout);
            assertThrows(IllegalStateException.class, protocol::init);
        }
    }
}
