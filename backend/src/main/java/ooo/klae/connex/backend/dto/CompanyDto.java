package ooo.klae.connex.backend.dto;

import java.util.Arrays;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.hibernate.validator.constraints.URL;

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
public class CompanyDto {

    private Integer id;

    @NotBlank
    @Size(max = 255)
    private String name;

    @URL
    @Size(max = 255)
    private String website;

    @Size(max = 128)
    private String industry;

    @Size(max = 64)
    private String phone;

    @Size(max = 512)
    private String address;

    private String logoUrl;

    private int[] personIds;
    private int[] dealIds;
    private int[] tagIds;

    private String createdAt;
    private String updatedAt;

    public static CompanyDto from(Company c) {
        if (c == null) return null;
        CompanyDto dto = new CompanyDto();
        dto.id = c.getId();
        dto.name = c.getName();
        dto.website = c.getWebsite();
        dto.industry = c.getIndustry();
        dto.phone = c.getPhone();
        dto.address = c.getAddress();
        dto.personIds = c.getPeople() == null ? null : Arrays.stream(c.getPeople()).mapToInt(Person::getId).toArray();
        dto.dealIds = c.getDeals() == null ? null : Arrays.stream(c.getDeals()).mapToInt(Deal::getId).toArray();
        dto.tagIds = c.getTags() == null ? null : Arrays.stream(c.getTags()).mapToInt(Tag::getId).toArray();
        dto.createdAt = c.getCreatedAt();
        dto.updatedAt = c.getUpdatedAt();
        dto.logoUrl = c.getLogoUrl();
        return dto;
    }

    public Company toBean() {
        Company c = new Company();
        if (id != null) c.setId(id);
        c.setName(name);
        c.setWebsite(website);
        c.setIndustry(industry);
        c.setPhone(phone);
        c.setAddress(address);
        c.setCreatedAt(createdAt);
        c.setUpdatedAt(updatedAt);
        c.setLogoUrl(logoUrl);
        return c;
    }
}
