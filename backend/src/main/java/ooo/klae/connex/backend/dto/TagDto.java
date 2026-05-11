package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import ooo.klae.connex.backend.beans.Tag;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TagDto {

    private int id;

    @NotBlank
    @Size(max = 64)
    private String name;

    @Pattern(regexp = "^#([0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})$", message = "must be a hex color like #RRGGBB or #RRGGBBAA")
    private String color;

    public static TagDto from(Tag t) {
        if (t == null) return null;
        TagDto dto = new TagDto();
        dto.id = t.getId();
        dto.name = t.getName();
        dto.color = t.getColor();
        return dto;
    }

    public Tag toBean() {
        Tag t = new Tag();
        t.setId(id);
        t.setName(name);
        t.setColor(color);
        return t;
    }
}
