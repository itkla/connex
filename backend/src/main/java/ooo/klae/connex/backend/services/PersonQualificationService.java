package ooo.klae.connex.backend.services;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.PersonQualificationAnswer;
import ooo.klae.connex.backend.beans.QualificationAnswer;
import ooo.klae.connex.backend.beans.QualificationCriterion;
import ooo.klae.connex.backend.beans.QualificationDimension;
import ooo.klae.connex.backend.dto.PersonQualificationCriterionDto;
import ooo.klae.connex.backend.dto.PersonQualificationDto;
import ooo.klae.connex.backend.dto.PersonQualificationRequest;
import ooo.klae.connex.backend.dto.QualificationScoreDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.PersonQualificationMapper;
import ooo.klae.connex.backend.mappers.QualificationCriterionMapper;
import ooo.klae.connex.backend.notifications.NotificationChangePublisher;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;

/**
 * Scores a contact against the workspace's qualification criteria and records the answers behind
 * the score (#559).
 *
 * <p>Scoring is deterministic and computed on read. Nothing is stored, because a stored score is a
 * second truth that goes stale the instant a weight changes or a criterion is archived — and the
 * whole point of the score is to decide whether a contact may be qualified. The cost is one extra
 * read per contact; the alternative is a number that disagrees with the criteria it claims to
 * summarise.
 *
 * <p>Fit and engagement are scored separately and never combined. A contact that fits perfectly but
 * has gone silent and one that talks constantly but could never buy are different problems, and a
 * single blended number hides which one is on screen.
 *
 * <p>Like the lifecycle stage it gates, qualification is strictly the owning workspace's own
 * assessment: a contact that is merely shared in is not addressable here.
 */
@Service
@RequiredArgsConstructor
public class PersonQualificationService {

    private final PersonMapper personMapper;
    private final PersonQualificationMapper qualificationMapper;
    private final QualificationCriterionMapper criterionMapper;
    private final WorkspaceService workspaceService;
    private final AuditService auditService;
    private final NotificationChangePublisher notificationChanges;

    /**
     * The contact's qualification picture: every active criterion with its answer, the score for
     * each dimension, and whether the required criteria are satisfied.
     *
     * @param personId contact to read
     * @return qualification state
     */
    public PersonQualificationDto getQualification(int personId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        requireOwnedPerson(workspaceId, personId);
        return project(workspaceId, personId);
    }

    /**
     * Records one answer against a contact, or clears it when the request carries no answer.
     *
     * @param personId contact being assessed
     * @param request criterion and answer
     * @return the contact's qualification state after the change
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    @RequirePermission(Permission.PERSON_UPDATE)
    public PersonQualificationDto answer(int personId, PersonQualificationRequest request) {
        if (request == null || request.getCriterionId() == null) {
            throw new BadRequestException("A criterion is required");
        }
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Person person = requireOwnedPerson(workspaceId, personId);
        QualificationCriterion criterion =
            criterionMapper.getById(workspaceId, request.getCriterionId());
        if (criterion == null) {
            throw new ResourceNotFoundException(
                "Criterion not found with id: " + request.getCriterionId());
        }
        if (criterion.getArchivedAt() != null) {
            throw new BadRequestException("That criterion is archived and cannot be answered");
        }
        QualificationAnswer previous = currentAnswer(workspaceId, personId, criterion.getId());
        if (request.getAnswer() == null) {
            qualificationMapper.delete(workspaceId, personId, criterion.getId());
        } else {
            PersonQualificationAnswer answer = new PersonQualificationAnswer();
            answer.setWorkspaceId(workspaceId);
            answer.setPersonId(personId);
            answer.setCriterionId(criterion.getId());
            answer.setAnswer(request.getAnswer());
            answer.setAnsweredById(workspaceService.getCurrentUserId());
            qualificationMapper.upsert(answer);
        }
        recordAudit(person, criterion, previous, request.getAnswer());
        notificationChanges.publish(workspaceId, "person", personId);
        return project(workspaceId, personId);
    }

    /**
     * Whether every required criterion is met, which is what the {@code QUALIFIED} transition
     * enforces. A workspace that has configured no required criteria is unaffected: the gate exists
     * to hold a workspace to its own stated standard, not to invent one.
     *
     * @param workspaceId owning workspace
     * @param personId contact to check
     * @return labels of required criteria not yet met, empty when the contact may be qualified
     */
    public List<String> unmetRequiredCriteria(int workspaceId, int personId) {
        Map<Integer, QualificationAnswer> answers = answersByCriterion(workspaceId, personId);
        List<String> unmet = new ArrayList<>();
        for (QualificationCriterion criterion : criterionMapper.getActive(workspaceId)) {
            if (criterion.isRequired()
                    && answers.get(criterion.getId()) != QualificationAnswer.MET) {
                unmet.add(criterion.getLabel());
            }
        }
        return unmet;
    }

