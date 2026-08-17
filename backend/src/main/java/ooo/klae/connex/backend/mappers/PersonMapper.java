package ooo.klae.connex.backend.mappers;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.PersonDisqualificationReason;
import ooo.klae.connex.backend.beans.PersonLeadSource;
import ooo.klae.connex.backend.beans.PersonLifecycleStage;
import ooo.klae.connex.backend.dto.CompanyEngagementPersonDto;
import ooo.klae.connex.backend.dto.FacetCount;
import ooo.klae.connex.backend.dto.MemberScope;
import ooo.klae.connex.backend.dto.RelationshipEvidenceRowDto;
import ooo.klae.connex.backend.dto.RelationshipEvidenceTotalsDto;
import ooo.klae.connex.backend.dto.RelationshipScoreAggregateDto;
import ooo.klae.connex.backend.warmth.RelationshipWarmthModel.SqlParameters;

/**
 * Mapper interface for {@code Person} persistence.
 * SQL is defined in {@code resources/mappers/PersonMapper.xml}.
 * Used by {@code PersonService}.
 */

public interface PersonMapper {
    List<Person> getAllPersons(int workspaceId);
    List<Person> getProcessablePersons(int workspaceId);
    List<Person> getPersonsForNetworkReport(
            @Param("workspaceId") int workspaceId,
            @Param("limit") int limit);
    List<RelationshipScoreAggregateDto> getRelationshipScoreAggregates(
            @Param("workspaceId") int workspaceId,
            @Param("reference") LocalDateTime reference,
            @Param("model") SqlParameters model);
    List<RelationshipScoreAggregateDto> getRelationshipScoreAggregatesExcludingHistoryImports(
            @Param("workspaceId") int workspaceId,
            @Param("reference") LocalDateTime reference,
            @Param("model") SqlParameters model,
            @Param("excludedActivityIds") List<Integer> excludedActivityIds,
            @Param("excludedNoteIds") List<Integer> excludedNoteIds,
            @Param("excludedTaskIds") List<Integer> excludedTaskIds);
    RelationshipEvidenceTotalsDto getRelationshipEvidenceTotals(
            @Param("workspaceId") int workspaceId,
            @Param("personId") int personId,
            @Param("reference") LocalDateTime reference,
            @Param("model") SqlParameters model,
            @Param("sourceLimit") int sourceLimit);
    List<RelationshipEvidenceRowDto> getRelationshipEvidenceContributors(
            @Param("workspaceId") int workspaceId,
            @Param("personId") int personId,
            @Param("reference") LocalDateTime reference,
            @Param("model") SqlParameters model,
            @Param("sourceLimit") int sourceLimit,
            @Param("limit") int limit);
    List<Person> getPersonsByCompanyId(@Param("workspaceId") int workspaceId,
            @Param("companyId") int companyId, @Param("limit") Integer limit);
    List<CompanyEngagementPersonDto> getCompanyEngagementPeople(@Param("workspaceId") int workspaceId,
            @Param("companyId") int companyId, @Param("limit") int limit);
    List<Person> getPersonsByTagId(@Param("workspaceId") int workspaceId, @Param("tagId") int tagId);
    List<Person> getPersonsByDealId(@Param("workspaceId") int workspaceId, @Param("dealId") int dealId);
    Person getPersonById(@Param("workspaceId") int workspaceId, @Param("id") int id);
    Person getVisiblePersonByIdForUpdate(@Param("workspaceId") int workspaceId, @Param("id") int id);
    Person getOwnedPersonByIdForUpdate(@Param("workspaceId") int workspaceId, @Param("id") int id);
    /** The owned contact only when it is archived; the restore path's pre-image read. */
    Person getOwnedArchivedPersonById(@Param("workspaceId") int workspaceId, @Param("id") int id);
    List<Integer> getProcessablePersonIds(@Param("workspaceId") int workspaceId,
            @Param("ids") List<Integer> ids);
    List<Person> getByIds(@Param("workspaceId") int workspaceId, @Param("ids") List<Integer> ids);
    List<Person> getPersonsByCompanyIds(@Param("workspaceId") int workspaceId,
            @Param("companyIds") List<Integer> companyIds);
    boolean exists(@Param("workspaceId") int workspaceId, @Param("id") int id);
    /**
     * True only when the workspace OWNS an ACTIVE contact (excludes records merely shared in and
     * records that have been archived); for write scoping.
     */
    boolean existsOwned(@Param("workspaceId") int workspaceId, @Param("id") int id);
    /** True only when the workspace owns the contact AND it is archived; for restore write scoping. */
    boolean existsOwnedArchived(@Param("workspaceId") int workspaceId, @Param("id") int id);
    Integer lockById(@Param("workspaceId") int workspaceId, @Param("id") int id);
    List<Person> findMentionedRecords(
            @Param("workspaceId") int workspaceId,
            @Param("text") String text,
            @Param("limit") int limit);
    List<Person> search(@Param("workspaceId") int workspaceId, @Param("query") String query);
    /** Existing contacts in the workspace whose email matches one of the given (normalized) emails; for import dedup. */
    List<Person> findByEmails(@Param("workspaceId") int workspaceId, @Param("emails") List<String> emails);
    /** One page of the browser list; {@code archived} selects the archived set instead of the active one. */
    List<Person> getPersonsPage(@Param("workspaceId") int workspaceId, @Param("query") String query,
            @Param("sort") String sort, @Param("dir") String dir,
            @Param("companies") List<String> companies, @Param("titles") List<String> titles,
            @Param("noCompany") boolean noCompany, @Param("memberScope") MemberScope memberScope,
            @Param("lifecycleStages") List<PersonLifecycleStage> lifecycleStages,
            @Param("noLifecycle") boolean noLifecycle,
            @Param("leadSources") List<PersonLeadSource> leadSources,
            @Param("noLeadSource") boolean noLeadSource,
            @Param("archived") boolean archived,
            @Param("limit") int limit, @Param("offset") int offset);
    long countPersons(@Param("workspaceId") int workspaceId, @Param("query") String query,
            @Param("companies") List<String> companies,
            @Param("titles") List<String> titles, @Param("noCompany") boolean noCompany,
            @Param("memberScope") MemberScope memberScope,
            @Param("lifecycleStages") List<PersonLifecycleStage> lifecycleStages,
            @Param("noLifecycle") boolean noLifecycle,
            @Param("leadSources") List<PersonLeadSource> leadSources,
            @Param("noLeadSource") boolean noLeadSource,
            @Param("archived") boolean archived);
    /**
     * CSV export using the browser filters and member scope, excluding suspended contacts.
     * Callers pass {@code archived = false}: an export is defined as the active working set.
     */
    List<Person> getPersonsFiltered(@Param("workspaceId") int workspaceId, @Param("query") String query,
            @Param("companies") List<String> companies, @Param("titles") List<String> titles,
            @Param("noCompany") boolean noCompany, @Param("memberScope") MemberScope memberScope,
            @Param("lifecycleStages") List<PersonLifecycleStage> lifecycleStages,
            @Param("noLifecycle") boolean noLifecycle,
            @Param("leadSources") List<PersonLeadSource> leadSources,
            @Param("noLeadSource") boolean noLeadSource,
            @Param("archived") boolean archived);
    /** Ids using the browser's filters and member scope; backs "select all matching". */
    List<Integer> getPersonIdsFiltered(@Param("workspaceId") int workspaceId, @Param("query") String query,
            @Param("companies") List<String> companies, @Param("titles") List<String> titles,
            @Param("noCompany") boolean noCompany, @Param("memberScope") MemberScope memberScope,
            @Param("lifecycleStages") List<PersonLifecycleStage> lifecycleStages,
            @Param("noLifecycle") boolean noLifecycle,
            @Param("leadSources") List<PersonLeadSource> leadSources,
            @Param("noLeadSource") boolean noLeadSource,
            @Param("archived") boolean archived, @Param("limit") int limit);
    List<String> distinctCompanies(int workspaceId);
    List<String> distinctTitles(int workspaceId);
    boolean hasPersonWithoutCompany(int workspaceId);
    List<FacetCount> countsByOwner(@Param("workspaceId") int workspaceId);
    /**
     * How many active contacts sit in each lead-lifecycle stage, matching the page filter's
     * population (suspended contacts stay administratively visible in both). Contacts outside the
     * lifecycle are counted under the {@code __none__} key so the browser can offer them as a bucket.
     *
     * @param workspaceId owning workspace
     * @return one bucket per stage
     */
    List<FacetCount> countsByLifecycleStage(@Param("workspaceId") int workspaceId);
    /**
     * How many active contacts entered through each lead source, matching the page filter's
     * population. Contacts with no captured provenance are counted under the {@code __none__} key.
     *
     * @param workspaceId owning workspace
     * @return one bucket per source
     */
    List<FacetCount> countsByLeadSource(@Param("workspaceId") int workspaceId);
    /** How many contacts the workspace currently holds archived; drives the browser's archived toggle. */
    long countArchivedPersons(@Param("workspaceId") int workspaceId);
    /** Ids of contacts the team has engaged (has any activity, note, or task), used as warm-intro entry points. */
    List<Integer> getEngagedPersonIds(int workspaceId);
    int insert(Person person);
    /** Bulk-insert contacts in one statement (CSV import); generated ids are written back to each bean. */
    int insertBatch(List<Person> persons);
    int update(Person person);
    int updateOwner(
        @Param("workspaceId") int workspaceId,
        @Param("id") int id,
        @Param("ownerId") Integer ownerId);
    int updateImageUrlIfCurrent(
        @Param("workspaceId") int workspaceId,
        @Param("id") int id,
        @Param("currentImageUrl") String currentImageUrl,
        @Param("imageUrl") String imageUrl);
    /** Targeted update of the engine-evaluation opt-outs; a {@code null} flag is left unchanged. */
    int updateEvaluationExclusions(
        @Param("workspaceId") int workspaceId,
        @Param("id") int id,
        @Param("riskExcluded") Boolean riskExcluded,
        @Param("introExcluded") Boolean introExcluded
    );
    /**
     * Replaces the whole lead-lifecycle state of one contact. Every field is written unconditionally
     * so that leaving a stage always clears the reason and notes that belonged to it; the previous
     * values remain readable in {@code person_lifecycle_history}.
     *
     * @param workspaceId owning workspace
     * @param id contact id
     * @param stage new stage, or {@code null} to withdraw the contact from the lifecycle
     * @param changedAt transition timestamp; withdrawal is a transition and carries one too
     * @param reason disqualification reason, permitted only alongside {@code DISQUALIFIED}
     * @param notes qualification notes
     * @return rows updated
     */
    int updateLifecycle(
        @Param("workspaceId") int workspaceId,
        @Param("id") int id,
        @Param("stage") PersonLifecycleStage stage,
        @Param("changedAt") LocalDateTime changedAt,
        @Param("reason") PersonDisqualificationReason reason,
        @Param("notes") String notes
    );
    /**
     * Replaces the whole source-provenance state of one contact. Every field is written
     * unconditionally so a correction can also clear a value; the previous values remain in the
     * audit log.
     *
     * @param workspaceId owning workspace
     * @param id contact id
     * @param source new source, or {@code null} when provenance is unknown
     * @param detail free-text source detail, permitted only alongside a source
     * @param referrerPersonId referring contact, permitted only for referral or partner sources
     * @return rows updated
     */
    int updateProvenance(
        @Param("workspaceId") int workspaceId,
        @Param("id") int id,
        @Param("source") PersonLeadSource source,
        @Param("detail") String detail,
        @Param("referrerPersonId") Integer referrerPersonId
    );
    int updateProcessingRestrictions(
        @Param("workspaceId") int workspaceId,
        @Param("id") int id,
        @Param("suspended") boolean suspended,
        @Param("provisionCeased") boolean provisionCeased
    );
    /**
     * Archives an active contact. There is deliberately no hard-delete statement: archiving replaced
     * it in #854 so no cascade can destroy employment, edges, identities, or the append-only
     * consent history. Whole-workspace erasure remains {@code TenantLifecycleMapper}'s job.
     *
     * @return 1 when an active owned row was archived, 0 otherwise
     */
    int archive(@Param("workspaceId") int workspaceId, @Param("id") int id);

    /**
     * Clears the archive tombstone, returning the contact to the active working set.
     *
     * @return 1 when an archived owned row was restored, 0 otherwise
     */
    int restore(@Param("workspaceId") int workspaceId, @Param("id") int id);

    /** Clears contact ownership held by one member within one workspace. */
    void clearMemberOwnership(@Param("workspaceId") int workspaceId, @Param("userId") int userId);

    /** Clears contact ownership held by a user across every workspace. */
    void clearOwnershipAnywhere(@Param("userId") int userId);

    int addTag(@Param("workspaceId") int workspaceId, @Param("personId") int personId, @Param("tagId") int tagId);
    int removeTag(@Param("workspaceId") int workspaceId, @Param("personId") int personId, @Param("tagId") int tagId);
    int clearTags(@Param("workspaceId") int workspaceId, @Param("personId") int personId);
    int insertTags(@Param("workspaceId") int workspaceId, @Param("personId") int personId, @Param("tagIds") List<Integer> tagIds);
}
