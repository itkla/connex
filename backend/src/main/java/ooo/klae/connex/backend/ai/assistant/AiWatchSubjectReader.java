package ooo.klae.connex.backend.ai.assistant;

import java.util.Optional;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.services.WorkspaceService;

/**
 * Resolves the record a watch is about, under the caller's own resolved workspace scope.
 *
 * <p>The same resolution runs at creation and again at every evaluation, so a watch on a record that
 * is later archived, restricted, or deleted simply stops resolving and therefore stops firing. That
 * is the whole eligibility reconciliation: nothing about the subject is cached on the watch row
 * except its identifier, so there is no stale copy of a name or a state to leak after access ends.
 */
@Service
@RequiredArgsConstructor
public class AiWatchSubjectReader {

    private final PersonMapper personMapper;
    private final CompanyMapper companyMapper;
    private final DealMapper dealMapper;
    private final WorkspaceService workspaceService;

    /**
     * Returns the readable display name of one watched record.
     *
     * @param subjectKind {@code person}, {@code company}, or {@code deal}
     * @param subjectId tenant-local record identifier
     * @return the record's name, or empty when it is absent, archived, or not processable
     */
    public Optional<String> label(String subjectKind, int subjectId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        return switch (subjectKind) {
            case "person" -> person(workspaceId, subjectId);
            case "company" -> company(workspaceId, subjectId);
            case "deal" -> deal(workspaceId, subjectId);
            default -> Optional.empty();
        };
    }

    private Optional<String> person(int workspaceId, int subjectId) {
        Person person = personMapper.getPersonById(workspaceId, subjectId);
        if (person == null || person.getSuspendedAt() != null
                || person.getProvisionCeasedAt() != null || person.getArchivedAt() != null
                || person.getName() == null || person.getName().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(person.getName());
    }

    private Optional<String> company(int workspaceId, int subjectId) {
        Company company = companyMapper.getCompanyById(workspaceId, subjectId);
        if (company == null || company.getArchivedAt() != null
                || company.getName() == null || company.getName().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(company.getName());
    }

    private Optional<String> deal(int workspaceId, int subjectId) {
        Deal deal = dealMapper.getDealById(workspaceId, subjectId);
        if (deal == null || deal.getName() == null || deal.getName().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(deal.getName());
    }
}
