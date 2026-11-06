package ooo.klae.connex.backend.controllers;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import ooo.klae.connex.backend.services.MyWorkReadService;

/** Caller-supplied member selectors cannot change any personal read's service arguments. */
@ExtendWith(MockitoExtension.class)
class MyWorkReadControllerTest {
    @Mock private MyWorkReadService readService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new MyWorkReadController(readService)).build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"history", "pulse", "relationships", "deal-kpis", "risks"})
    void ignoresAttemptsToSelectAnotherMember(String section) throws Exception {
        mockMvc.perform(get("/api/my-work/" + section)
                .param("userId", "99").param("id", "99").param("ownerId", "99")
                .param("scope", "members").param("memberIds", "99").param("currency", "JPY"))
            .andExpect(status().isOk());

        switch (section) {
            case "history" -> verify(readService).history();
            case "pulse" -> verify(readService).pulse();
            case "relationships" -> verify(readService).relationships();
            case "deal-kpis" -> verify(readService).dealKpis("JPY");
            case "risks" -> verify(readService).risks();
            default -> throw new IllegalArgumentException("Unknown personal section");
        }
        verifyNoMoreInteractions(readService);
    }
}
