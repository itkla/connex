package ooo.klae.connex.backend.mappers;

import java.util.List;

import org.apache.ibatis.annotations.Param;

/** Catalog-pinned maintenance access to assignment references, without control-plane joins. */
public interface AssignmentSweepMapper {
    /** Fixed SQL branches for the references covered by post-deletion recovery. */
    enum Kind {
        COMPANY, PERSON, DEAL, COLLABORATOR, TASK, INTERVENTION, CAMPAIGN, LIFECYCLE_PASS
    }

    /** Scalar traversal id, or the complete collaborator primary-key tuple. */
    record Cursor(long id, int workspaceId, long parentId, int userId) {
        public static Cursor beginning() {
            return new Cursor(0, 0, 0, 0);
        }
    }

    /** Numeric discovery only; parentId is the person or deal whose root precedes child mutation. */
    record Reference(long id, int workspaceId, long parentId, int userId) {
        public Cursor cursor() {
            return new Cursor(id, workspaceId, parentId, userId);
        }
    }

    Cursor findWatermark(@Param("kind") Kind kind);

    List<Reference> findReferencesAnywhere(
        @Param("kind") Kind kind,
        @Param("userId") Integer userId,
        @Param("cursor") Cursor cursor,
        @Param("watermark") Cursor watermark,
        @Param("limit") int limit);

    /** Acquires only the exact PRIMARY parent row, before any child or secondary-index mutation. */
    Long lockParent(@Param("kind") Kind kind, @Param("ref") Reference reference);

    int clearOwner(@Param("kind") Kind kind, @Param("ref") Reference reference);
    int removeCollaborator(@Param("ref") Reference reference);
    int unassignTask(@Param("ref") Reference reference);
    int clearInterventionOwner(@Param("ref") Reference reference);
    int clearCampaignOwner(@Param("ref") Reference reference);
    int clearPersonPassOwners(@Param("ref") Reference reference);
}
