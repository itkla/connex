package ooo.klae.connex.backend.mappers;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * Resolves shared session identities and atomically consumes validated provider authorization state.
 *
 * <p>Spring Session separates the immutable {@code PRIMARY_ID} from the {@code SESSION_ID} a
 * client presents, because session-fixation rotation replaces the latter while keeping the same
 * physical row and its attributes. Anything that must name one session across a rotation has to
 * name the primary id.
 */
@Mapper
public interface SpringSessionMapper {
    /**
     * The stable row identity behind a presented session id.
     *
     * @param sessionId the logical session id a request carries
     * @return the owning row's primary id, or null when no live row carries that session id
     */
    String primaryIdBySessionId(String sessionId);

    /** Atomically consumes the exact validated provider state without replacing a newer flow. */
    int consumeProviderConnectionState(
        @Param("sessionId") String sessionId,
        @Param("expectedState") byte[] expectedState,
        @Param("consumedState") byte[] consumedState);
}
