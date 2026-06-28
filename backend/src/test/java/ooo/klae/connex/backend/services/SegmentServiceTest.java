package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.Activity;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.PersonEdge;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.dto.SegmentSelection;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.mappers.PersonEdgeMapper;

class SegmentServiceTest extends AbstractServiceTest {

    @Autowired SegmentService segmentService;
    @Autowired PersonEdgeMapper edgeMapper;

    private static final DateTimeFormatter MYSQL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static SegmentSelection segment(String key) {
        SegmentSelection selection = new SegmentSelection();
        selection.setKey(key);
        return selection;
    }

    private void recentActivity(Person person) {
        Activity activity = new Activity();
        activity.setWorkspaceId(workspace.getId());
        activity.setType("call");
        activity.setSubject("recent_" + unique());
        activity.setPerson(person);
        activity.setCreatedBy(currentUser);
        activity.setTimestamp(LocalDateTime.now().format(MYSQL));
        activityMapper.insert(activity);
    }

    private void strongEdge(Person a, Person b) {
        PersonEdge edge = new PersonEdge();
        edge.setWorkspaceId(workspace.getId());
        edge.setSourcePersonId(Math.min(a.getId(), b.getId()));
        edge.setTargetPersonId(Math.max(a.getId(), b.getId()));
        edge.setType("knows");
        edge.setStrength(3);
        edge.setCreatedAt(LocalDateTime.now().format(MYSQL));
        edgeMapper.upsert(edge);
    }

    @Test
    void openDeal_matchesCompaniesWithAnOpenDeal() {
        Company withDeal = newCompany();
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        newDeal(pipeline, stage, withDeal);
        Company withoutDeal = newCompany();

        List<Integer> ids = segmentService.evaluate("company", List.of(segment("open_deal")));

        assertTrue(ids.contains(withDeal.getId()));
        assertFalse(ids.contains(withoutDeal.getId()));
    }

    @Test
    void noActivity_excludesCompaniesWithRecentActivity() {
        Company quiet = newCompany();
        Company active = newCompany();
        recentActivity(newPerson(active));

        List<Integer> ids = segmentService.evaluate("company", List.of(segment("no_activity")));

        assertTrue(ids.contains(quiet.getId()));
        assertFalse(ids.contains(active.getId()));
    }

    @Test
    void warmIntroAvailable_matchesTeamConnectedCompanyWithNoActivityFromMe() {
        Company target = newCompany();
        Person contact = newPerson(target);
        Person engaged = newPerson(newCompany());
        recentActivity(engaged);
        strongEdge(contact, engaged);

        List<Integer> ids = segmentService.evaluate("company", List.of(segment("warm_intro_available")));

        assertTrue(ids.contains(target.getId()));
    }

    @Test
    void warmIntroAvailable_excludesCompanyIveAlreadyEngaged() {
        Company target = newCompany();
        Person contact = newPerson(target);
        recentActivity(contact);
        Person engaged = newPerson(newCompany());
        recentActivity(engaged);
        strongEdge(contact, engaged);

        List<Integer> ids = segmentService.evaluate("company", List.of(segment("warm_intro_available")));

        assertFalse(ids.contains(target.getId()));
    }

    @Test
    void cooling_includesUntouchedCompany() {
        Company cold = newCompany();

        List<Integer> ids = segmentService.evaluate("company", List.of(segment("cooling")));

        assertTrue(ids.contains(cold.getId()));
    }

    @Test
    void multipleSegments_areIntersectedWithAnd() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Company both = newCompany();
        newDeal(pipeline, stage, both);
        Company dealButActive = newCompany();
        newDeal(pipeline, stage, dealButActive);
        recentActivity(newPerson(dealButActive));

        List<Integer> ids = segmentService.evaluate("company", List.of(segment("open_deal"), segment("no_activity")));

        assertTrue(ids.contains(both.getId()));
        assertFalse(ids.contains(dealButActive.getId()));
    }

    @Test
    void unknownSegment_throwsBadRequest() {
        assertThrows(BadRequestException.class,
            () -> segmentService.evaluate("company", List.of(segment("bogus"))));
    }

    @Test
    void unsupportedRecordType_throwsBadRequest() {
        assertThrows(BadRequestException.class,
            () -> segmentService.evaluate("person", List.of(segment("open_deal"))));
    }
}
