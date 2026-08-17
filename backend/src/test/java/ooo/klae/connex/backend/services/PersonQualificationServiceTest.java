package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.PersonLifecycleStage;
import ooo.klae.connex.backend.beans.QualificationAnswer;
import ooo.klae.connex.backend.beans.QualificationCriterion;
import ooo.klae.connex.backend.beans.QualificationDimension;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.PersonLifecycleRequest;
import ooo.klae.connex.backend.dto.PersonQualificationDto;
import ooo.klae.connex.backend.dto.PersonQualificationRequest;
import ooo.klae.connex.backend.dto.QualificationCriterionRequest;
import ooo.klae.connex.backend.dto.QualificationScoreDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.ShareMapper;
import ooo.klae.connex.backend.notifications.NotificationChangePublisher;

/**
 * Deterministic qualification scoring and the required-criteria gate on {@code QUALIFIED} (#559,
 * increment 5 of {@code docs/LEAD_LIFECYCLE.md}).
 *
 * <p>The cases that matter are the ones where a number could lie: an unconfigured dimension must
 * not read as zero, an unanswered question must not lift a score, and archiving a criterion must
 * change the score without destroying the answers already given against it.
 */
class PersonQualificationServiceTest extends AbstractServiceTest {

    @Autowired PersonQualificationService qualificationService;
    @Autowired QualificationCriterionService criterionService;
    @Autowired PersonLifecycleService lifecycleService;
    @Autowired ShareMapper shareMapper;
    @MockitoBean RuleTriggerPublisher ruleTriggers;
    @MockitoBean NotificationChangePublisher notificationChanges;

    @Test
    void anUnconfiguredDimensionScoresNullRatherThanZero() {
        Person person = newPerson(newCompany());
        criterion("Has budget", QualificationDimension.FIT, 1, false);

        PersonQualificationDto qualification =
            qualificationService.getQualification(person.getId());

        assertEquals(0, score(qualification, QualificationDimension.FIT).percent(),
            "a fit criterion exists and nothing is met yet, which is a real zero");
        assertNull(score(qualification, QualificationDimension.ENGAGEMENT).percent(),
            "no engagement criterion is configured, so the workspace has made no such assessment "
                + "and a zero would invent one");
        assertEquals(0, score(qualification, QualificationDimension.ENGAGEMENT).totalWeight());
    }

    @Test
    void anUnansweredCriterionNeverLiftsTheScoreAndIsCountedSeparately() {
        Person person = newPerson(newCompany());
        QualificationCriterion met = criterion("Has budget", QualificationDimension.FIT, 1, false);
        criterion("Has authority", QualificationDimension.FIT, 1, false);
        answer(person, met, QualificationAnswer.MET);

        QualificationScoreDto fit =
            score(qualificationService.getQualification(person.getId()), QualificationDimension.FIT);

        assertEquals(50, fit.percent());
        assertEquals(1, fit.unansweredCount(),
            "the untouched question is reported, so 50% with one unasked is distinguishable "
                + "from 50% with both answered");
    }

    @Test
    void unknownIsAnAnswerThatStillLeavesTheCriterionUnmet() {
        Person person = newPerson(newCompany());
        QualificationCriterion budget = criterion("Has budget", QualificationDimension.FIT, 1, false);
        answer(person, budget, QualificationAnswer.UNKNOWN);

        QualificationScoreDto fit =
            score(qualificationService.getQualification(person.getId()), QualificationDimension.FIT);

        assertEquals(0, fit.percent());
        assertEquals(0, fit.unansweredCount(), "the question was put; the answer was inconclusive");
    }

    @Test
    void weightsDecideTheContributionWithinADimension() {
        Person person = newPerson(newCompany());
        QualificationCriterion heavy = criterion("Board sponsor", QualificationDimension.FIT, 30, false);
        criterion("Uses a competitor", QualificationDimension.FIT, 10, false);
        answer(person, heavy, QualificationAnswer.MET);

        assertEquals(75, score(
            qualificationService.getQualification(person.getId()),
            QualificationDimension.FIT).percent());
    }

    @Test
    void theTwoDimensionsAreScoredSeparately() {
        Person person = newPerson(newCompany());
        QualificationCriterion fit = criterion("Right size", QualificationDimension.FIT, 1, false);
        criterion("Replied recently", QualificationDimension.ENGAGEMENT, 1, false);
        answer(person, fit, QualificationAnswer.MET);

        PersonQualificationDto qualification =
            qualificationService.getQualification(person.getId());

        assertEquals(100, score(qualification, QualificationDimension.FIT).percent());
        assertEquals(0, score(qualification, QualificationDimension.ENGAGEMENT).percent());
    }

