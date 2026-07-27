package ooo.klae.connex.backend.mappers;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.CompanyIdentityBackfillCandidate;
import ooo.klae.connex.backend.beans.IdentityKeyRow;
import ooo.klae.connex.backend.beans.PersonIdentityBackfillCandidate;
import ooo.klae.connex.backend.dto.DuplicateCandidateRow;
import ooo.klae.connex.backend.dto.DuplicateIdentityKey;
import ooo.klae.connex.backend.dto.DuplicateNameKey;

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

    List<DuplicateCandidateRow> findVisiblePersonIdentityMatches(
        @Param("workspaceId") int workspaceId,
        @Param("orgWorkspaceIdsJson") String orgWorkspaceIdsJson,
        @Param("keys") List<DuplicateIdentityKey> keys,
        @Param("perKeyLimit") int perKeyLimit);

    List<DuplicateCandidateRow> findVisibleCompanyIdentityMatches(
        @Param("workspaceId") int workspaceId,
        @Param("orgWorkspaceIdsJson") String orgWorkspaceIdsJson,
        @Param("keys") List<DuplicateIdentityKey> keys,
        @Param("perKeyLimit") int perKeyLimit);

    List<DuplicateCandidateRow> findVisiblePersonNameMatches(
        @Param("workspaceId") int workspaceId,
        @Param("orgWorkspaceIdsJson") String orgWorkspaceIdsJson,
        @Param("keys") List<DuplicateNameKey> keys,
        @Param("perKeyLimit") int perKeyLimit);

    List<DuplicateCandidateRow> findVisibleCompanyNameMatches(
        @Param("workspaceId") int workspaceId,
        @Param("orgWorkspaceIdsJson") String orgWorkspaceIdsJson,
        @Param("keys") List<DuplicateNameKey> keys,
        @Param("perKeyLimit") int perKeyLimit);

    int updatePersonNormalizedName(
        @Param("workspaceId") int workspaceId,
        @Param("personId") int personId,
        @Param("rawName") String rawName,
        @Param("normalizedName") String normalizedName);

    int updateCompanyNormalizedName(
        @Param("workspaceId") int workspaceId,
        @Param("companyId") int companyId,
        @Param("rawName") String rawName,
        @Param("normalizedName") String normalizedName);

    PersonIdentityBackfillCandidate lockPersonIdentityParent(
        @Param("workspaceId") int workspaceId,
        @Param("personId") int personId);

    CompanyIdentityBackfillCandidate lockCompanyIdentityParent(
        @Param("workspaceId") int workspaceId,
        @Param("companyId") int companyId);

    int supersedePersonEmailIdentities(
        @Param("workspaceId") int workspaceId,
        @Param("personId") int personId,
        @Param("rawValue") String rawValue,
        @Param("normalizedValue") String normalizedValue,
        @Param("supersededAt") LocalDateTime supersededAt);

    int supersedePersonPhoneIdentities(
        @Param("workspaceId") int workspaceId,
        @Param("personId") int personId,
        @Param("rawValue") String rawValue,
        @Param("normalizedValue") String normalizedValue,
        @Param("supersededAt") LocalDateTime supersededAt);

    int supersedeCompanyDomainIdentities(
        @Param("workspaceId") int workspaceId,
        @Param("companyId") int companyId,
        @Param("rawValue") String rawValue,
        @Param("normalizedValue") String normalizedValue,
        @Param("supersededAt") LocalDateTime supersededAt);

    int supersedeCompanyPhoneIdentities(
        @Param("workspaceId") int workspaceId,
        @Param("companyId") int companyId,
        @Param("rawValue") String rawValue,
        @Param("normalizedValue") String normalizedValue,
        @Param("supersededAt") LocalDateTime supersededAt);

    int upsertPersonEmailIdentity(
        @Param("workspaceId") int workspaceId,
        @Param("personId") int personId,
        @Param("rawValue") String rawValue,
        @Param("normalizedValue") String normalizedValue,
        @Param("sourceSystem") String sourceSystem,
        @Param("sourceRowRef") String sourceRowRef,
        @Param("acquiredAt") LocalDateTime acquiredAt);

    int upsertPersonPhoneIdentity(
        @Param("workspaceId") int workspaceId,
        @Param("personId") int personId,
        @Param("rawValue") String rawValue,
        @Param("normalizedValue") String normalizedValue,
        @Param("sourceSystem") String sourceSystem,
        @Param("sourceRowRef") String sourceRowRef,
        @Param("acquiredAt") LocalDateTime acquiredAt);

    int upsertCompanyDomainIdentity(
        @Param("workspaceId") int workspaceId,
        @Param("companyId") int companyId,
        @Param("rawValue") String rawValue,
        @Param("normalizedValue") String normalizedValue,
        @Param("sourceSystem") String sourceSystem,
        @Param("sourceRowRef") String sourceRowRef,
        @Param("acquiredAt") LocalDateTime acquiredAt);

    int upsertCompanyPhoneIdentity(
        @Param("workspaceId") int workspaceId,
        @Param("companyId") int companyId,
        @Param("rawValue") String rawValue,
        @Param("normalizedValue") String normalizedValue,
        @Param("sourceSystem") String sourceSystem,
        @Param("sourceRowRef") String sourceRowRef,
        @Param("acquiredAt") LocalDateTime acquiredAt);

    int deletePersonIdentitiesForWorkspace(@Param("workspaceId") int workspaceId);

    int deleteCompanyIdentitiesForWorkspace(@Param("workspaceId") int workspaceId);
}
