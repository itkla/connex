package ooo.klae.connex.backend.services;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.UserDashboard;
import ooo.klae.connex.backend.exceptions.BadRequestException;

class DashboardLayoutServiceTest extends AbstractServiceTest {

    @Autowired DashboardLayoutService service;

    @Test
    void saveLayout_persistsAndRoundTrips() {
        service.saveLayout(Map.of(
            "version", "1",
            "widgets", List.of(Map.of("id", "w1", "type", "pipeline", "span", "2"))));

        UserDashboard found = service.getLayout();
        Object layout = service.parseLayout(found.getLayoutJson());
        assertTrue(layout instanceof Map);
        assertEquals("1", ((Map<?, ?>) layout).get("version"));
    }

    @Test
    void saveLayout_replacesPrevious() {
        service.saveLayout(Map.of("marker", "first"));
        service.saveLayout(Map.of("marker", "second"));

        Object layout = service.parseLayout(service.getLayout().getLayoutJson());
        assertEquals("second", ((Map<?, ?>) layout).get("marker"));
    }

    @Test
    void getLayout_nullWhenUnset() {
        assertNull(service.getLayout());
    }

    @Test
    void saveLayout_nullLayout_throws() {
        assertThrows(BadRequestException.class, () -> service.saveLayout(null));
    }

    @Test
    void saveLayout_tooLarge_throws() {
        assertThrows(BadRequestException.class,
            () -> service.saveLayout(Map.of("blob", "y".repeat(20000))));
    }

    @Test
    void saveLayout_exactlyAtByteLimit_isAccepted() {
        service.saveLayout(Map.of("k", "x".repeat(16376)));
        assertNotNull(service.getLayout());
    }

    @Test
    void saveLayout_oneByteOverLimit_throws() {
        assertThrows(BadRequestException.class,
            () -> service.saveLayout(Map.of("k", "x".repeat(16377))));
    }

    @Test
    void resetLayout_removesIt() {
        service.saveLayout(Map.of("marker", "x"));
        service.resetLayout();
        assertNull(service.getLayout());
    }

    @Test
    void resetLayout_idempotentWhenNothingSaved() {
        assertDoesNotThrow(() -> service.resetLayout());
        assertNull(service.getLayout());
    }

    @Test
    void layout_isScopedToCurrentUser() {
        service.saveLayout(Map.of("owner", "mine"));

        User other = newUser();
        authAs(other);
        assertNull(service.getLayout());
        service.saveLayout(Map.of("owner", "theirs"));

        authAs(currentUser);
        Object layout = service.parseLayout(service.getLayout().getLayoutJson());
        assertEquals("mine", ((Map<?, ?>) layout).get("owner"));
    }

    private void authAs(User user) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
    }
}