    @Test
    void aRequiredCriterionGatesTheMoveToQualified() {
        Person person = enterLifecycle(newPerson(newCompany()));
        QualificationCriterion required =
            criterion("Confirmed budget", QualificationDimension.FIT, 1, true);

        BadRequestException refused = assertThrows(BadRequestException.class,
            () -> lifecycleService.updateLifecycle(
                person.getId(), stage(PersonLifecycleStage.QUALIFIED)));
        assertTrue(refused.getMessage().contains("Confirmed budget"),
            "the refusal names what is missing so the user can act on it");

        answer(person, required, QualificationAnswer.MET);
        Person qualified = lifecycleService.updateLifecycle(
            person.getId(), stage(PersonLifecycleStage.QUALIFIED));

        assertEquals(PersonLifecycleStage.QUALIFIED, qualified.getLifecycleStage());
    }

    @Test
    void aWorkspaceWithNoRequiredCriteriaKeepsQualifyingFreely() {
        Person person = enterLifecycle(newPerson(newCompany()));
        criterion("Nice to have", QualificationDimension.FIT, 1, false);

        Person qualified = lifecycleService.updateLifecycle(
            person.getId(), stage(PersonLifecycleStage.QUALIFIED));

        assertEquals(PersonLifecycleStage.QUALIFIED, qualified.getLifecycleStage(),
            "the gate holds a workspace to its own standard; it must not invent one");
    }

    @Test
    void anUnknownAnswerDoesNotSatisfyARequiredCriterion() {
        Person person = enterLifecycle(newPerson(newCompany()));
        QualificationCriterion required =
            criterion("Confirmed budget", QualificationDimension.FIT, 1, true);
        answer(person, required, QualificationAnswer.UNKNOWN);

        assertThrows(BadRequestException.class, () -> lifecycleService.updateLifecycle(
            person.getId(), stage(PersonLifecycleStage.QUALIFIED)));
    }

    @Test
    void archivingACriterionStopsItScoringAndGatingButKeepsItsAnswers() {
        Person person = enterLifecycle(newPerson(newCompany()));
        QualificationCriterion required =
            criterion("Legacy question", QualificationDimension.FIT, 1, true);
        answer(person, required, QualificationAnswer.NOT_MET);

        criterionService.archive(required.getId());

        PersonQualificationDto qualification =
            qualificationService.getQualification(person.getId());
        assertTrue(qualification.criteria().isEmpty(), "an archived criterion is not asked again");
        assertTrue(qualification.qualifiable(), "and it no longer gates");
        assertEquals(PersonLifecycleStage.QUALIFIED, lifecycleService.updateLifecycle(
            person.getId(), stage(PersonLifecycleStage.QUALIFIED)).getLifecycleStage());

        criterionService.restore(required.getId());
        assertEquals(QualificationAnswer.NOT_MET,
            qualificationService.getQualification(person.getId()).criteria().getFirst().answer(),
            "the answer given before archiving survived and is authoritative again");
    }

    @Test
    void clearingAnAnswerReturnsTheCriterionToUnanswered() {
        Person person = newPerson(newCompany());
        QualificationCriterion budget = criterion("Has budget", QualificationDimension.FIT, 1, false);
        answer(person, budget, QualificationAnswer.MET);

        PersonQualificationRequest cleared = new PersonQualificationRequest();
        cleared.setCriterionId(budget.getId());
        qualificationService.answer(person.getId(), cleared);

        PersonQualificationDto qualification =
            qualificationService.getQualification(person.getId());
        assertNull(qualification.criteria().getFirst().answer());
        assertEquals(1, score(qualification, QualificationDimension.FIT).unansweredCount());
    }

    @Test
    void reAnsweringReplacesInPlaceRatherThanAccumulating() {
        Person person = newPerson(newCompany());
        QualificationCriterion budget = criterion("Has budget", QualificationDimension.FIT, 1, false);
        answer(person, budget, QualificationAnswer.MET);
        answer(person, budget, QualificationAnswer.NOT_MET);

        PersonQualificationDto qualification =
            qualificationService.getQualification(person.getId());
        assertEquals(1, qualification.criteria().size());
        assertEquals(QualificationAnswer.NOT_MET, qualification.criteria().getFirst().answer());
        assertNotNull(qualification.criteria().getFirst().answeredAt());
    }

