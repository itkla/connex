package ooo.klae.connex.backend.mappers;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.ContactChannelConsent;
import ooo.klae.connex.backend.beans.ContactChannelConsentEvent;

/** Data access for current contact consent and its append-only history. */
public interface ConsentMapper {
    List<ContactChannelConsent> getForPerson(
            @Param("workspaceId") int workspaceId,
            @Param("personId") int personId);

    void upsert(ContactChannelConsent consent);

    void insertEvent(ContactChannelConsentEvent event);

    void clearEventCreatorsAnywhere(@Param("userId") int userId);
}
