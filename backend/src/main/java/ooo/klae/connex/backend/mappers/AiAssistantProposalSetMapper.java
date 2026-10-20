package ooo.klae.connex.backend.mappers;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.AiAssistantProposalSet;
import ooo.klae.connex.backend.beans.AiChatToolCall;

/**
 * Dormant tenant storage primitives; no production call creates proposal sets in this slice.
 * A future authorized creator must insert the parent and every child in one transaction,
 * verify complete membership, and retain the session mutex. These statements grant no authority.
 * Identity, invocation mode, membership and creation time have no update operation.
 */
@Mapper
public interface AiAssistantProposalSetMapper {

    /** Inserts a staged parent only for a user message and turn with the same session and requester. */
    int insert(AiAssistantProposalSet proposalSet);

    /**
     * Inserts one trusted staged member with its parent's original creation time.
     * Returns zero for a missing parent, invalid ordinal, tool, requester or preparation anchor.
     * The caller must roll back the entire creation transaction unless every insert returns one.
     */
    int insertMember(AiChatToolCall toolCall);

    /** Returns the exact parent, or null when its workspace/session/key does not match. */
    AiAssistantProposalSet findByKey(
            @Param("workspaceId") int workspaceId,
            @Param("sessionId") int sessionId,
            @Param("proposalKey") String proposalKey,
            @Param("organizationPrivacyGeneration") long organizationPrivacyGeneration);

    /** Locks the exact parent before any proposal child or originating turn is acquired. */
    AiAssistantProposalSet lockByKey(
            @Param("workspaceId") int workspaceId,
            @Param("sessionId") int sessionId,
            @Param("proposalKey") String proposalKey,
            @Param("organizationPrivacyGeneration") long organizationPrivacyGeneration);

    /** Returns the original invocation, including staged or failed calls, or null when absent. */
    AiAssistantProposalSet findByCallIdentity(
            @Param("workspaceId") int workspaceId,
            @Param("sessionId") int sessionId,
            @Param("originTurnId") int originTurnId,
            @Param("stepNumber") int stepNumber,
            @Param("callOrdinal") int callOrdinal,
            @Param("organizationPrivacyGeneration") long organizationPrivacyGeneration);

    /** Reads all members in original display order without applying a transcript projection cap. */
    List<AiChatToolCall> listMembers(
            @Param("workspaceId") int workspaceId,
            @Param("sessionId") int sessionId,
            @Param("proposalKey") String proposalKey,
            @Param("organizationPrivacyGeneration") long organizationPrivacyGeneration);

    /**
     * Tests exact count, contiguous ordinals, trust, tool, shared creation time and chat ownership.
     * This is storage integrity only, not publication readiness, privacy validity or authorization.
     */
    boolean hasCompleteMembership(
            @Param("workspaceId") int workspaceId,
            @Param("sessionId") int sessionId,
            @Param("proposalKey") String proposalKey,
            @Param("organizationPrivacyGeneration") long organizationPrivacyGeneration);
}
