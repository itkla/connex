package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import jakarta.servlet.Filter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.request.RequestContextHolder;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.mappers.UserMapper;

/**
 * Pins the endpoint contract for a caller holding a real session whose account row has since been
 * deleted: signed out, not looking at something missing. The app shell reads only 401 as signed
 * out, so any other status leaves a browser with a valid cookie stuck on a retryable "unavailable"
 * state that re-reads the same rejection forever.
 *
 * <p>Today the 401 is produced before the controller: {@code SessionEpochFilter} reads the
 * principal's session epoch, finds no row, clears the security context, and ordinary authorization
 * refuses the request. This test therefore guards that shielding, not the service beneath it — the
 * service-level contract that an unresolvable principal is an authentication failure rather than a
 * missing resource is pinned separately in {@code AuthServiceTest} (#1479).
 */
@SpringBootTest
class DeletedPrincipalResponseIntegrationTest {
    private static final String PASSWORD = "Deleted-Principal-Pw1!";

    @Autowired private WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") private Filter springSecurityFilterChain;
    @Autowired private UserMapper userMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;
    private Integer userId;

    @BeforeEach
    void setUp() {
        clearContexts();
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .addFilters(springSecurityFilterChain)
            .build();
    }

    /** The account is committed so the request thread can see it, so it is removed here too. */
    @AfterEach
    void clearContexts() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
        if (userId != null) {
            jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", userId);
            userId = null;
        }
    }

    @Test
    void currentUserWithADeletedAccountIsUnauthorized() throws Exception {
        User user = newUser();
        MockHttpSession session = login(user);

        mockMvc.perform(get("/api/auth/me").session(session))
            .andExpect(status().isOk());

        userMapper.delete(user.getId());

        mockMvc.perform(get("/api/auth/me").session(session))
            .andExpect(status().isUnauthorized());
    }

    private MockHttpSession login(User user) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + user.getUsername()
                    + "\",\"password\":\"" + PASSWORD + "\"}"))
            .andExpect(status().isOk())
            .andReturn();
        return assertInstanceOf(MockHttpSession.class, result.getRequest().getSession(false));
    }

    private User newUser() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User user = new User();
        user.setUsername("deleted_principal_" + suffix);
        user.setDisplayName("Deleted principal " + suffix);
        user.setEmail(suffix + "@example.com");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setTimezone("UTC");
        userMapper.insert(user);
        userId = user.getId();
        return user;
    }
}
