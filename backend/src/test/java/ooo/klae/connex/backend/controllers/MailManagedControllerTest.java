package ooo.klae.connex.backend.controllers;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import ooo.klae.connex.backend.mail.MailProperties;

class MailManagedControllerTest {

    private MailProperties mailProperties;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mailProperties = new MailProperties();
        mockMvc = MockMvcBuilders.standaloneSetup(new MailManagedController(mailProperties)).build();
    }

    @Test
    void managedReturnsFalseByDefault() throws Exception {
        mockMvc.perform(get("/api/mail/managed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.managed").value(false));
    }

    @Test
    void managedReturnsTrueWhenEnabled() throws Exception {
        mailProperties.setManaged(true);

        mockMvc.perform(get("/api/mail/managed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.managed").value(true));
    }
}
