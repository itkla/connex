package ooo.klae.connex.backend.beans;

import java.time.LocalDateTime;

import lombok.Data;
import lombok.NoArgsConstructor;

/** An immutable versioned revision of a campaign message's content in one locale. */
@Data
@NoArgsConstructor
public class CampaignMessageRevision {
    private int id;
    private int workspaceId;
    private int messageId;
    private int version;
    private String locale;
    private String subject;
    private String bodyHtml;
    private String bodyText;
    private LocalDateTime createdAt;
}
