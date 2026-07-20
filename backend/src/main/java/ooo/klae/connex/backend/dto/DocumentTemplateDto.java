package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import lombok.Data;
import lombok.NoArgsConstructor;

import ooo.klae.connex.backend.beans.DocumentTemplate;

/** Client-facing document template. {@code workspaceId} is never accepted from the client. */
@Data
@NoArgsConstructor
public class DocumentTemplateDto {

    private Integer id;

    @NotBlank
    @Size(max = 255)
    private String name;

    @Pattern(regexp = "quote|proposal|order_form|contract",
        message = "type must be quote, proposal, order_form, or contract")
    private String type;

    @Size(max = 8)
    private String locale;

    @Size(max = 512)
    private String title;

    private String intro;
    private String terms;
    private String footer;
    private String body;

    private Boolean active;

    private String createdAt;
    private String updatedAt;

    public static DocumentTemplateDto from(DocumentTemplate t) {
        if (t == null) return null;
        DocumentTemplateDto dto = new DocumentTemplateDto();
        dto.id = t.getId();
        dto.name = t.getName();
        dto.type = t.getType();
        dto.locale = t.getLocale();
        dto.title = t.getTitle();
        dto.intro = t.getIntro();
        dto.terms = t.getTerms();
        dto.footer = t.getFooter();
        dto.body = t.getBody();
        dto.active = t.isActive();
        dto.createdAt = t.getCreatedAt();
        dto.updatedAt = t.getUpdatedAt();
        return dto;
    }

    public DocumentTemplate toBean() {
        DocumentTemplate t = new DocumentTemplate();
        if (id != null) t.setId(id);
        t.setName(name);
        t.setType(type == null || type.isBlank() ? "quote" : type);
        t.setLocale(locale == null || locale.isBlank() ? "en" : locale);
        t.setTitle(title);
        t.setIntro(intro);
        t.setTerms(terms);
        t.setFooter(footer);
        t.setBody(body);
        t.setActive(active == null ? true : active);
        return t;
    }
}
