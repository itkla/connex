package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Objects;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.Attachment;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.User;

class LegacyUploadMigrationMapperTest extends AbstractMapperTest {
    @Autowired LegacyTenantUploadMigrationMapper tenantMigrationMapper;
    @Autowired LegacyControlUploadMigrationMapper controlMigrationMapper;
    @Autowired AttachmentMapper attachmentMapper;

    @Test
    void discoversKnownLegacyPrefixesAndRewritesWithCompareAndSet() {
        User user = newUser();
        Company company = newCompany();
        Person person = newPerson(company);
        String attachmentUrl = "/attachments/person/person-" + person.getId()
            + "-1700000000000-legacy.pdf";
        String personUrl = "/contact-pictures/contact-" + person.getId()
            + "-1700000000000-legacy.png";
        String companyUrl = "/company-logos/company-" + company.getId()
            + "-1700000000000-legacy.jpg";
        String userUrl = "/profile-pictures/user-" + user.getId()
            + "-1700000000000-legacy.webp";
        Attachment attachment = new Attachment();
        attachment.setWorkspaceId(workspace.getId());
        attachment.setEntityType("person");
        attachment.setEntityId(person.getId());
        attachment.setFileName("legacy.pdf");
        attachment.setUrl(attachmentUrl);
        attachment.setContentType("application/pdf");
        attachment.setSize(12L);
        attachment.setUploadedBy(user);
        attachmentMapper.insert(attachment);
        assertEquals(1, personMapper.updateImageUrlIfCurrent(
            workspace.getId(), person.getId(), null, personUrl));
        assertEquals(1, companyMapper.updateLogoUrlIfCurrent(
            workspace.getId(), company.getId(), null, companyUrl));
        assertEquals(1, userMapper.updateProfilePictureUrlIfCurrent(user.getId(), null, userUrl));

        assertTrue(tenantMigrationMapper.findAttachments(workspace.getId(), 0, 100).stream()
            .anyMatch(record -> record.getId() == attachment.getId()
                && "person".equals(record.getEntityType())
                && Objects.equals(person.getId(), record.getEntityId())));
        assertTrue(tenantMigrationMapper.findPersonImages(workspace.getId(), 0, 100).stream()
            .anyMatch(record -> record.getId() == person.getId()));
        assertTrue(tenantMigrationMapper.findCompanyImages(workspace.getId(), 0, 100).stream()
            .anyMatch(record -> record.getId() == company.getId()));
        assertTrue(controlMigrationMapper.findUserImages(0, 100).stream()
            .anyMatch(record -> record.getId() == user.getId()));
        assertTrue(controlMigrationMapper.findWorkspaceIds(0, 100).contains(workspace.getId()));
        assertTrue(tenantMigrationMapper.countReferences(workspace.getId()) >= 3);
        assertTrue(controlMigrationMapper.countUserReferences() >= 1);
        assertTrue(tenantMigrationMapper.findAttachments(
            workspace.getId() + 1_000_000, 0, 100).isEmpty());

        String managedAttachment = "/api/attachments/content/550e8400-e29b-41d4-a716-446655440000.pdf";
        assertEquals(1, tenantMigrationMapper.updateAttachment(
            workspace.getId(), attachment.getId(), attachmentUrl, managedAttachment,
            "legacy.pdf", "application/pdf", 99));
        assertEquals(0, tenantMigrationMapper.updateAttachment(
            workspace.getId(), attachment.getId(), attachmentUrl, managedAttachment,
            "legacy.pdf", "application/pdf", 99));
        assertEquals(managedAttachment,
            attachmentMapper.getById(workspace.getId(), attachment.getId()).getUrl());

        String managedPerson = "/api/persons/" + person.getId()
            + "/profile-picture/550e8400-e29b-41d4-a716-446655440000.png";
        String managedCompany = "/api/companies/" + company.getId()
            + "/logo/550e8400-e29b-41d4-a716-446655440000.jpg";
        String managedUser = "/api/users/" + user.getId()
            + "/profile-picture/550e8400-e29b-41d4-a716-446655440000.webp";
        assertEquals(1, tenantMigrationMapper.updatePersonImage(
            workspace.getId(), person.getId(), personUrl, managedPerson));
        assertEquals(1, tenantMigrationMapper.updateCompanyImage(
            workspace.getId(), company.getId(), companyUrl, managedCompany));
        assertEquals(1, controlMigrationMapper.updateUserImage(
            user.getId(), userUrl, managedUser));
        assertEquals(managedPerson,
            personMapper.getPersonById(workspace.getId(), person.getId()).getImageUrl());
        assertEquals(managedCompany,
            companyMapper.getCompanyById(workspace.getId(), company.getId()).getLogoUrl());
        assertEquals(managedUser, userMapper.getUserById(user.getId()).getProfilePictureUrl());
    }
}