    private PersonQualificationDto project(int workspaceId, int personId) {
        List<QualificationCriterion> criteria = criterionMapper.getActive(workspaceId);
        Map<Integer, PersonQualificationAnswer> answers = new HashMap<>();
        for (PersonQualificationAnswer answer
                : qualificationMapper.getByPersonId(workspaceId, personId)) {
            answers.put(answer.getCriterionId(), answer);
        }
        List<PersonQualificationCriterionDto> projected = new ArrayList<>();
        Map<QualificationDimension, Tally> tallies = new EnumMap<>(QualificationDimension.class);
        List<String> unmetRequired = new ArrayList<>();
        for (QualificationCriterion criterion : criteria) {
            PersonQualificationAnswer answer = answers.get(criterion.getId());
            QualificationAnswer value = answer == null ? null : answer.getAnswer();
            projected.add(new PersonQualificationCriterionDto(
                criterion.getId(),
                criterion.getLabel(),
                criterion.getDimension(),
                criterion.getWeight(),
                criterion.isRequired(),
                value,
                answer == null ? null : answer.getAnsweredById(),
                answer == null ? null : answer.getAnsweredAt()));
            Tally tally = tallies.computeIfAbsent(criterion.getDimension(), key -> new Tally());
            tally.totalWeight += criterion.getWeight();
            if (value == QualificationAnswer.MET) {
                tally.metWeight += criterion.getWeight();
            } else if (value == null) {
                tally.unansweredCount++;
            }
            if (criterion.isRequired() && value != QualificationAnswer.MET) {
                tally.unmetRequiredLabels.add(criterion.getLabel());
                unmetRequired.add(criterion.getLabel());
            }
        }
        List<QualificationScoreDto> scores = new ArrayList<>();
        for (QualificationDimension dimension : QualificationDimension.values()) {
            Tally tally = tallies.get(dimension);
            scores.add(tally == null
                ? new QualificationScoreDto(dimension, null, 0, 0, 0, List.of())
                : new QualificationScoreDto(
                    dimension,
                    percent(tally.metWeight, tally.totalWeight),
                    tally.metWeight,
                    tally.totalWeight,
                    tally.unansweredCount,
                    List.copyOf(tally.unmetRequiredLabels)));
        }
        return new PersonQualificationDto(projected, scores, unmetRequired.isEmpty());
    }

    /**
     * Whole-percent score, or {@code null} when the dimension has no criteria at all. A workspace
     * that never wrote a fit question has not decided the contact is a bad fit, and reporting 0%
     * would manufacture an assessment nobody made.
     */
    private static Integer percent(int metWeight, int totalWeight) {
        if (totalWeight <= 0) {
            return null;
        }
        return Math.round((float) metWeight * 100 / totalWeight);
    }

    private QualificationAnswer currentAnswer(int workspaceId, int personId, int criterionId) {
        return answersByCriterion(workspaceId, personId).get(criterionId);
    }

    private Map<Integer, QualificationAnswer> answersByCriterion(int workspaceId, int personId) {
        Map<Integer, QualificationAnswer> answers = new HashMap<>();
        for (PersonQualificationAnswer answer
                : qualificationMapper.getByPersonId(workspaceId, personId)) {
            answers.put(answer.getCriterionId(), answer.getAnswer());
        }
        return answers;
    }

    private void recordAudit(
            Person person,
            QualificationCriterion criterion,
            QualificationAnswer before,
            QualificationAnswer after) {
        if (before == after) {
            return;
        }
        auditService.record("person.qualification", "person", person.getId(), person.getName(),
            "Answered qualification criterion for " + person.getName(),
            Map.of("criterion", criterion.getLabel(),
                "answer", Map.of(
                    "from", before == null ? "unanswered" : before.name(),
                    "to", after == null ? "unanswered" : after.name())));
    }

    private Person requireOwnedPerson(int workspaceId, int personId) {
        Person person = personMapper.getPersonById(workspaceId, personId);
        if (person == null || person.getWorkspaceId() != workspaceId) {
            throw new ResourceNotFoundException("Person not found with id: " + personId);
        }
        return person;
    }

    private static final class Tally {
        private int metWeight;
        private int totalWeight;
        private int unansweredCount;
        private final List<String> unmetRequiredLabels = new ArrayList<>();
    }
}
