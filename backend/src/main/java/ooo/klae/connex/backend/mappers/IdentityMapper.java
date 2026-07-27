package ooo.klae.connex.backend.mappers;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.CompanyIdentityBackfillCandidate;
import ooo.klae.connex.backend.beans.IdentityKeyRow;
import ooo.klae.connex.backend.beans.PersonIdentityBackfillCandidate;

/**
 * Workspace-scoped canonical identity persistence and backfill reads.
 */
public interface IdentityMapper {

    List<PersonIdentityBackfillCandidate> findPersonBackfillCandidates(
        @Param("workspaceId") int workspaceId,
        @Param("afterId") int afterId,
        @Param("limit") int limit);

    List<CompanyIdentityBackfillCandidate> findCompanyBackfillCandidates(
        @Param("workspaceId") int workspaceId,
        @Param("afterId") int afterId,
        @Param("limit") int limit);

    List<IdentityKeyRow> findPersonIdentityKeys(
        @Param("workspaceId") int workspaceId,
        @Param("recordIds") List<Integer> recordIds);

    List<IdentityKeyRow> findCompanyIdentityKeys(
        @Param("workspaceId") int workspaceId,
        @Param("recordIds") List<Integer> recordIds);

    int insertBackfilledPersonEmailIfAbsent(
        @Param("workspaceId") int workspaceId,
        @Param("personId") int personId,
        @Param("rawValue") String rawValue,
        @Param("normalizedValue") String normalizedValue);

    int insertBackfilledPersonPhoneIfAbsent(
        @Param("workspaceId") int workspaceId,
        @Param("personId") int personId,
        @Param("rawValue") String rawValue,
        @Param("normalizedValue") String normalizedValue);

    int insertBackfilledCompanyDomainIfAbsent(
        @Param("workspaceId") int workspaceId,
        @Param("companyId") int companyId,
        @Param("rawValue") String rawValue,
        @Param("normalizedValue") String normalizedValue);

    int deletePersonIdentitiesForWorkspace(@Param("workspaceId") int workspaceId);

    int deleteCompanyIdentitiesForWorkspace(@Param("workspaceId") int workspaceId);
}
