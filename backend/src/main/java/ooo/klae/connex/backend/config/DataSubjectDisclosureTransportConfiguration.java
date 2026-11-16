package ooo.klae.connex.backend.config;

import java.io.IOException;

import org.apache.coyote.Processor;
import org.apache.coyote.http11.Http11NioProtocol;
import org.apache.coyote.http11.Http11Processor;
import org.apache.tomcat.util.net.AbstractEndpoint.Handler.SocketState;
import org.apache.tomcat.util.net.SocketWrapperBase;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import ooo.klae.connex.backend.services.DataSubjectDisclosureDelivery;

/** Makes the deployed blocking HTTP/1.1 connector cancellable at an absolute disclosure deadline. */
@Configuration(proxyBeanMethods = false)
public class DataSubjectDisclosureTransportConfiguration {
    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> disclosureTransport(DataSubjectDisclosureDelivery delivery) {
        return factory -> {
            factory.setProtocol(DisclosureProtocol.class.getName());
            factory.addConnectorCustomizers(connector -> delivery.requireTransport());
        };
    }

    /** Retains Tomcat's ordinary NIO behavior and binds cancellation only during synchronous service. */
    public static final class DisclosureProtocol extends Http11NioProtocol {
        @Override
        public void init() throws Exception {
            if (getConnectionTimeout() <= 0) {
                throw new IllegalStateException("Disclosure transport requires a positive connector write timeout");
            }
            super.init();
        }

        @Override
        protected Processor createProcessor() {
            return new Http11Processor(this, getAdapter()) {
                @Override
                public SocketState service(SocketWrapperBase<?> socket) throws IOException {
                    try (var binding = DataSubjectDisclosureDelivery.withTransport(socket::close)) {
                        return super.service(socket);
                    }
                }
            };
        }
    }
}
