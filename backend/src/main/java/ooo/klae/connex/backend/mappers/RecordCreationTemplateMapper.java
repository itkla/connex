package ooo.klae.connex.backend.mappers;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.RecordCreationTemplate;
import ooo.klae.connex.backend.beans.RecordCreationTemplateSet;
import ooo.klae.connex.backend.beans.RecordCreationTemplateVersion;

public interface RecordCreationTemplateMapper {
    void insertSetIfAbsent(
        @Param("workspaceId") int workspaceId,
        @Param("recordType") String recordType);

    RecordCreationTemplateSet getSet(
        @Param("workspaceId") int workspaceId,
        @Param("recordType") String recordType);

    RecordCreationTemplateSet getSetForUpdate(
        @Param("workspaceId") int workspaceId,
        @Param("recordType") String recordType);

    List<RecordCreationTemplate> listRoots(
        @Param("workspaceId") int workspaceId,
        @Param("recordType") String recordType,
        @Param("includeArchived") boolean includeArchived);

    List<RecordCreationTemplate> listRootsForUpdate(
        @Param("workspaceId") int workspaceId,
        @Param("recordType") String recordType);

    RecordCreationTemplate getRoot(
        @Param("workspaceId") int workspaceId,
        @Param("templateId") int templateId);

    RecordCreationTemplate getRootForUpdate(
        @Param("workspaceId") int workspaceId,
        @Param("templateId") int templateId);

    RecordCreationTemplateVersion getCurrentVersion(
        @Param("workspaceId") int workspaceId,
        @Param("templateId") int templateId);

    RecordCreationTemplateVersion getVersion(
        @Param("workspaceId") int workspaceId,
        @Param("templateId") int templateId,
        @Param("versionNumber") int versionNumber);

    int nextVersionNumber(
        @Param("workspaceId") int workspaceId,
        @Param("templateId") int templateId);

    void insertRoot(RecordCreationTemplate template);

    void insertVersion(RecordCreationTemplateVersion version);

    int installCurrentVersion(
        @Param("workspaceId") int workspaceId,
        @Param("templateId") int templateId,
        @Param("versionId") long versionId,
        @Param("expectedRevision") int expectedRevision,
        @Param("actorId") int actorId);

    int updateStatus(
        @Param("workspaceId") int workspaceId,
        @Param("templateId") int templateId,
        @Param("status") String status,
        @Param("archivedAt") LocalDateTime archivedAt,
        @Param("expectedRevision") int expectedRevision,
        @Param("actorId") int actorId);

    int updatePositions(
        @Param("workspaceId") int workspaceId,
        @Param("templateId") int templateId,
        @Param("position") int position,
        @Param("actorId") int actorId);

    int setDefault(
        @Param("workspaceId") int workspaceId,
        @Param("recordType") String recordType,
        @Param("templateId") Integer templateId,
        @Param("expectedRevision") int expectedRevision);

    int advanceSetRevision(
        @Param("workspaceId") int workspaceId,
        @Param("recordType") String recordType,
        @Param("expectedRevision") int expectedRevision);

    int clearUserReferencesAnywhere(@Param("userId") int userId);
}
