package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import ooo.klae.connex.backend.beans.Activity;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.PersonEdge;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.beans.WarmPathDismissal;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.WarmPathDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.IntroductionMapper;
import ooo.klae.connex.backend.mappers.PersonEdgeMapper;

/**
 * Database-backed tests for {@link WarmPathService}: the path lifecycle (surface, accept into a
 * task, dismiss) and the tenant-scoping invariants on reads and writes.
 */
@Transactional(isolation = Isolation.READ_COMMITTED)
class WarmPathServiceTest extends AbstractServiceTest {

    @Autowired private WarmPathService warmPathService;
    @Autowired private PersonEdgeMapper personEdgeMapper;
    @Autowired private IntroductionMapper introductionMapper;

    @Test
    void surfacesPathAndAcceptCreatesTaskAndRetiresTheTarget() {
        Person bridge = engagedPerson(newCompany());
        Person otherBridge = engagedPerson(newCompany());
        Person target = newPerson(newCompany());
        connect(bridge.getId(), target.getId());
        connect(otherBridge.getId(), target.getId());

        List<WarmPathDto> paths = warmPathService.getPaths(50);
        WarmPathDto row = findTarget(paths, target.getId());
        assertNotNull(row, "an untouched import bridged by a warm contact must surface");
        assertEquals(WarmPathService.REACH_NEW, row.getReachType());
        assertEquals(2, row.getBridges().size());
        assertEquals(WarmPathService.EVIDENCE_CONNECTION, row.getBridges().get(0).getEvidenceType());

        Task created = warmPathService.acceptPath(target.getId(), bridge.getId(), null);
        assertNotNull(created);
        assertTrue(created.getDescription().contains("(person:" + bridge.getId() + ")"));
        assertTrue(created.getDescription().contains("(person:" + target.getId() + ")"));
        assertEquals(currentUser.getId(), created.getAssignedTo().getId());
        assertEquals(target.getId(), created.getPerson().getId());

        assertTarget(warmPathService.getPaths(50), target.getId(), false);
    }

    @Test
    void acceptComposesTheDefaultTaskTextInTheRequestLocale() {
        Person bridge = engagedPerson(newCompany());
        Person target = newPerson(newCompany());
        connect(bridge.getId(), target.getId());

        LocaleContextHolder.setLocale(Locale.JAPANESE);
        try {
            Task created = warmPathService.acceptPath(target.getId(), bridge.getId(), null);
            assertTrue(created.getDescription().endsWith("への紹介を依頼する"),
                "a Japanese request locale must produce the Japanese default task text");
            assertTrue(created.getDescription().contains("(person:" + bridge.getId() + ")"));
        } finally {
            LocaleContextHolder.resetLocaleContext();
        }
    }

    @Test
    void acceptHonorsCallerSuppliedTaskText() {
        Person bridge = engagedPerson(newCompany());
        Person target = newPerson(newCompany());
        connect(bridge.getId(), target.getId());

        Task created = warmPathService.acceptPath(target.getId(), bridge.getId(), "  紹介をお願いする  ");
        assertEquals("紹介をお願いする", created.getDescription());
    }

    @Test
    void dismissingTheTargetHidesEveryAvenue() {
        Person bridgeA = engagedPerson(newCompany());
        Person bridgeB = engagedPerson(newCompany());
        Person target = newPerson(newCompany());
        connect(bridgeA.getId(), target.getId());
        connect(bridgeB.getId(), target.getId());

        assertTarget(warmPathService.getPaths(50), target.getId(), true);
        warmPathService.dismissPath(target.getId(), null);
        assertTarget(warmPathService.getPaths(50), target.getId(), false);
    }

    @Test
    void dismissingOneAvenueKeepsTheOthers() {
        Person bridgeA = engagedPerson(newCompany());
        Person bridgeB = engagedPerson(newCompany());
        Person target = newPerson(newCompany());
        connect(bridgeA.getId(), target.getId());
        connect(bridgeB.getId(), target.getId());

        warmPathService.dismissPath(target.getId(), bridgeA.getId());

        WarmPathDto row = findTarget(warmPathService.getPaths(50), target.getId());
        assertNotNull(row);
        assertEquals(1, row.getBridges().size());
        assertEquals(bridgeB.getId(), row.getBridges().get(0).getPersonId());
    }

