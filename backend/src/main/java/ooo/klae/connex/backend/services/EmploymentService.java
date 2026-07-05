package ooo.klae.connex.backend.services;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.PersonEmployment;
import ooo.klae.connex.backend.dto.JobMoveDto;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.PersonEmploymentMapper;

/**
 * Records and reads contact employment history. The write paths ({@code recordInitial} /
 * {@code recordTransition}) are invoked from {@code PersonService} inside the person create/update
 * transaction; the read paths back the per-contact history view and the "recently moved" feed.
 */
@Service
@RequiredArgsConstructor
public class EmploymentService {
    private final PersonEmploymentMapper employmentMapper;
    private final CompanyMapper companyMapper;
    private final WorkspaceService workspaceService;
    private final RuleTriggerPublisher ruleTriggers;
    private final Clock clock;

    private static final int RECENT_MOVE_DAYS = 90;
    private static final int RECENT_MOVE_LIMIT = 20;
    private static final DateTimeFormatter MYSQL_DATETIME =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** Opens the first employment row for a freshly created contact that already has a company. */
    public void recordInitial(int workspaceId, int personId, Integer companyId, String title) {
        insertCurrent(workspaceId, personId, companyId, title, now());
    }

    /**
     * Closes the contact's current employment and, when they moved to a company rather than away from
     * one, opens a new current row. Call only when the contact's company actually changed.
     */
    public void recordTransition(int workspaceId, int personId, Integer newCompanyId, String title) {
        String now = now();
        employmentMapper.closeCurrent(workspaceId, personId, now);
        insertCurrent(workspaceId, personId, newCompanyId, title, now);
        ruleTriggers.publish(workspaceId, "person", personId, "person.job_changed");
    }

    /** Employment history for a contact in the active workspace, current row first. */
    public List<PersonEmployment> getHistory(int personId) {
        return employmentMapper.getByPersonId(workspaceService.getCurrentWorkspaceId(), personId);
    }

    /** Contacts who changed companies within the recent window, newest move first. */
    public List<JobMoveDto> getRecentMoves() {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        String since = LocalDateTime.ofInstant(Instant.now(clock), ZoneOffset.UTC)
            .minusDays(RECENT_MOVE_DAYS).format(MYSQL_DATETIME);
        return employmentMapper.getRecentMoves(workspaceId, since, RECENT_MOVE_LIMIT);
    }

    private void insertCurrent(int workspaceId, int personId, Integer companyId, String title, String startedAt) {
        if (companyId == null || companyId == 0) return;
        Company company = companyMapper.getCompanyById(workspaceId, companyId);
        PersonEmployment employment = new PersonEmployment();
        employment.setWorkspaceId(workspaceId);
        employment.setPersonId(personId);
        employment.setCompanyId(companyId);
        employment.setCompanyName(company == null ? null : company.getName());
        employment.setTitle(title);
        employment.setStartedAt(startedAt);
        employmentMapper.insert(employment);
    }

    private String now() {
        return LocalDateTime.ofInstant(Instant.now(clock), ZoneOffset.UTC).format(MYSQL_DATETIME);
    }
}
