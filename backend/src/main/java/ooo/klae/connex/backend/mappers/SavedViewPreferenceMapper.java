package ooo.klae.connex.backend.mappers;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.SavedView;
import ooo.klae.connex.backend.beans.SavedViewDefault;
import ooo.klae.connex.backend.beans.SavedViewPin;

/** Workspace-scoped persistence for per-user saved-view pins and defaults. */
public interface SavedViewPreferenceMapper {
    List<SavedView> getAccessiblePins(
        @Param("workspaceId") int workspaceId,
        @Param("userId") int userId);

    SavedViewPin getPin(
        @Param("workspaceId") int workspaceId,
        @Param("userId") int userId,
        @Param("savedViewId") int savedViewId);

    int maxPinPosition(@Param("workspaceId") int workspaceId, @Param("userId") int userId);

    void resequencePins(@Param("workspaceId") int workspaceId, @Param("userId") int userId);

    void insertPin(
        @Param("workspaceId") int workspaceId,
        @Param("userId") int userId,
        @Param("savedViewId") int savedViewId,
        @Param("position") int position);

    void updatePinPosition(
        @Param("workspaceId") int workspaceId,
        @Param("userId") int userId,
        @Param("savedViewId") int savedViewId,
        @Param("position") int position);

    void deletePin(
        @Param("workspaceId") int workspaceId,
        @Param("userId") int userId,
        @Param("savedViewId") int savedViewId);

    SavedView getAccessibleDefault(
        @Param("workspaceId") int workspaceId,
        @Param("userId") int userId,
        @Param("recordType") String recordType);

    SavedViewDefault getDefault(
        @Param("workspaceId") int workspaceId,
        @Param("userId") int userId,
        @Param("recordType") String recordType);

    void upsertDefault(
        @Param("workspaceId") int workspaceId,
        @Param("userId") int userId,
        @Param("recordType") String recordType,
        @Param("savedViewId") int savedViewId);

    void deleteDefault(
        @Param("workspaceId") int workspaceId,
        @Param("userId") int userId,
        @Param("recordType") String recordType);

    void deleteNonOwnerPinsForView(
        @Param("workspaceId") int workspaceId,
        @Param("savedViewId") int savedViewId,
        @Param("ownerUserId") int ownerUserId);

    void deleteNonOwnerDefaultsForView(
        @Param("workspaceId") int workspaceId,
        @Param("savedViewId") int savedViewId,
        @Param("ownerUserId") int ownerUserId);

    void deletePinsForUser(@Param("workspaceId") int workspaceId, @Param("userId") int userId);

    void deleteDefaultsForUser(@Param("workspaceId") int workspaceId, @Param("userId") int userId);

    /** Deletes a user's pin preferences across every workspace during account erasure. */
    void deletePinsForUserAnywhere(@Param("userId") int userId);

    /** Deletes a user's default preferences across every workspace during account erasure. */
    void deleteDefaultsForUserAnywhere(@Param("userId") int userId);
}