    @Test
    void aSharedInContactCannotBeAssessedByTheGranteeWorkspace() {
        Person person = newPerson(newCompany());
        QualificationCriterion budget = criterion("Has budget", QualificationDimension.FIT, 1, false);
        answer(person, budget, QualificationAnswer.MET);

        Workspace grantee = siblingWorkspace();
        shareMapper.sharePerson(
            person.getId(), workspace.getId(), grantee.getId(), currentUser.getId(), false);
        User outsider = newUser();
        workspaceMapper.addMember(grantee.getId(), outsider.getId(), "owner");
        authenticateAs(outsider, grantee.getId());

        assertThrows(ResourceNotFoundException.class,
            () -> qualificationService.getQualification(person.getId()));
        PersonQualificationRequest request = new PersonQualificationRequest();
        request.setCriterionId(budget.getId());
        request.setAnswer(QualificationAnswer.NOT_MET);
        assertThrows(ResourceNotFoundException.class,
            () -> qualificationService.answer(person.getId(), request));
    }

    @Test
    void anArchivedCriterionCannotBeAnswered() {
        Person person = newPerson(newCompany());
        QualificationCriterion budget = criterion("Has budget", QualificationDimension.FIT, 1, false);
        criterionService.archive(budget.getId());

        PersonQualificationRequest request = new PersonQualificationRequest();
        request.setCriterionId(budget.getId());
        request.setAnswer(QualificationAnswer.MET);
        assertThrows(BadRequestException.class,
            () -> qualificationService.answer(person.getId(), request));
    }

    @Test
    void aCriterionFromAnotherWorkspaceIsNotAnswerable() {
        Person person = newPerson(newCompany());
        Workspace other = siblingWorkspace();
        User outsider = newUser();
        workspaceMapper.addMember(other.getId(), outsider.getId(), "owner");
        authenticateAs(outsider, other.getId());
        QualificationCriterion foreign =
            criterion("Their question", QualificationDimension.FIT, 1, false);
        authenticateAs(currentUser, workspace.getId());

        PersonQualificationRequest request = new PersonQualificationRequest();
        request.setCriterionId(foreign.getId());
        request.setAnswer(QualificationAnswer.MET);
        assertThrows(ResourceNotFoundException.class,
            () -> qualificationService.answer(person.getId(), request));
    }

    @Test
    void criteriaWeightsAreBounded() {
        QualificationCriterionRequest request = new QualificationCriterionRequest();
        request.setLabel("Too heavy");
        request.setDimension(QualificationDimension.FIT);
        request.setWeight(101);
        assertThrows(BadRequestException.class, () -> criterionService.create(request));

        request.setWeight(0);
        assertThrows(BadRequestException.class, () -> criterionService.create(request));

        request.setWeight(1);
        request.setLabel("   ");
        assertThrows(BadRequestException.class, () -> criterionService.create(request));
    }

    private QualificationCriterion criterion(
            String label, QualificationDimension dimension, int weight, boolean required) {
        QualificationCriterionRequest request = new QualificationCriterionRequest();
        request.setLabel(label);
        request.setDimension(dimension);
        request.setWeight(weight);
        request.setRequired(required);
        return criterionService.create(request);
    }

    private void answer(Person person, QualificationCriterion criterion, QualificationAnswer value) {
        PersonQualificationRequest request = new PersonQualificationRequest();
        request.setCriterionId(criterion.getId());
        request.setAnswer(value);
        qualificationService.answer(person.getId(), request);
    }

    private static QualificationScoreDto score(
            PersonQualificationDto qualification, QualificationDimension dimension) {
        return qualification.scores().stream()
            .filter(score -> score.dimension() == dimension)
            .findFirst()
            .orElseThrow();
    }

    private Person enterLifecycle(Person person) {
        lifecycleService.updateLifecycle(person.getId(), stage(PersonLifecycleStage.NEW));
        return person;
    }

    private static PersonLifecycleRequest stage(PersonLifecycleStage stage) {
        PersonLifecycleRequest request = new PersonLifecycleRequest();
        request.setStage(stage);
        return request;
    }

    private Workspace siblingWorkspace() {
        Workspace sibling = new Workspace();
        sibling.setName("Sibling " + unique());
        sibling.setSlug("sibling-" + unique());
        sibling.setOrgId(workspaceMapper.getOrgId(workspace.getId()));
        workspaceMapper.insert(sibling);
        return sibling;
    }
}
