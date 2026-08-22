package ooo.klae.connex.backend.beans;

import lombok.Getter;
import lombok.Setter;

/** One visible CRM record name matched inside bounded Ask Connex free text. */
@Getter
@Setter
public class AiAssistantIdentifierMention {
    private String kind;
    private int id;
    private String value;
}
