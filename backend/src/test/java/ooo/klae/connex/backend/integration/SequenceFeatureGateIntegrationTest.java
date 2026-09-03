package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import ooo.klae.connex.backend.controllers.SequenceController;

/** Proves the disabled sequence flag removes the HTTP surface. */
@SpringBootTest(properties = {
    "connex.sequences.enabled=false",
    "spring.task.scheduling.enabled=false"
})
class SequenceFeatureGateIntegrationTest {
    @Autowired private WebApplicationContext context;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void previewRouteIsNotFoundWhenTheFeatureGateIsOff() throws Exception {
        assertNull(context.getBeanProvider(SequenceController.class).getIfAvailable());

        mockMvc.perform(post("/api/sequences/41/versions/2/preview")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"personId\":73}"))
            .andExpect(status().isNotFound());
    }
}
