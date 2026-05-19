package ooo.klae.connex.backend.dto;

import java.util.Arrays;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Person;
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

    private int[] personIds;
    private int[] companyIds;
    private int[] dealIds;

    public static TagDto from(Tag t) {
        if (t == null) return null;
        TagDto dto = new TagDto();
        dto.id = t.getId();
        dto.name = t.getName();
        dto.color = t.getColor();
        dto.personIds = t.getPeople() == null ? null : Arrays.stream(t.getPeople()).mapToInt(Person::getId).toArray(); // is getPeople null? yes : no, then map to array of person ids
        dto.companyIds = t.getCompanies() == null ? null : Arrays.stream(t.getCompanies()).mapToInt(Company::getId).toArray();
        dto.dealIds = t.getDeals() == null ? null : Arrays.stream(t.getDeals()).mapToInt(Deal::getId).toArray();
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
