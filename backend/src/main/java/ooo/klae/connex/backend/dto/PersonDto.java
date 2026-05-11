package ooo.klae.connex.backend.dto;

import com.fasterxml.jackson.annotation.JsonIdentityReference;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Person;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PersonDto {

    private int id;

    @NotBlank
    @Size(max = 255)
    private String name;

    @Email
    @Size(max = 255)
    private String email;

    @Size(max = 64)
    private String phone;

    @JsonIdentityReference(alwaysAsId = true)
    private Company company;

    @Size(max = 128)
    private String title;

    private String createdAt;
    private String updatedAt;

    public static PersonDto from(Person p) {
        if (p == null) return null;
        PersonDto dto = new PersonDto();
        dto.id = p.getId();
        dto.name = p.getName();
        dto.email = p.getEmail();
        dto.phone = p.getPhone();
        dto.company = p.getCompany();
        dto.title = p.getTitle();
        dto.createdAt = p.getCreatedAt();
        dto.updatedAt = p.getUpdatedAt();
        return dto;
    }

    public Person toBean() {
        Person p = new Person();
        p.setId(id);
        p.setName(name);
        p.setEmail(email);
        p.setPhone(phone);
        p.setCompany(company);
        p.setTitle(title);
        p.setCreatedAt(createdAt);
        p.setUpdatedAt(updatedAt);
        return p;
    }
}
