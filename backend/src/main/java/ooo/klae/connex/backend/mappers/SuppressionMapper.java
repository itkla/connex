package ooo.klae.connex.backend.mappers;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.SuppressionEntry;
import ooo.klae.connex.backend.dto.ChannelAddressRef;
import ooo.klae.connex.backend.dto.SuppressionChannelStateRow;

/** Data access for workspace-owned suppression entries. */
public interface SuppressionMapper {
    List<SuppressionEntry> getAll(@Param("workspaceId") int workspaceId);

    /**
     * The channel/reason pairs one contact is suppressed under, matched both by the contact link and
     * by the canonical addresses the channels would reach them at.
     *
     * @param workspaceId the resolved tenant
     * @param personId the contact record id
     * @param addresses the contact's canonical per-channel addresses, possibly empty
     * @return one row per suppressed channel and reason
     */
    List<SuppressionChannelStateRow> findPersonChannelStates(
            @Param("workspaceId") int workspaceId,
            @Param("personId") int personId,
            @Param("addresses") List<ChannelAddressRef> addresses);

    SuppressionEntry getById(@Param("workspaceId") int workspaceId, @Param("id") int id);

    void insert(SuppressionEntry entry);

    int delete(@Param("workspaceId") int workspaceId, @Param("id") int id);

    void clearCreatorsAnywhere(@Param("userId") int userId);
}
