package ooo.klae.connex.backend.ai.mission;

import java.time.Instant;
import java.util.List;

import ooo.klae.connex.backend.ai.assistant.AiAssistantPreparedWrite;
import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteToolService.LockedDecisionContext;
import ooo.klae.connex.backend.beans.AiAgentAction;
import ooo.klae.connex.backend.beans.AiAgentRun;
import ooo.klae.connex.backend.beans.AiChatToolCall;

/** Mission-owned semantic slots backed exclusively by canonical singleton tool calls. */
public interface AiAgentProposalSetPort {
    /** Allocates the closed definition inventory under ordered authorization/session/run roots. */
    List<AiAgentAction> allocateSlots(AiAgentRun run, AiAgentScopePolicy scope);
    /** Verifies runtime-three checkpoint references against the complete authoritative action inventory. */
    void validateInventory(AiAgentRun run, AiAgentContinuationCodec.Snapshot snapshot);
    /** Discovers durable source holdings independently of executable runtime compatibility. */
    boolean hasInventory(AiAgentRun run);
    /** Discovers semantic identity before any fresh preparation or lock acquisition. */
    AiAgentAction findSlot(int workspaceId, int runId, String semanticKey);
    /** Binds immutable provenance and its canonical prepared singleton in the same transaction. */
    AiChatToolCall publishPrepared(AiAgentRun run, AiAgentExecutionPolicy policy, AiAgentAction candidate,
            AiAssistantPreparedWrite write);
    /** Locks every action before every canonical call. */
    List<AiAgentAction> lockInventory(AiAgentRun run);
    /** Freezes all bound members after their original source vectors have been revalidated. */
    void freeze(AiAgentRun run, List<AiAgentAction> inventory, Instant frozenAt);
    /** Reads exact frozen membership independently of transcript limits. */
    List<AiAgentAction> readFrozen(AiAgentRun run);
    /** Executes one selected decision through the assistant-owned ordered decision core. */
    void decideLocked(LockedDecisionContext context, String decision);
    /** Withdraws pending canonical mission rows without checkpoint discovery. */
    void withdrawPending(AiAgentRun run, String reason);
}
