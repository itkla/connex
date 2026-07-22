package ooo.klae.connex.backend.controllers;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Properties;

import com.fasterxml.jackson.annotation.JsonInclude;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import tools.jackson.databind.json.JsonMapper;

class VersionControllerTest {

    private DefaultListableBeanFactory beanFactory;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        beanFactory = new DefaultListableBeanFactory();
        JsonMapper jsonMapper = JsonMapper.builder()
                .changeDefaultPropertyInclusion(ignored -> JsonInclude.Value.ALL_NON_NULL)
                .build();
        mockMvc = MockMvcBuilders.standaloneSetup(
                new VersionController(beanFactory.getBeanProvider(BuildProperties.class)))
                .setMessageConverters(new JacksonJsonHttpMessageConverter(jsonMapper))
                .build();
    }

    @Test
    void versionReturnsBuildPropertiesWhenPresent() throws Exception {
        Properties properties = new Properties();
        properties.setProperty("version", "1.2.3");
        properties.setProperty("time", "2026-07-12T18:30:00Z");
        beanFactory.registerSingleton("buildProperties", new BuildProperties(properties));

        mockMvc.perform(get("/api/version"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("1.2.3"))
                .andExpect(jsonPath("$.buildTime").value("2026-07-12T18:30:00Z"));
    }

    @Test
    void versionReturnsDevelopmentDefaultsWhenBuildPropertiesAreAbsent() throws Exception {
        mockMvc.perform(get("/api/version"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("dev"))
                .andExpect(jsonPath("$.buildTime").value(nullValue()));
    }
}
