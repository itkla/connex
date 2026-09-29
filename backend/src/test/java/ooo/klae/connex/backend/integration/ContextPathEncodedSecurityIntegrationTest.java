package ooo.klae.connex.backend.integration;

import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"server.address=127.0.0.1", "server.servlet.context-path=/connex"})
class ContextPathEncodedSecurityIntegrationTest extends AbstractEncodedSecurityPathIntegrationTest {
}
