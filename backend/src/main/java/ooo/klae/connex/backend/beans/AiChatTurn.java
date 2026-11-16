package ooo.klae.connex.backend.beans;

import lombok.Data;
import lombok.NoArgsConstructor;

/** Durable execution state for one bounded assistant agent turn. */
@Data
@NoArgsConstructor
public class AiChatTurn {
    private int id;
    private Integer missionRunId;
    private Integer missionSegmentId;
    private Long privacyGeneration;
    private Integer privacyTaintSeq;
    private int workspaceId;
    private long organizationPrivacyGeneration;
    private int sessionId;
    private Integer requestedByUserId;
    private String status;
    private String terminalReason;
    private String skillKey;
    private String skillVersion;
    private String scopeJson;
    private String privacyMode = "masked";
    private boolean streamed;
    private String partialContent;
    private int partialContentUtf16Offset;
    private String cancelRequestedAt;
    private String createdAt;
    private String updatedAt;
}
