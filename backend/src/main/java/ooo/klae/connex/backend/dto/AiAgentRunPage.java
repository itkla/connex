package ooo.klae.connex.backend.dto;

import java.util.List;

/** Stable descending-ID page bound to the current requester and normalized filters. */
public record AiAgentRunPage(List<AiAgentRunDto> items, String nextCursor) {}
