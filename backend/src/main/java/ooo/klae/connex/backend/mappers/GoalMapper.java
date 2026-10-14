package ooo.klae.connex.backend.mappers;

import java.time.LocalDate;
import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.ReportGoal;

/**
 * Data access for report goals. Workspace operations bind {@code workspaceId}; identity-scoped
 * account erasure discovers references throughout its pinned catalog. Owner labels are hydrated
 * in the service.
 */
public interface GoalMapper {
    List<ReportGoal> getGoals(@Param("workspaceId") int workspaceId);

    ReportGoal getGoal(@Param("workspaceId") int workspaceId, @Param("id") int id);

    List<ReportGoal> getGoalsForPeriod(
            @Param("workspaceId") int workspaceId,
            @Param("metric") String metric,
            @Param("periodType") String periodType,
            @Param("periodStart") LocalDate periodStart);

    void insert(ReportGoal goal);

    int update(ReportGoal goal);

    int delete(@Param("workspaceId") int workspaceId, @Param("id") int id);

    /** Fresh, non-locking discovery of personal goals in a departing member's workspace. */
    List<ReportGoal> findDepartureReferences(@Param("workspaceId") int workspaceId,
        @Param("userId") int userId);

    /** Fresh, non-locking discovery of owner and creator references in this catalog. */
    List<ReportGoal> findDepartureReferencesAnywhere(@Param("userId") int userId);

    /** Deletes only the discovered personal goal if its owner still matches. */
    int deleteDepartingOwner(@Param("workspaceId") int workspaceId, @Param("id") int id,
        @Param("userId") int userId);

    /** Identity-scoped catalog-erasure twin; retains the exact workspace, row, and user predicate. */
    int deleteDepartingOwnerAnywhere(@Param("workspaceId") int workspaceId, @Param("id") int id,
        @Param("userId") int userId);

    /** Clears the exact creator reference during identity-scoped catalog erasure. */
    int clearDepartingCreatorAnywhere(@Param("workspaceId") int workspaceId, @Param("id") int id,
        @Param("userId") int userId);
}