    @Test
    void wholeTargetDismissalPreservesAcceptedLineage() {
        Person bridge = engagedPerson(newCompany());
        Person target = newPerson(newCompany());
        connect(bridge.getId(), target.getId());

        warmPathService.acceptPath(target.getId(), bridge.getId(), null);
        warmPathService.dismissPath(target.getId(), null);

        List<WarmPathDismissal> rows =
            introductionMapper.findWarmPathDismissals(workspace.getId()).stream()
                .filter(row -> row.getTargetPersonId() == target.getId())
                .toList();
        assertEquals(3, rows.size(),
            "the accepted avenue and target rows must survive a whole-target dismissal");
        assertTrue(rows.stream().anyMatch(row -> row.getBridgePersonId() == null));
        assertTrue(rows.stream().anyMatch(row ->
            row.getBridgePersonId() != null && row.getBridgePersonId() == bridge.getId()));
    }

    @Test
    void rejectsSelfBridgeAndForeignPersons() {
        Person mine = engagedPerson(newCompany());
        Person foreign = foreignPerson();

        assertThrows(BadRequestException.class,
            () -> warmPathService.acceptPath(mine.getId(), mine.getId(), null));
        assertThrows(ResourceNotFoundException.class,
            () -> warmPathService.acceptPath(foreign.getId(), mine.getId(), null));
        assertThrows(ResourceNotFoundException.class,
            () -> warmPathService.dismissPath(foreign.getId(), null));
    }

    @Test
    void foreignWorkspaceGraphNeverLeaksIntoThePaths() {
        Workspace other = newWorkspace();
        Person foreignBridge = foreignPersonIn(other);
        Person foreignTarget = foreignPersonIn(other);
        PersonEdge edge = new PersonEdge();
        edge.setWorkspaceId(other.getId());
        edge.setSourcePersonId(Math.min(foreignBridge.getId(), foreignTarget.getId()));
        edge.setTargetPersonId(Math.max(foreignBridge.getId(), foreignTarget.getId()));
        edge.setType("knows");
        edge.setStrength(2);
        personEdgeMapper.upsert(edge);

        List<WarmPathDto> paths = warmPathService.getPaths(50);
        assertTrue(paths.stream().noneMatch(row -> row.getTargetId() == foreignTarget.getId()));
        assertTrue(paths.stream().flatMap(row -> row.getBridges().stream())
            .noneMatch(bridgeDto -> bridgeDto.getPersonId() == foreignBridge.getId()));
    }

    /** A contact touched today, so it scores warm/hot and qualifies as a bridge. */
    private Person engagedPerson(Company company) {
        Person person = newPerson(company);
        Activity activity = new Activity();
        activity.setWorkspaceId(workspace.getId());
        activity.setType("call");
        activity.setSubject("subj_" + unique());
        activity.setNotes("notes_" + unique());
        activity.setPerson(person);
        activity.setCreatedBy(currentUser);
        activity.setTimestamp(LocalDateTime.now(ZoneOffset.UTC).format(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        activityMapper.insert(activity);
        return person;
    }

    private void connect(int a, int b) {
        PersonEdge edge = new PersonEdge();
        edge.setWorkspaceId(workspace.getId());
        edge.setSourcePersonId(Math.min(a, b));
        edge.setTargetPersonId(Math.max(a, b));
        edge.setType("knows");
        edge.setStrength(2);
        personEdgeMapper.upsert(edge);
    }

    private Workspace newWorkspace() {
        Workspace other = new Workspace();
        other.setName("Other " + unique());
        other.setSlug("other-" + unique());
        workspaceMapper.insert(other);
        return other;
    }

    private Person foreignPerson() {
        return foreignPersonIn(newWorkspace());
    }

    private Person foreignPersonIn(Workspace other) {
        Person person = new Person();
        person.setName("Foreign " + unique());
        person.setWorkspaceId(other.getId());
        personMapper.insert(person);
        return person;
    }

    private static WarmPathDto findTarget(List<WarmPathDto> paths, int targetId) {
        return paths.stream().filter(row -> row.getTargetId() == targetId).findFirst().orElse(null);
    }

    private static void assertTarget(List<WarmPathDto> paths, int targetId, boolean expected) {
        if (expected) {
            assertNotNull(findTarget(paths, targetId));
        } else {
            assertFalse(paths.stream().anyMatch(row -> row.getTargetId() == targetId));
        }
    }
}
