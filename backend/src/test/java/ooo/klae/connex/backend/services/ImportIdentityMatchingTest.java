package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import ooo.klae.connex.backend.beans.CustomFieldDefinition;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.ColumnMapping;
import ooo.klae.connex.backend.dto.DuplicateMatchKind;
import ooo.klae.connex.backend.dto.ImportPreviewResult;
import ooo.klae.connex.backend.dto.ImportRequest;
import ooo.klae.connex.backend.dto.ImportResult;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.mappers.CustomFieldDefinitionMapper;
import ooo.klae.connex.backend.mappers.RecordCreationTemplateMapper;

class ImportIdentityMatchingTest extends AbstractServiceTest {

    @Autowired ImportService importService;
    @Autowired PersonService personService;
    @Autowired CustomFieldValueService customFieldValueService;
    @Autowired CustomFieldDefinitionMapper definitionMapper;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired SqlSessionTemplate sqlSessionTemplate;
    @MockitoSpyBean RecordCreationTemplateMapper templateMapperSpy;

    @Test
    void sharedOfficePhoneDoesNotCombineDifferentPeopleForAnyDuplicateAction() {
        for (String action : List.of("fill_empty", "overwrite", "skip")) {
            String firstEmail = unique() + "@example.test";
            String secondEmail = unique() + "@example.test";
            ImportRequest request = peopleRequest(List.of(
                Map.of("Name", "森 海斗", "Email", firstEmail, "Phone", "03-5555-0000"),
                Map.of("Name", "森 莉奈", "Email", secondEmail, "Phone", "+81 3 5555 0000")), action);

            ImportPreviewResult preview = importService.previewPersons(request);

            assertEquals(2, preview.getToCreate());
            assertEquals(0, preview.getInvalid());
            assertTrue(preview.getRows().stream().allMatch(row -> row.getMergedRowCount() == 1));
            ImportResult result = commit(request, preview);
            assertEquals(2, result.getCreated());
            assertTrue(result.getFailed().isEmpty());
            assertEquals(2, personMapper.findByEmails(
                workspace.getId(), List.of(firstEmail, secondEmail)).size());
        }
    }

    @Test
    void phoneOnlyMatchesRemainReviewCandidatesWithoutOverwritingExistingPeople() {
        Person draft = new Person();
        draft.setName("Office colleague");
        draft.setEmail(unique() + "@example.test");
        draft.setPhone("03-5555-0001");
        Person existing = personService.create(draft);
        for (String action : List.of("fill_empty", "overwrite", "skip")) {
            String incomingEmail = unique() + "@example.test";
            ImportRequest request = peopleRequest(List.of(Map.of(
                "Name", "New colleague " + unique(), "Email", incomingEmail,
                "Phone", "03-5555-0001")), action);

            ImportPreviewResult preview = importService.previewPersons(request);

            assertEquals(1, preview.getToCreate());
            assertEquals(0, preview.getToUpdate());
            assertNull(preview.getRows().getFirst().getMatchedId());
            assertTrue(preview.getRows().getFirst().getCandidates().stream()
                .anyMatch(candidate -> candidate.recordId() == existing.getId()
                    && candidate.matches().stream().anyMatch(match -> match.kind() == DuplicateMatchKind.PHONE)));
            ImportResult result = commit(request, preview);
            assertEquals(1, result.getCreated());
            assertEquals(0, result.getUpdated());
            assertTrue(result.getFailed().isEmpty());
        }
        assertEquals("Office colleague", jdbcTemplate.queryForObject(
            "SELECT name FROM person WHERE workspace_id = ? AND id = ?",
            String.class, workspace.getId(), existing.getId()));
        assertEquals(existing.getEmail(), jdbcTemplate.queryForObject(
            "SELECT email FROM person WHERE workspace_id = ? AND id = ?",
            String.class, workspace.getId(), existing.getId()));
    }

    @Test
    void phoneWithoutEmailDoesNotCombineNamesOrAutomaticallyMatchTheSameName() {
        ImportRequest request = peopleRequest(List.of(
            Map.of("Name", "Phone only first", "Phone", "03-5555-0002"),
            Map.of("Name", "Phone only second", "Phone", "03-5555-0002")), "overwrite");
        ImportPreviewResult preview = importService.previewPersons(request);

        assertEquals(2, preview.getToCreate());
        assertEquals(2, commit(request, preview).getCreated());

        ImportRequest repeated = peopleRequest(List.of(request.getRows().getFirst()), "overwrite");
        ImportPreviewResult repeatedPreview = importService.previewPersons(repeated);
        assertEquals(1, repeatedPreview.getToCreate());
        assertEquals(0, repeatedPreview.getInvalid());
        assertEquals(1, commit(repeated, repeatedPreview).getCreated());
    }

