package ooo.klae.connex.backend.dto;

import java.util.Arrays;

import com.fasterxml.jackson.annotation.JsonIdentityReference;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import ooo.klae.connex.backend.beans.Activity;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Note;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Tag;
import ooo.klae.connex.backend.beans.Task;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PersonDto {

    private Integer id;

    @NotBlank
    @Size(max = 255)
    private String name;

    @Email
    @Size(max = 255)
    private String email;

    @Size(max = 64)
    private String phone;

    // @JsonIdentityReference(alwaysAsId = true) // this was casting Company to an int, causing it to crash
    private Company company;

    private Integer companyId;

    @Size(max = 128)
    private String title;

    private int[] tagIds;
    private int[] dealIds;
    private int[] taskIds;
    private int[] activityIds;
    private int[] noteIds;

    private String createdAt;
    private String updatedAt;

    private String imageUrl;

    public static PersonDto from(Person p) {
        if (p == null) return null;
        PersonDto dto = new PersonDto();
        dto.id = p.getId();
        dto.name = p.getName();
        dto.email = p.getEmail();
        dto.phone = p.getPhone();
        dto.company = p.getCompany();
        dto.companyId = p.getCompany() == null ? null : p.getCompany().getId();
        dto.title = p.getTitle();
        dto.tagIds = p.getTags() == null ? null : Arrays.stream(p.getTags()).mapToInt(Tag::getId).toArray();
        dto.dealIds = p.getDeals() == null ? null : Arrays.stream(p.getDeals()).mapToInt(Deal::getId).toArray();
        dto.taskIds = p.getTasks() == null ? null : Arrays.stream(p.getTasks()).mapToInt(Task::getId).toArray();
        dto.activityIds = p.getActivities() == null ? null : Arrays.stream(p.getActivities()).mapToInt(Activity::getId).toArray();
        dto.noteIds = p.getNotes() == null ? null : Arrays.stream(p.getNotes()).mapToInt(Note::getId).toArray();
        dto.createdAt = p.getCreatedAt();
        dto.updatedAt = p.getUpdatedAt();
        dto.imageUrl = p.getImageUrl();
        return dto;
    }

    public Person toBean() {
        Person p = new Person();
        if (id != null) p.setId(id);
        p.setName(name);
        p.setEmail(email);
        p.setPhone(phone);
        // if company is not null set it to company id stub, otherwise set it to company
        if (companyId != null) {
            Company stub = new Company();
            stub.setId(companyId);
            p.setCompany(stub);
        } else {
            p.setCompany(company);
        }
        p.setTitle(title);
        p.setCreatedAt(createdAt);
        p.setUpdatedAt(updatedAt);
        p.setImageUrl(imageUrl);
        return p;
    }
}
