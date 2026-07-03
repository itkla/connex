package ooo.klae.connex.backend.services;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Notification;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.IntroductionDto;
import ooo.klae.connex.backend.mappers.NotificationMapper;

class IntroductionMentionTest extends AbstractServiceTest {

    @Autowired IntroductionService introductionService;
    @Autowired NotificationMapper notificationMapper;

    private String mention(String label, User user) {
        return "[" + label + "](user:" + user.getId() + ")";
    }

    private List<Notification> mentions(int recipientId) {
        return notificationMapper.findPage(recipientId, null, "introduction", null, null, 50, 0)
            .stream().filter(n -> "introduction.mention".equals(n.getType())).toList();
    }

    /**
     * Recording an introduction whose note mentions a member notifies them and returns
     * the introduction with its resolved references.
     */
    @Test
    void createIntroduction_withMention_notifiesAndHydratesReferences() {
        Company company = newCompany();
        Person a = newPerson(company);
        Person b = newPerson(company);
        User mentioned = newUser();

        IntroductionDto dto = introductionService.createIntroduction(a.getId(), b.getId(),
            "Connecting you two, loop in " + mention("Mentioned", mentioned));

        assertNotNull(dto.getReferences());
        assertEquals(1, dto.getReferences().size());
        assertEquals(mentioned.getId(), dto.getReferences().get(0).getId());
        assertEquals("user", dto.getReferences().get(0).getType());

        List<Notification> notifications = mentions(mentioned.getId());
        assertEquals(1, notifications.size());
        Notification notification = notifications.get(0);
        assertEquals("introduction.mention", notification.getType());
        assertEquals("introduction", notification.getCategory());
        assertEquals(currentUser.getId(), notification.getActorId());
        assertEquals("introduction", notification.getSourceType());
        assertEquals(dto.getId(), notification.getSourceId());
        assertEquals("introduction.mention:" + dto.getId() + ":" + mentioned.getId(), notification.getDedupeKey());
    }

    /**
     * The author is never notified for mentioning themselves.
     */
    @Test
    void createIntroduction_selfMention_doesNotNotify() {
        Company company = newCompany();
        Person a = newPerson(company);
        Person b = newPerson(company);

        introductionService.createIntroduction(a.getId(), b.getId(), "note " + mention("Me", currentUser));

        assertTrue(mentions(currentUser.getId()).isEmpty());
    }

    /**
     * The lineage feed hydrates references so mentions render as chips.
     */
    @Test
    void lineage_hydratesReferences() {
        Company company = newCompany();
        Person a = newPerson(company);
        Person b = newPerson(company);
        User mentioned = newUser();
        IntroductionDto created =
            introductionService.createIntroduction(a.getId(), b.getId(), mention("Mentioned", mentioned));

        IntroductionDto fromLineage = introductionService.getLineage(1, 50).items().stream()
            .filter((i) -> i.getId() == created.getId())
            .findFirst()
            .orElseThrow();
        assertNotNull(fromLineage.getReferences());
        assertEquals(1, fromLineage.getReferences().size());
        assertEquals(mentioned.getId(), fromLineage.getReferences().get(0).getId());
    }
}
