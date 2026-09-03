package ooo.klae.connex.backend.mappers;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.SequenceVersion;

/** Data access for immutable published sequence versions. */
public interface SequenceVersionMapper {
    /** Locks the current version range and returns the next number while the sequence root is held. */
    int nextVersionNumberForUpdate(
            @Param("workspaceId") int workspaceId,
            @Param("sequenceId") int sequenceId);

    /** Inserts one immutable canonical definition row. */
    int insertVersion(SequenceVersion version);

    /** Inserts the separately mutable publisher attribution for an immutable version. */
    int insertVersionPublisher(
            @Param("workspaceId") int workspaceId,
            @Param("versionId") long versionId,
            @Param("publishedById") int publishedById);

    /** Lists immutable versions and their current publisher attributions. */
    List<SequenceVersion> getVersions(
            @Param("workspaceId") int workspaceId,
            @Param("sequenceId") int sequenceId);

    /** Returns one immutable version and its current publisher attribution. */
    SequenceVersion getVersion(
            @Param("workspaceId") int workspaceId,
            @Param("sequenceId") int sequenceId,
            @Param("versionNumber") int versionNumber);
}
