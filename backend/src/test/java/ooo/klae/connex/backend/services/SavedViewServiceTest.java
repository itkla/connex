package ooo.klae.connex.backend.services;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import ooo.klae.connex.backend.beans.SavedView;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.DuplicateResourceException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;

class SavedViewServiceTest extends AbstractServiceTest {

    @Autowired SavedViewService service;

    @Test
    void create_persistsAndRoundTripsConfig() {
        SavedView view = service.create("company", "Hot prospects",
            Map.of("query", "acme", "filters", Map.of("industry", List.of("Tech"))));

        assertNotEquals(0, view.getId());
        SavedView found = service.getById(view.getId());
        assertEquals("Hot prospects", found.getName());
        Object config = service.parseConfig(found.getConfigJson());
        assertTrue(config instanceof Map);
        assertEquals("acme", ((Map<?, ?>) config).get("query"));
    }

    @Test
    void create_duplicateName_throws() {
        service.create("company", "tier-a", Map.of());
        assertThrows(DuplicateResourceException.class, () -> service.create("company", "tier-a", Map.of()));
    }

    @Test
    void create_sameNameDifferentRecordType_allowed() {
        service.create("company", "prospects", Map.of());
        assertNotEquals(0, service.create("deal", "prospects", Map.of()).getId());
    }

    @Test
    void create_invalidRecordType_throws() {
        assertThrows(BadRequestException.class, () -> service.create("widget", "x", Map.of()));
    }

    @Test
    void create_blankNameOrNullConfig_throws() {
        assertThrows(BadRequestException.class, () -> service.create("company", "  ", Map.of()));
        assertThrows(BadRequestException.class, () -> service.create("company", "x", null));
    }

    @Test
    void getById_missing_throws() {
        assertThrows(ResourceNotFoundException.class, () -> service.getById(-1));
    }

    @Test
    void update_changesNameAndConfig() {
        SavedView view = service.create("person", "contacts", Map.of("query", "a"));
        SavedView updated = service.update(view.getId(), "my-contacts", Map.of("query", "b"), 3);

        assertEquals("my-contacts", updated.getName());
        assertEquals(3, updated.getPosition());
        assertEquals("b", ((Map<?, ?>) service.parseConfig(updated.getConfigJson())).get("query"));
    }

    @Test
    void update_renameToExistingName_throws() {
        service.create("company", "alpha", Map.of());
        SavedView beta = service.create("company", "beta", Map.of());
        assertThrows(DuplicateResourceException.class, () -> service.update(beta.getId(), "alpha", null, null));
    }

    @Test
    void delete_removesIt() {
        SavedView view = service.create("deal", "to-delete", Map.of());
        service.delete(view.getId());
        assertThrows(ResourceNotFoundException.class, () -> service.getById(view.getId()));
    }

    @Test
    void views_areScopedToCurrentUser() {
        SavedView mine = service.create("company", "mine", Map.of());

        User other = newUser();
        authAs(other);
        assertTrue(service.list("company").stream().noneMatch(v -> v.getId() == mine.getId()));
        assertThrows(ResourceNotFoundException.class, () -> service.getById(mine.getId()));
        assertThrows(ResourceNotFoundException.class, () -> service.delete(mine.getId()));
        SavedView theirs = service.create("company", "theirs", Map.of());

        authAs(currentUser);
        assertTrue(service.list("company").stream().anyMatch(v -> v.getId() == mine.getId()));
        assertTrue(service.list("company").stream().noneMatch(v -> v.getId() == theirs.getId()));
    }

    private void authAs(User user) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
    }
}
