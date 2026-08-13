package ooo.klae.connex.backend.beans;

import lombok.Data;
import lombok.NoArgsConstructor;

/** Durable execution state for one bounded assistant agent turn. */
@Data
@NoArgsConstructor
public class AiChatTurn {
    private int id;
    private int workspaceId;
    private int sessionId;
    private Integer requestedByUserId;
    private String status;
    private String terminalReason;
    private String privacyMode = "masked";
    private boolean streamed;
    private String partialContent;
    private int partialContentUtf16Offset;
    private String cancelRequestedAt;
    private String createdAt;
    private String updatedAt;
}