    @Test
    void repeatedEmailsWithSharedAndChangedPhonesResolveToSeparatePeople() {
        String firstEmail = unique() + "@example.test";
        String secondEmail = unique() + "@example.test";
        ImportRequest request = new ImportRequest(List.of(
            Map.of("Name", "First person", "Email", firstEmail, "Phone", "03-5555-0003", "Title", "Director"),
            Map.of("Name", "Second person", "Email", secondEmail, "Phone", "03-5555-0003"),
            Map.of("Name", "First person", "Email", firstEmail.toUpperCase(java.util.Locale.ROOT), "Phone", "03-5555-0004"),
            Map.of("Name", "Second person", "Email", secondEmail, "Phone", "03-5555-0004", "Title", "Manager")),
            List.of(mapping("Name", "name"), mapping("Email", "email"), mapping("Phone", "phone"), mapping("Title", "title")),
            "fill_empty", null);

        ImportPreviewResult preview = importService.previewPersons(request);

        assertEquals(0, preview.getInvalid());
        assertEquals(2, preview.getToCreate());
        assertEquals(List.of(0, 1, 0, 1), preview.getRows().stream().map(row -> row.getCanonicalRowIndex()).toList());
        assertTrue(preview.getRows().stream().allMatch(row -> row.getMergedRowCount() == 2));
        ImportResult created = commit(request, preview);
        assertEquals(2, created.getCreated());
        assertTrue(created.getFailed().isEmpty());

        ImportPreviewResult replay = importService.previewPersons(request);
        assertEquals(0, replay.getInvalid());
        assertEquals(2, replay.getToUpdate());
        ImportResult unchanged = commit(request, replay);
        assertEquals(0, unchanged.getCreated());
        assertEquals(0, unchanged.getUpdated());
        assertTrue(unchanged.getFailed().isEmpty());
        assertEquals("Director", jdbcTemplate.queryForObject(
            "SELECT title FROM person WHERE workspace_id = ? AND email = ?", String.class, workspace.getId(), firstEmail));
        assertEquals("Manager", jdbcTemplate.queryForObject(
            "SELECT title FROM person WHERE workspace_id = ? AND email = ?", String.class, workspace.getId(), secondEmail));
    }

    @Test
    void explicitPhoneCandidateLinkCanUpdateAnOwnedContact() {
        Person draft = new Person();
        draft.setName("Reviewed phone target");
        draft.setPhone("03-5555-0005");
        Person target = personService.create(draft);
        ImportRequest request = peopleRequest(List.of(Map.of(
            "Name", "Reviewed updated name", "Phone", "03-5555-0005")), "overwrite");
        request.setLinks(Map.of(0, target.getId()));

        ImportPreviewResult preview = importService.previewPersons(request);
        assertEquals(1, preview.getToUpdate());
        assertEquals(target.getId(), preview.getRows().getFirst().getMatchedId());
        assertEquals(1, commit(request, preview).getUpdated());
    }

    @Test
    void emailMatchStillUpdatesWhenAColleagueAlsoOwnsThePhone() {
        Person draft = new Person();
        draft.setName("Email target");
        draft.setEmail(unique() + "@example.test");
        draft.setPhone("03-5555-0008");
        Person target = personService.create(draft);
        Person colleague = new Person();
        colleague.setName("Separate colleague");
        colleague.setEmail(unique() + "@example.test");
        colleague.setPhone("03-5555-0008");
        Person other = personService.create(colleague);
        ImportRequest request = peopleRequest(List.of(Map.of(
            "Name", "Updated email target", "Email", target.getEmail(), "Phone", "03-5555-0008")), "overwrite");

        ImportPreviewResult preview = importService.previewPersons(request);

        assertEquals(1, preview.getToUpdate());
        assertEquals(0, preview.getInvalid());
        assertEquals(target.getId(), preview.getRows().getFirst().getMatchedId());
        ImportResult result = commit(request, preview);
        assertEquals(1, result.getUpdated());
        assertTrue(result.getFailed().isEmpty());
        assertEquals("Separate colleague", jdbcTemplate.queryForObject(
            "SELECT name FROM person WHERE workspace_id = ? AND id = ?",
            String.class, workspace.getId(), other.getId()));
    }

