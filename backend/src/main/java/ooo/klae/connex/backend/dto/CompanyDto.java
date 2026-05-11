package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import ooo.klae.connex.backend.beans.Company;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CompanyDto {

    private int id;

    @NotBlank
    @Size(max = 255)
    private String name;

    @Size(max = 255)
    private String website;

    @Size(max = 128)
    private String industry;

    @Size(max = 64)
    private String phone;

    @Size(max = 512)
    private String address;

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
        dto.createdAt = c.getCreatedAt();
        dto.updatedAt = c.getUpdatedAt();
        return dto;
    }

    public Company toBean() {
        Company c = new Company();
        c.setId(id);
        c.setName(name);
        c.setWebsite(website);
        c.setIndustry(industry);
        c.setPhone(phone);
        c.setAddress(address);
        c.setCreatedAt(createdAt);
        c.setUpdatedAt(updatedAt);
        return c;
    }
}
