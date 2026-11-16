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

    /** Reads the current subject row under its existing write lock order. */
    ContactChannelConsent getBySubjectForUpdate(
            @Param("workspaceId") int workspaceId, @Param("personId") int personId,
            @Param("channel") String channel, @Param("purpose") String purpose);

    /** Fresh conflict metadata without upgrading a duplicate insert's retained shared index lock. */
    ContactChannelConsent getBySubject(
            @Param("workspaceId") int workspaceId, @Param("personId") int personId,
            @Param("channel") String channel, @Param("purpose") String purpose);

    /** Creates a row only when the unique subject key is absent. */
    void insert(ContactChannelConsent consent);

    /** Atomically replaces exactly the version the member reviewed. */
    int updateIfVersion(@Param("consent") ContactChannelConsent consent,
            @Param("expectedVersion") long expectedVersion);

    void insertEvent(ContactChannelConsentEvent event);

    void clearEventCreatorsAnywhere(@Param("userId") int userId);
}