    @Test
    void aColleaguesPhoneIdentityDoesNotPreventBackfillingTheSelectedContactsIdentity() {
        Person draft = new Person();
        draft.setName("Phone backfill target");
        draft.setEmail(unique() + "@example.test");
        draft.setPhone("03-5555-0009");
        Person target = personService.create(draft);
        jdbcTemplate.update(
            "DELETE FROM person_identity WHERE workspace_id = ? AND person_id = ? AND kind = 'phone'",
            workspace.getId(), target.getId());
        Person colleague = new Person();
        colleague.setName("Phone backfill colleague");
        colleague.setEmail(unique() + "@example.test");
        colleague.setPhone(target.getPhone());
        personService.create(colleague);
        ImportRequest request = peopleRequest(List.of(Map.of(
            "Name", target.getName(), "Email", target.getEmail(), "Phone", target.getPhone())), "fill_empty");

        ImportPreviewResult preview = importService.previewPersons(request);
        assertEquals(1, preview.getToUpdate());
        ImportResult result = commit(request, preview);

        assertTrue(result.getFailed().isEmpty());
        assertEquals(1, result.getUpdated());
        assertEquals(1, jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM person_identity
            WHERE workspace_id = ? AND person_id = ? AND kind = 'phone'
              AND normalized_value = '+81355550009' AND superseded_at IS NULL
            """, Integer.class, workspace.getId(), target.getId()));
        assertEquals(0, commit(request, importService.previewPersons(request)).getUpdated());
    }

    @Test
    void foreignTenantPhoneAndEmailNeverMatchOrBlockAnImport() {
        Workspace foreign = new Workspace();
        foreign.setName("Other organization " + unique());
        foreign.setSlug("other-" + unique());
        workspaceMapper.insert(foreign);
        String email = unique() + "@example.test";
        Person draft = new Person();
        draft.setWorkspaceId(foreign.getId());
        draft.setName("Foreign contact");
        draft.setEmail(email);
        draft.setPhone("03-5555-0006");
        personMapper.insert(draft);
        jdbcTemplate.update("""
            INSERT INTO person_identity (workspace_id, person_id, kind, `value`, normalized_value, source_system, acquired_at)
            VALUES (?, ?, 'email', ?, ?, 'manual', NOW()), (?, ?, 'phone', ?, ?, 'manual', NOW())
            """, foreign.getId(), draft.getId(), email, email,
            foreign.getId(), draft.getId(), draft.getPhone(), "+81355550006");
        ImportRequest request = peopleRequest(List.of(Map.of(
            "Name", "Local contact", "Email", email, "Phone", "03-5555-0006")), "overwrite");

        ImportPreviewResult preview = importService.previewPersons(request);

        assertEquals(1, preview.getToCreate());
        assertTrue(preview.getRows().getFirst().getCandidates().isEmpty());
        ImportResult result = commit(request, preview);
        assertEquals(1, result.getCreated());
        assertTrue(result.getFailed().isEmpty());
        assertEquals("Foreign contact", jdbcTemplate.queryForObject(
            "SELECT name FROM person WHERE workspace_id = ? AND id = ?",
            String.class, foreign.getId(), draft.getId()));
    }

    @Test
    void japaneseCustomFieldsStayDistinctInOneFileAndAcrossImports() {
        String email = unique() + "@example.test";
        List<ColumnMapping> mapping = List.of(mapping("Name", "name"), mapping("Email", "email"),
            custom("名", "text"), custom("部署", "text"), custom("住所", "text"), custom("役職X", "text"), custom("部署X", "text"));
        ImportRequest request = new ImportRequest(List.of(Map.of(
            "Name", "Imported contact", "Email", email, "名", "太郎", "部署", "営業部", "住所", "東京都",
            "役職X", "部長", "部署X", "購買部")), mapping, "overwrite", null);

        ImportPreviewResult preview = importService.previewPersons(request);
        assertEquals(1, preview.getToCreate());
        assertEquals(0, preview.getInvalid());
        ImportResult first = commit(request, preview);
        assertEquals(1, first.getCreated());
        assertTrue(first.getFailed().isEmpty());
        List<CustomFieldDefinition> definitions = definitionMapper.getByEntityType(workspace.getId(), "person");
        assertEquals(5, definitions.size());
        assertEquals(5, definitions.stream().map(CustomFieldDefinition::getFieldKey).distinct().count());
        assertTrue(definitions.stream().allMatch(def -> def.getFieldKey().length() <= 64));
        int personId = personMapper.findByEmails(workspace.getId(), List.of(email)).getFirst().getId();
        Map<Integer, Object> values = customFieldValueService.getForEntities("person", List.of(personId)).getOrDefault(personId, Map.of());
        for (CustomFieldDefinition definition : definitions) {
            assertEquals(request.getRows().getFirst().get(definition.getLabel()), values.get(definition.getId()));
        }

        assertEquals(0, commit(request, importService.previewPersons(request)).getUpdated());
        assertEquals(definitions.stream().map(CustomFieldDefinition::getId).toList(),
            definitionMapper.getByEntityType(workspace.getId(), "person").stream().map(CustomFieldDefinition::getId).toList());
    }

    @Test
    void successiveJapaneseFieldsNeverReuseTheLegacyFallbackDefinition() {
        CustomFieldDefinition legacy = definition("field", "名", "text", false, workspace.getId());
        String email = unique() + "@example.test";
        ImportRequest first = customRequest(email, "部署", "text", "営業部");
        assertEquals(1, commit(first, importService.previewPersons(first)).getCreated());
        ImportRequest second = customRequest(email, "住所", "text", "東京都");
        assertEquals(1, commit(second, importService.previewPersons(second)).getUpdated());

        List<CustomFieldDefinition> definitions = definitionMapper.getByEntityType(workspace.getId(), "person");
        assertEquals(3, definitions.size());
        int personId = personMapper.findByEmails(workspace.getId(), List.of(email)).getFirst().getId();
        Map<Integer, Object> values = customFieldValueService.getForEntities("person", List.of(personId)).getOrDefault(personId, Map.of());
        assertFalse(values.containsKey(legacy.getId()));
        assertEquals("営業部", values.get(definitions.stream().filter(def -> "部署".equals(def.getLabel())).findFirst().orElseThrow().getId()));
        assertEquals("東京都", values.get(definitions.stream().filter(def -> "住所".equals(def.getLabel())).findFirst().orElseThrow().getId()));
    }

    @Test
    void asciiSlugCollisionsDoNotReuseAnUnrelatedLabelTypeOrArchivedDefinition() {
        for (CustomFieldDefinition collision : List.of(
                definition("a_b", "A-B", "text", false, workspace.getId()),
                definition("budget", "Budget", "number", false, workspace.getId()),
                definition("region", "Region", "text", true, workspace.getId()))) {
            String label = "a_b".equals(collision.getFieldKey()) ? "A B" : collision.getLabel();
            String email = unique() + "@example.test";
            ImportRequest request = customRequest(email, label, "text", "Separate value");
            ImportPreviewResult preview = importService.previewPersons(request);
            assertEquals(1, preview.getToCreate());
            ImportResult result = commit(request, preview);
            assertEquals(1, result.getCreated());
            assertTrue(result.getFailed().isEmpty());
            int personId = personMapper.findByEmails(workspace.getId(), List.of(email)).getFirst().getId();
            Map<Integer, Object> values = customFieldValueService.getForEntities("person", List.of(personId)).getOrDefault(personId, Map.of());
            assertFalse(values.containsKey(collision.getId()));
            CustomFieldDefinition created = definitionMapper.getByEntityType(workspace.getId(), "person").stream()
                .filter(def -> !def.isArchived() && label.equals(def.getLabel()) && "text".equals(def.getFieldType()))
                .findFirst().orElseThrow();
            assertNotEquals(collision.getFieldKey(), created.getFieldKey());
            assertEquals("Separate value", values.get(created.getId()));
            assertEquals(0, commit(request, importService.previewPersons(request)).getUpdated());
        }
    }

    @Test
    void customFieldsInAnotherTenantCannotSupplyTheDefinition() {
        Workspace foreign = new Workspace();
        foreign.setName("Other custom fields " + unique());
        foreign.setSlug("other-custom-" + unique());
        workspaceMapper.insert(foreign);
        CustomFieldDefinition foreignDefinition = definition("budget", "Budget", "text", false, foreign.getId());
        String email = unique() + "@example.test";
        ImportRequest request = customRequest(email, "Budget", "text", "Local value");

        ImportPreviewResult preview = importService.previewPersons(request);
        assertEquals(1, preview.getToCreate());
        assertEquals(1, commit(request, preview).getCreated());
        CustomFieldDefinition local = definitionMapper.getByKey(workspace.getId(), "person", "budget");
        assertNotNull(local);
        assertNotEquals(foreignDefinition.getId(), local.getId());
        int personId = personMapper.findByEmails(workspace.getId(), List.of(email)).getFirst().getId();
        assertEquals(Map.of(local.getId(), "Local value"),
            customFieldValueService.getForEntities("person", List.of(personId)).get(personId));
    }

    @Test
    void longLabelsWithTheSameAsciiPrefixReceiveDistinctStableKeys() {
        String prefix = "long_label_".repeat(7);
        ImportRequest request = new ImportRequest(List.of(Map.of(
            "Name", "Long label contact", "Email", unique() + "@example.test", "First", "one", "Second", "two")),
            List.of(mapping("Name", "name"), mapping("Email", "email"),
                new ColumnMapping("First", null, true, "text", prefix + "first"),
                new ColumnMapping("Second", null, true, "text", prefix + "second")), "overwrite", null);

        ImportPreviewResult preview = importService.previewPersons(request);
        assertEquals(1, preview.getToCreate());
        assertEquals(1, commit(request, preview).getCreated());
        List<CustomFieldDefinition> definitions = definitionMapper.getByEntityType(workspace.getId(), "person");
        assertEquals(2, definitions.size());
        assertNotEquals(definitions.getFirst().getFieldKey(), definitions.getLast().getFieldKey());
        assertTrue(definitions.stream().allMatch(def -> def.getFieldKey().length() == 64));
        assertEquals(0, commit(request, importService.previewPersons(request)).getUpdated());
        assertEquals(2, definitionMapper.getByEntityType(workspace.getId(), "person").size());
    }

    @Test
    void aDefinitionRenamedAtTheTemplateFenceCannotRedirectValues() {
        CustomFieldDefinition existing = definition("budget", "Budget", "text", false, workspace.getId());
        String email = unique() + "@example.test";
        ImportRequest request = customRequest(email, "Budget", "text", "Reviewed value");
        ImportPreviewResult preview = importService.previewPersons(request);
        RecordCreationTemplateMapper realMapper = sqlSessionTemplate.getMapper(RecordCreationTemplateMapper.class);
        doAnswer(invocation -> {
            existing.setLabel("Unrelated field");
            definitionMapper.update(existing);
            return realMapper.getSetForUpdate(workspace.getId(), "person");
        }).when(templateMapperSpy).getSetForUpdate(workspace.getId(), "person");

        assertThrows(ConflictException.class, () -> commit(request, preview));

        assertTrue(personMapper.findByEmails(workspace.getId(), List.of(email)).isEmpty());
        assertEquals(1, definitionMapper.getByEntityType(workspace.getId(), "person").size());
        assertEquals(0, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM custom_field_value WHERE workspace_id = ? AND definition_id = ?",
            Integer.class, workspace.getId(), existing.getId()));
    }

    @Test
    void anUnrelatedDefinitionAtBothKeysFailsDuringPreview() {
        definition("a_b", "A-B", "text", false, workspace.getId());
        ImportRequest request = customRequest(unique() + "@example.test", "A B", "text", "Separate value");
        assertEquals(1, commit(request, importService.previewPersons(request)).getCreated());
        CustomFieldDefinition alternative = definitionMapper.getByEntityType(workspace.getId(), "person").stream()
            .filter(def -> "A B".equals(def.getLabel())).findFirst().orElseThrow();
        alternative.setLabel("Unrelated field");
        definitionMapper.update(alternative);

        BadRequestException exception = assertThrows(BadRequestException.class,
            () -> importService.previewPersons(request));

        assertTrue(exception.getMessage().contains("choose a different label or map to an existing field"));
        assertEquals(2, definitionMapper.getByEntityType(workspace.getId(), "person").size());
    }

    private CustomFieldDefinition definition(String key, String label, String type, boolean archived, int workspaceId) {
        CustomFieldDefinition definition = new CustomFieldDefinition();
        definition.setWorkspaceId(workspaceId);
        definition.setEntityType("person");
        definition.setFieldKey(key);
        definition.setLabel(label);
        definition.setFieldType(type);
        definition.setArchived(archived);
        definitionMapper.insert(definition);
        return definition;
    }

    private static ImportRequest customRequest(String email, String label, String type, String value) {
        return new ImportRequest(List.of(Map.of("Name", "Custom contact", "Email", email, label, value)),
            List.of(mapping("Name", "name"), mapping("Email", "email"), custom(label, type)), "overwrite", null);
    }

    private static ColumnMapping custom(String label, String type) {
        return new ColumnMapping(label, null, true, type, label);
    }

    private static ColumnMapping mapping(String column, String field) {
        return new ColumnMapping(column, field, null, null, null);
    }

    private static ImportRequest peopleRequest(List<Map<String, String>> rows, String action) {
        return new ImportRequest(rows,
            List.of(mapping("Name", "name"), mapping("Email", "email"), mapping("Phone", "phone")), action, null);
    }

    private ImportResult commit(ImportRequest request, ImportPreviewResult preview) {
        request.setDuplicateReviewProof(preview.getDuplicateReviewProof());
        return importService.commitPersons(request);
    }
}
