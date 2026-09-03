package ooo.klae.connex.backend.mappers;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.CampaignMessage;
import ooo.klae.connex.backend.beans.CampaignMessageRevision;

/** Data access for workspace-scoped campaign messages and their immutable revisions. */
public interface CampaignMessageMapper {

    List<CampaignMessage> getMessages(
            @Param("workspaceId") int workspaceId,
            @Param("campaignId") int campaignId);

    CampaignMessage getMessage(@Param("workspaceId") int workspaceId, @Param("id") int id);

    /** Returns a current campaign message under a shared lock. */
    CampaignMessage getMessageForShare(@Param("workspaceId") int workspaceId, @Param("id") int id);

    void insertMessage(CampaignMessage message);

    int updateMessage(CampaignMessage message);

    int nextRevisionVersion(@Param("workspaceId") int workspaceId, @Param("messageId") int messageId);

    void insertRevision(CampaignMessageRevision revision);

    List<CampaignMessageRevision> getRevisions(
            @Param("workspaceId") int workspaceId,
            @Param("messageId") int messageId);

    CampaignMessageRevision getRevision(
            @Param("workspaceId") int workspaceId,
            @Param("messageId") int messageId,
            @Param("version") int version);

    /** Returns a current immutable message revision under a shared lock. */
    CampaignMessageRevision getRevisionForShare(
            @Param("workspaceId") int workspaceId,
            @Param("messageId") int messageId,
            @Param("version") int version);
}
