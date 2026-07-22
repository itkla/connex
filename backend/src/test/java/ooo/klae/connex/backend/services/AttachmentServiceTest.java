package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.Attachment;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.AttachmentMapper;
import ooo.klae.connex.backend.mappers.ShareMapper;
import ooo.klae.connex.backend.storage.ManagedObjectService.ManagedContent;
import ooo.klae.connex.backend.storage.UploadSource;

class AttachmentServiceTest extends AbstractServiceTest {

    @Autowired AttachmentService attachmentService;
    @Autowired AttachmentMapper attachmentMapper;
    @Autowired ShareMapper shareMapper;

    private Attachment attachmentWithUrl(String url) {
        Attachment attachment = new Attachment();
        attachment.setEntityType("company");
        attachment.setEntityId(1);
        attachment.setFileName("file.png");
        attachment.setUrl(url);
        return attachment;
    }

    @Test
    void create_rejectsScriptAndProtocolRelativeUrls() {
        List<String> unsafe = List.of(
            "javascript:alert(1)",
            "data:text/html,<script>alert(1)</script>",
            "vbscript:msgbox(1)",
            "//evil.com/x",
            "/\\evil.com/x",
            "/" + (char) 0x0A + "/evil.test/x",
            "report.pdf");
        for (String url : unsafe) {
            assertThrows(BadRequestException.class,
                () -> attachmentService.create(attachmentWithUrl(url)), url);
        }
    }

    @Test
    void createRejectsReservedManagedAttachmentReferences() {
        assertThrows(BadRequestException.class, () -> attachmentService.create(attachmentWithUrl(
            "/api/attachments/content/550e8400-e29b-41d4-a716-446655440000.pdf")));
    }

    @Test
    void create_acceptsAppRelativeAndHttpUrls() {
        List<String> safe = List.of(
            "/attachments/company/1-file.png",
            "https://example.com/file.pdf",
            "HTTP://example.com/file.pdf");
        for (String url : safe) {
            assertDoesNotThrow(() -> attachmentService.create(attachmentWithUrl(url)), url);
        }
    }

    @Test
    void getByUrl_resolvesWithinWorkspaceOnly() {
        String url = "/attachments/company/1-" + unique() + ".png";
        Attachment created = attachmentService.create(attachmentWithUrl(url));

        assertEquals(created.getId(), attachmentService.getByUrl(url).getId());
        assertThrows(ResourceNotFoundException.class,
            () -> attachmentService.getByUrl("/attachments/company/missing-" + unique() + ".png"));
        assertNull(attachmentMapper.getByUrl(workspace.getId() + 100_000, url),
            "another workspace must not resolve this blob url");
    }

    @Test
    void create_rejectsUrlClaimedByAnotherWorkspace() {
        String url = "/attachments/company/1-" + unique() + ".png";
        attachmentService.create(attachmentWithUrl(url));

        assertEquals(0, attachmentMapper.countUrlInOtherWorkspaces(workspace.getId(), url),
            "the owning workspace is not counted as another");
        assertEquals(1, attachmentMapper.countUrlInOtherWorkspaces(workspace.getId() + 100_000, url),
            "a different workspace sees the url as foreign, so re-claiming it is rejected on create");
    }

    @Test
    void uploadPersistsAndStreamsPrivateContentForOwnedTarget() throws Exception {
        byte[] bytes = "private attachment".getBytes(StandardCharsets.UTF_8);
        var company = newCompany();

        Attachment uploaded = attachmentService.upload(
            "company",
            company.getId(),
            UploadSource.from("report.pdf", "application/pdf", bytes),
            currentUser);

        assertEquals("report.pdf", uploaded.getFileName());
        assertEquals("application/pdf", uploaded.getContentType());
        assertEquals(bytes.length, uploaded.getSize());
        assertEquals(company.getId(), uploaded.getEntityId());
        try (ManagedContent content = attachmentService.getManagedContent(
                uploaded.getUrl().substring(uploaded.getUrl().lastIndexOf('/') + 1))) {
            assertArrayEquals(bytes, content.inputStream().readAllBytes());
        }
    }

    @Test
    void duplicateManagedReferencesDeleteTheObjectWithTheFinalReference() throws Exception {
        byte[] bytes = "shared private attachment".getBytes(StandardCharsets.UTF_8);
        Company company = newCompany();
        Attachment first = attachmentService.upload(
            "company",
            company.getId(),
            UploadSource.from("report.pdf", "application/pdf", bytes),
            currentUser);
        Attachment second = new Attachment();
        second.setWorkspaceId(workspace.getId());
        second.setEntityType("company");
        second.setEntityId(company.getId());
        second.setFileName(first.getFileName());
        second.setUrl(first.getUrl());
        second.setContentType(first.getContentType());
        second.setSize(first.getSize());
        second.setUploadedBy(currentUser);
        attachmentMapper.insert(second);
        String token = first.getUrl().substring(first.getUrl().lastIndexOf('/') + 1);

        attachmentService.delete(first.getId());

        try (ManagedContent content = attachmentService.getManagedContent(token)) {
            assertArrayEquals(bytes, content.inputStream().readAllBytes());
        }

        attachmentService.delete(second.getId());

        assertThrows(ResourceNotFoundException.class,
            () -> attachmentService.getManagedContent(token));
    }

    @Test
    void uploadAcceptsSameOrganizationSharedCompanyAndPersonTargets() {
        Workspace ownerWorkspace = newWorkspaceInSameOrg();
        Company company = companyInWorkspace(ownerWorkspace);
        Person person = personInWorkspace(ownerWorkspace);
        assertEquals(1, shareMapper.shareCompany(
            company.getId(), ownerWorkspace.getId(), workspace.getId(), currentUser.getId(), false,
            List.of(ownerWorkspace.getId(), workspace.getId())));
        assertEquals(1, shareMapper.sharePerson(
            person.getId(), ownerWorkspace.getId(), workspace.getId(), currentUser.getId(), false,
            List.of(ownerWorkspace.getId(), workspace.getId())));
        byte[] bytes = { 1, 2, 3 };

        Attachment companyAttachment = attachmentService.upload(
            "company",
            company.getId(),
            UploadSource.from("company.pdf", "application/pdf", bytes),
            currentUser);
        Attachment personAttachment = attachmentService.upload(
            "person",
            person.getId(),
            UploadSource.from("person.pdf", "application/pdf", bytes),
            currentUser);

        assertEquals(company.getId(), companyAttachment.getEntityId());
        assertEquals(person.getId(), personAttachment.getEntityId());
        assertEquals(workspace.getId(), companyAttachment.getWorkspaceId());
        assertEquals(workspace.getId(), personAttachment.getWorkspaceId());
    }

    private Workspace newWorkspaceInSameOrg() {
        Workspace other = new Workspace();
        other.setName("Attachment Owner Workspace");
        other.setSlug("attachment-owner-" + unique());
        other.setOrgId(workspaceMapper.getOrgId(workspace.getId()));
        workspaceMapper.insert(other);
        return other;
    }

    private Company companyInWorkspace(Workspace target) {
        Company company = new Company();
        company.setName("Company " + unique());
        company.setWorkspaceId(target.getId());
        companyMapper.insert(company);
        return company;
    }

    private Person personInWorkspace(Workspace target) {
        Person person = new Person();
        person.setName("Person " + unique());
        person.setWorkspaceId(target.getId());
        personMapper.insert(person);
        return person;
    }
}
