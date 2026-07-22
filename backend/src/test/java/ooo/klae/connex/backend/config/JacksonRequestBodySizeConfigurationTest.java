package ooo.klae.connex.backend.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import tools.jackson.core.json.JsonFactory;

class JacksonRequestBodySizeConfigurationTest {

    @Test
    void configuresJacksonFromTheLargestRouteBodyLimit() {
        RequestBodySizeProperties properties = new RequestBodySizeProperties();
        properties.setMaxBodyBytes(8);
        properties.setImportMaxBodyBytes(64);
        properties.setBusinessCardMaxBodyBytes(32);
        properties.setWebauthnMaxBodyBytes(4);
        properties.setWorkflowMaxBodyBytes(16);
        JacksonRequestBodySizeConfiguration configuration = new JacksonRequestBodySizeConfiguration();
        var customizer = configuration.requestBodyStreamReadConstraints(properties);
        var builder = JsonFactory.builder();

        customizer.customize(builder);

        assertEquals(64, builder.build().streamReadConstraints().getMaxDocumentLength());
    }

    @Test
    void raisingTheGeneralLimitAlsoRaisesJacksonsLimit() {
        RequestBodySizeProperties properties = new RequestBodySizeProperties();
        properties.setMaxBodyBytes(128);
        properties.setImportMaxBodyBytes(64);
        properties.setBusinessCardMaxBodyBytes(32);
        properties.setWebauthnMaxBodyBytes(4);
        properties.setWorkflowMaxBodyBytes(16);
        JacksonRequestBodySizeConfiguration configuration = new JacksonRequestBodySizeConfiguration();
        var customizer = configuration.requestBodyStreamReadConstraints(properties);
        var builder = JsonFactory.builder();

        customizer.customize(builder);

        assertEquals(128, builder.build().streamReadConstraints().getMaxDocumentLength());
    }
}
