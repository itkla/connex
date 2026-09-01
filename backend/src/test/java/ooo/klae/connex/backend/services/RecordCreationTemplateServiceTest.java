package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.beans.RecordCreationTemplate;
import ooo.klae.connex.backend.beans.RecordCreationTemplateSet;
import ooo.klae.connex.backend.beans.RecordCreationTemplateVersion;
import ooo.klae.connex.backend.dto.recordcreation.LocalizedTextDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateCreateRequestDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateDefaultRequestDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateDefinitionDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateDuplicateRequestDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateFieldDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateGroupDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateReorderRequestDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateResetRequestDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateStateRequestDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateUpdateRequestDto;
import ooo.klae.connex.backend.dto.recordcreation.ResolvedCreationTemplateDto;
import ooo.klae.connex.backend.exceptions.RecordCreationTemplateException;
import ooo.klae.connex.backend.mappers.CustomFieldDefinitionMapper;
import ooo.klae.connex.backend.mappers.RecordCreationTemplateMapper;
import ooo.klae.connex.backend.recordcreation.RecordCreationFieldRegistry;
import ooo.klae.connex.backend.recordcreation.RecordCreationRecordType;
import ooo.klae.connex.backend.recordcreation.RecordCreationTemplateAvailability;

class RecordCreationTemplateServiceTest {

    private final RecordCreationTemplateMapper mapper = mock(RecordCreationTemplateMapper.class);
    private final CustomFieldDefinitionMapper customFieldMapper = mock(CustomFieldDefinitionMapper.class);
    private final RecordCreationTemplateValidator validator = mock(RecordCreationTemplateValidator.class);
    private final RecordCreationTemplateResolver resolver = mock(RecordCreationTemplateResolver.class);
    private final WorkspaceService workspaceService = mock(WorkspaceService.class);
    private final AuditService auditService = mock(AuditService.class);
    private final RecordCreationFieldRegistry registry = new RecordCreationFieldRegistry();

    private RecordCreationTemplateService service;
    private RecordCreationTemplateDefinitionDto definition;

    @BeforeEach
    void setUp() throws Exception {
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(7);
        when(workspaceService.getCurrentUserId()).thenReturn(11);
        definition = definition("name");
        service = new RecordCreationTemplateService(
            mapper,
            customFieldMapper,
            validator,
            resolver,
            registry,
            workspaceService,
            auditService,
            Clock.fixed(Instant.parse("2026-08-31T10:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void createPublishesVersionAndAdvancesTheSetOnce() throws Exception {
        RecordCreationTemplateSet set = set(4, null);
        when(mapper.getSetForUpdate(7, "person")).thenReturn(set);
        when(mapper.listRootsForUpdate(7, "person")).thenReturn(List.of());
        var validated = validated("canonical", definition);
        when(validator.validateAndCanonicalize(any(), any(), any(), any())).thenReturn(validated);
        doAnswer(invocation -> {
            invocation.<RecordCreationTemplate>getArgument(0).setId(42);
            return null;
        }).when(mapper).insertRoot(any());
        doAnswer(invocation -> {
            invocation.<RecordCreationTemplateVersion>getArgument(0).setId(101);
            return null;
        }).when(mapper).insertVersion(any());
        when(mapper.installCurrentVersion(7, 42, 101, 0, 11)).thenReturn(1);
        when(mapper.advanceSetRevision(7, "person", 4)).thenReturn(1);
        RecordCreationTemplate stored = root(42, "person", "enabled", 0, 1);
        RecordCreationTemplateVersion version = version(42, 1, "canonical");
        when(mapper.getRoot(7, 42)).thenReturn(stored);
        when(mapper.getCurrentVersion(7, 42)).thenReturn(version);
        when(validator.parseDefinition("canonical")).thenReturn(definition);
        when(resolver.resolveWorkspace(stored, version, null)).thenReturn(
            available("workspace:42", RecordCreationRecordType.person, false));

        var created = service.create(new RecordCreationTemplateCreateRequestDto(
            RecordCreationRecordType.person,
            names(),
            null,
            definition,
            true,
            4));

        assertEquals("workspace:42", created.id());
        assertEquals(1, created.version());
        verify(mapper).insertSetIfAbsent(7, "person");
        verify(mapper).insertVersion(any());
        verify(mapper).advanceSetRevision(7, "person", 4);
        verify(auditService).record(
            org.mockito.ArgumentMatchers.eq("record_creation_template.create"),
            anyString(), anyInt(), anyString(), anyString(), any());
    }

    @Test
    void byteIdenticalUpdateIsANoOp() throws Exception {
        RecordCreationTemplate root = root(42, "person", "enabled", 0, 3);
        RecordCreationTemplateVersion current = version(42, 2, "same");
        RecordCreationTemplateSet set = set(8, null);
        when(mapper.getRoot(7, 42)).thenReturn(root);
        when(mapper.getSetForUpdate(7, "person")).thenReturn(set);
        when(mapper.getRootForUpdate(7, 42)).thenReturn(root);
        when(mapper.getCurrentVersion(7, 42)).thenReturn(current);
        when(validator.validateAndCanonicalize(any(), any(), any(), any()))
            .thenReturn(validated("same", definition));
        when(validator.parseDefinition("same")).thenReturn(definition);
        when(resolver.resolveWorkspace(root, current, null)).thenReturn(
            available("workspace:42", RecordCreationRecordType.person, false));

        var result = service.update("workspace:42", new RecordCreationTemplateUpdateRequestDto(
            names(), null, definition, true, 3, 2, 8, false));

        assertEquals(2, result.version());
        verify(mapper, never()).insertVersion(any());
        verify(mapper, never()).advanceSetRevision(anyInt(), anyString(), anyInt());
        verify(auditService, never()).record(anyString(), anyString(), any(), any(), any(), any());
    }

    @Test
    void contentUpdatePublishesOneImmutableVersion() throws Exception {
        RecordCreationTemplate root = root(42, "person", "enabled", 0, 3);
        RecordCreationTemplateVersion current = version(42, 2, "old");
        RecordCreationTemplateVersion published = version(42, 3, "new");
        published.setId(202);
        RecordCreationTemplateSet set = set(8, null);
        when(mapper.getRoot(7, 42)).thenReturn(root, root(42, "person", "enabled", 0, 4));
        when(mapper.getSetForUpdate(7, "person")).thenReturn(set);
        when(mapper.getRootForUpdate(7, 42)).thenReturn(root);
        when(mapper.getCurrentVersion(7, 42)).thenReturn(current, published);
        when(mapper.nextVersionNumber(7, 42)).thenReturn(3);
        when(validator.validateAndCanonicalize(any(), any(), any(), any()))
            .thenReturn(validated("new", definition));
        when(validator.parseDefinition("old")).thenReturn(definition);
        when(validator.parseDefinition("new")).thenReturn(definition);
        doAnswer(invocation -> {
            invocation.<RecordCreationTemplateVersion>getArgument(0).setId(202);
            return null;
        }).when(mapper).insertVersion(any());
        when(mapper.installCurrentVersion(7, 42, 202, 3, 11)).thenReturn(1);
        when(mapper.advanceSetRevision(7, "person", 8)).thenReturn(1);
        when(resolver.resolveWorkspace(any(), any(), org.mockito.ArgumentMatchers.isNull()))
            .thenReturn(available("workspace:42", RecordCreationRecordType.person, false));

        var result = service.update("workspace:42", new RecordCreationTemplateUpdateRequestDto(
            names(), null, definition, true, 3, 2, 8, false));

        assertEquals(3, result.version());
        verify(mapper).insertVersion(any());
        verify(mapper).installCurrentVersion(7, 42, 202, 3, 11);
        verify(mapper).advanceSetRevision(7, "person", 8);
    }

    @Test
    void archiveRequiresImpactConfirmationAndSystemPresetsAreImmutable() throws Exception {
        RecordCreationTemplateException system = assertThrows(
            RecordCreationTemplateException.class,
            () -> service.archive(
                "system:person:standard",
                new RecordCreationTemplateStateRequestDto(0, 0, true)));
        assertEquals("SYSTEM_TEMPLATE_IMMUTABLE", system.error().code());

        RecordCreationTemplate root = root(42, "person", "enabled", 0, 3);
        RecordCreationTemplateVersion current = version(42, 2, "current");
        when(mapper.getRoot(7, 42)).thenReturn(root);
        when(mapper.getSetForUpdate(7, "person")).thenReturn(set(8, 42));
        when(mapper.getRootForUpdate(7, 42)).thenReturn(root);
        when(mapper.getCurrentVersion(7, 42)).thenReturn(current);
        when(mapper.getSet(7, "person")).thenReturn(set(8, 42));
        when(mapper.listRoots(7, "person", false)).thenReturn(List.of(root));
        when(resolver.resolveWorkspace(root, current, null)).thenReturn(
            available("workspace:42", RecordCreationRecordType.person, false));
        when(resolver.resolveSystem(RecordCreationRecordType.person, null)).thenReturn(
            available("system:person:standard", RecordCreationRecordType.person, true));

        RecordCreationTemplateException confirmation = assertThrows(
            RecordCreationTemplateException.class,
            () -> service.archive(
                "workspace:42",
                new RecordCreationTemplateStateRequestDto(3, 8, false)));

        assertEquals("TEMPLATE_IMPACT_CONFIRMATION_REQUIRED", confirmation.error().code());
        assertTrue(confirmation.error().impact().requiresConfirmation());
        verify(mapper, never()).updateStatus(anyInt(), anyInt(), anyString(), any(), anyInt(), anyInt());
    }

    @Test
    void resetArchivesEveryRootClearsDefaultAndSelectsSystem() {
        RecordCreationTemplate root = root(42, "person", "enabled", 0, 3);
        RecordCreationTemplateSet locked = set(8, 42);
        RecordCreationTemplateSet after = set(9, null);
        when(mapper.getSetForUpdate(7, "person")).thenReturn(locked);
        when(mapper.listRootsForUpdate(7, "person")).thenReturn(List.of(root));
        when(mapper.updateStatus(anyInt(), anyInt(), anyString(), any(), anyInt(), anyInt())).thenReturn(1);
        when(mapper.setDefault(7, "person", null, 8)).thenReturn(1);
        when(mapper.getSet(7, "person")).thenReturn(after);
        when(mapper.listRoots(7, "person", false)).thenReturn(List.of());
        when(resolver.resolveSystem(RecordCreationRecordType.person, null)).thenReturn(
            available("system:person:standard", RecordCreationRecordType.person, true));

        var catalog = service.reset(new RecordCreationTemplateResetRequestDto(
            RecordCreationRecordType.person, 8, true));

        assertEquals("system:person:standard", catalog.selectedTemplateId());
        assertEquals(9, catalog.setRevision());
        verify(mapper).updateStatus(
            org.mockito.ArgumentMatchers.eq(7),
            org.mockito.ArgumentMatchers.eq(42),
            org.mockito.ArgumentMatchers.eq("archived"),
            any(),
            org.mockito.ArgumentMatchers.eq(3),
            org.mockito.ArgumentMatchers.eq(11));
        verify(mapper).setDefault(7, "person", null, 8);
    }

    @Test
    void deterministicSelectionSkipsUnavailableExplicitDefault() throws Exception {
        RecordCreationTemplate first = root(1, "person", "enabled", 1, 1);
        RecordCreationTemplate explicit = root(2, "person", "enabled", 0, 1);
        RecordCreationTemplateVersion firstVersion = version(1, 1, "first");
        RecordCreationTemplateVersion explicitVersion = version(2, 1, "explicit");
        when(mapper.getSet(7, "person")).thenReturn(set(5, 2));
        when(mapper.listRoots(7, "person", false)).thenReturn(List.of(first, explicit));
        when(mapper.getCurrentVersion(7, 1)).thenReturn(firstVersion);
        when(mapper.getCurrentVersion(7, 2)).thenReturn(explicitVersion);
        when(resolver.resolveWorkspace(first, firstVersion, null)).thenReturn(
            available("workspace:1", RecordCreationRecordType.person, false));
        when(resolver.resolveWorkspace(explicit, explicitVersion, null)).thenReturn(
            unavailable("workspace:2"));
        when(resolver.resolveSystem(RecordCreationRecordType.person, null)).thenReturn(
            available("system:person:standard", RecordCreationRecordType.person, true));

        var catalog = service.catalog(RecordCreationRecordType.person);

        assertEquals("workspace:1", catalog.selectedTemplateId());
        assertTrue(catalog.partial());
    }

    @Test
    void duplicatePublishesDisabledCopyFromSystemPreset() throws Exception {
        RecordCreationTemplateSet locked = set(6, null);
        when(mapper.getSetForUpdate(7, "person")).thenReturn(locked);
        when(mapper.listRootsForUpdate(7, "person")).thenReturn(List.of());
        when(validator.validateAndCanonicalize(any(), any(), any(), any()))
            .thenReturn(validated("copy", registry.systemPreset(RecordCreationRecordType.person).definition()));
        doAnswer(invocation -> {
            invocation.<RecordCreationTemplate>getArgument(0).setId(44);
            return null;
        }).when(mapper).insertRoot(any());
        doAnswer(invocation -> {
            invocation.<RecordCreationTemplateVersion>getArgument(0).setId(301);
            return null;
        }).when(mapper).insertVersion(any());
        when(mapper.installCurrentVersion(7, 44, 301, 0, 11)).thenReturn(1);
        when(mapper.advanceSetRevision(7, "person", 6)).thenReturn(1);
        RecordCreationTemplate stored = root(44, "person", "disabled", 0, 1);
        RecordCreationTemplateVersion version = version(44, 1, "copy");
        when(mapper.getRoot(7, 44)).thenReturn(stored);
        when(mapper.getCurrentVersion(7, 44)).thenReturn(version);
        when(validator.parseDefinition("copy"))
            .thenReturn(registry.systemPreset(RecordCreationRecordType.person).definition());
        when(resolver.resolveWorkspace(stored, version, null)).thenReturn(
            available("workspace:44", RecordCreationRecordType.person, false));

        var copy = service.duplicate(
            "system:person:standard",
            new RecordCreationTemplateDuplicateRequestDto(names(), null, 1, 6));

        assertEquals("workspace:44", copy.id());
        assertEquals("disabled", copy.status().name());
        verify(mapper).advanceSetRevision(7, "person", 6);
        verify(auditService).record(
            org.mockito.ArgumentMatchers.eq("record_creation_template.duplicate"),
            anyString(), anyInt(), anyString(), anyString(), any());
    }

    @Test
    void reorderRequiresExactActiveTemplateSetAndAdvancesOneRevision() throws Exception {
        RecordCreationTemplate first = root(1, "company", "enabled", 0, 1);
        RecordCreationTemplate second = root(2, "company", "disabled", 1, 1);
        RecordCreationTemplateVersion firstVersion = version(1, 1, "first");
        RecordCreationTemplateVersion secondVersion = version(2, 1, "second");
        when(mapper.getSetForUpdate(7, "company")).thenReturn(set(3, null));
        when(mapper.listRootsForUpdate(7, "company")).thenReturn(List.of(first, second));
        when(mapper.advanceSetRevision(7, "company", 3)).thenReturn(1);
        when(mapper.getSet(7, "company")).thenReturn(set(4, null));
        when(mapper.listRoots(7, "company", false)).thenReturn(List.of(second, first));
        when(mapper.getCurrentVersion(7, 1)).thenReturn(firstVersion);
        when(mapper.getCurrentVersion(7, 2)).thenReturn(secondVersion);
        when(resolver.resolveWorkspace(first, firstVersion, null)).thenReturn(
            available("workspace:1", RecordCreationRecordType.company, false));
        when(resolver.resolveWorkspace(second, secondVersion, null)).thenReturn(
            available("workspace:2", RecordCreationRecordType.company, false));
        when(resolver.resolveSystem(RecordCreationRecordType.company, null)).thenReturn(
            available("system:company:standard", RecordCreationRecordType.company, true));

        var reordered = service.reorder(new RecordCreationTemplateReorderRequestDto(
            RecordCreationRecordType.company, List.of("workspace:2", "workspace:1"), 3));

        assertEquals(List.of("workspace:2", "workspace:1", "system:company:standard"),
            reordered.stream().map(summary -> summary.id()).toList());
        verify(mapper).updatePositions(7, 2, 0, 11);
        verify(mapper).updatePositions(7, 1, 1, 11);
        verify(mapper).advanceSetRevision(7, "company", 3);

        RecordCreationTemplateException invalid = assertThrows(
            RecordCreationTemplateException.class,
            () -> service.reorder(new RecordCreationTemplateReorderRequestDto(
                RecordCreationRecordType.company, List.of("workspace:1", "workspace:1"), 3)));
        assertEquals("TEMPLATE_ORDER_INVALID", invalid.error().code());
    }

    @Test
    void defaultRequiresEnabledAvailableWorkspaceTemplate() throws Exception {
        RecordCreationTemplate root = root(42, "deal", "enabled", 0, 2);
        RecordCreationTemplateVersion version = version(42, 1, "deal");
        when(mapper.getSetForUpdate(7, "deal")).thenReturn(set(5, null));
        when(mapper.getRootForUpdate(7, 42)).thenReturn(root);
        when(mapper.getCurrentVersion(7, 42)).thenReturn(version);
        when(resolver.resolveWorkspace(root, version, null)).thenReturn(
            available("workspace:42", RecordCreationRecordType.deal, false));
        when(mapper.setDefault(7, "deal", 42, 5)).thenReturn(1);
        when(mapper.getSet(7, "deal")).thenReturn(set(6, 42));
        when(mapper.listRoots(7, "deal", false)).thenReturn(List.of(root));
        when(resolver.resolveSystem(RecordCreationRecordType.deal, null)).thenReturn(
            available("system:deal:standard", RecordCreationRecordType.deal, true));

        var catalog = service.setDefault(new RecordCreationTemplateDefaultRequestDto(
            RecordCreationRecordType.deal, "workspace:42", 5));

        assertEquals("workspace:42", catalog.selectedTemplateId());
        verify(mapper).setDefault(7, "deal", 42, 5);
        verify(auditService).record(
            org.mockito.ArgumentMatchers.eq("record_creation_template.default_set"),
            anyString(), anyInt(), anyString(), anyString(), any());
    }

    @Test
    void updateCanEnableAndDisableWithoutPublishingContent() throws Exception {
        assertStatusOnlyUpdate("disabled", true, "enabled");
        assertStatusOnlyUpdate("enabled", false, "disabled");

        verify(mapper, times(2)).updateStatus(
            org.mockito.ArgumentMatchers.eq(7),
            org.mockito.ArgumentMatchers.eq(42),
            anyString(),
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.eq(3),
            org.mockito.ArgumentMatchers.eq(11));
        verify(mapper, never()).insertVersion(any());
    }

    @Test
    void archiveAndRestoreUseCasAdvanceRevisionAndAudit() throws Exception {
        RecordCreationTemplate enabled = root(42, "person", "enabled", 0, 3);
        RecordCreationTemplate archived = root(42, "person", "archived", 0, 4);
        RecordCreationTemplate restored = root(42, "person", "disabled", 0, 5);
        RecordCreationTemplateVersion version = version(42, 2, "current");
        when(mapper.getRoot(7, 42)).thenReturn(enabled, archived, archived, restored);
        when(mapper.getSetForUpdate(7, "person")).thenReturn(set(8, 42), set(9, null));
        when(mapper.getRootForUpdate(7, 42)).thenReturn(enabled, archived);
        when(mapper.getCurrentVersion(7, 42)).thenReturn(version);
        when(mapper.getSet(7, "person")).thenReturn(set(8, 42));
        when(mapper.listRoots(7, "person", false)).thenReturn(List.of(enabled));
        when(resolver.resolveWorkspace(enabled, version, null)).thenReturn(
            available("workspace:42", RecordCreationRecordType.person, false));
        when(resolver.resolveWorkspace(archived, version, null)).thenReturn(
            available("workspace:42", RecordCreationRecordType.person, false));
        when(resolver.resolveWorkspace(restored, version, null)).thenReturn(
            available("workspace:42", RecordCreationRecordType.person, false));
        when(resolver.resolveSystem(RecordCreationRecordType.person, null)).thenReturn(
            available("system:person:standard", RecordCreationRecordType.person, true));
        when(mapper.updateStatus(7, 42, "archived", java.time.LocalDateTime.of(
            2026, 8, 31, 10, 0), 3, 11)).thenReturn(1);
        when(mapper.updateStatus(7, 42, "disabled", null, 4, 11)).thenReturn(1);
        when(mapper.advanceSetRevision(7, "person", 8)).thenReturn(1);
        when(mapper.advanceSetRevision(7, "person", 9)).thenReturn(1);
        when(validator.parseDefinition("current")).thenReturn(definition);

        var archivedResult = service.archive(
            "workspace:42", new RecordCreationTemplateStateRequestDto(3, 8, true));
        var restoredResult = service.restore(
            "workspace:42", new RecordCreationTemplateStateRequestDto(4, 9, true));

        assertEquals("archived", archivedResult.status().name());
        assertEquals("disabled", restoredResult.status().name());
        verify(auditService).record(
            org.mockito.ArgumentMatchers.eq("record_creation_template.archive"),
            anyString(), anyInt(), anyString(), anyString(), any());
        verify(auditService).record(
            org.mockito.ArgumentMatchers.eq("record_creation_template.restore"),
            anyString(), anyInt(), anyString(), anyString(), any());
    }

    @Test
    void staleSetRevisionReturnsCurrentCasStateWithoutWriting() {
        when(mapper.getSetForUpdate(7, "person")).thenReturn(set(9, null));

        RecordCreationTemplateException stale = assertThrows(
            RecordCreationTemplateException.class,
            () -> service.create(new RecordCreationTemplateCreateRequestDto(
                RecordCreationRecordType.person, names(), null, definition, true, 8)));

        assertEquals("TEMPLATE_SET_STALE", stale.error().code());
        assertEquals(9, stale.error().currentSetRevision());
        verify(mapper, never()).insertRoot(any());
        verify(auditService, never()).record(anyString(), anyString(), any(), any(), any(), any());
    }

    private void assertStatusOnlyUpdate(String oldStatus, boolean enabled, String newStatus)
            throws Exception {
        RecordCreationTemplate root = root(42, "person", oldStatus, 0, 3);
        RecordCreationTemplate stored = root(42, "person", newStatus, 0, 4);
        RecordCreationTemplateVersion current = version(42, 2, "same");
        when(mapper.getRoot(7, 42)).thenReturn(root, stored);
        when(mapper.getSetForUpdate(7, "person")).thenReturn(set(8, null));
        when(mapper.getRootForUpdate(7, 42)).thenReturn(root);
        when(mapper.getCurrentVersion(7, 42)).thenReturn(current);
        when(validator.validateAndCanonicalize(any(), any(), any(), any()))
            .thenReturn(validated("same", definition));
        when(validator.parseDefinition("same")).thenReturn(definition);
        when(mapper.updateStatus(7, 42, newStatus, null, 3, 11)).thenReturn(1);
        when(mapper.advanceSetRevision(7, "person", 8)).thenReturn(1);
        when(resolver.resolveWorkspace(stored, current, null)).thenReturn(
            available("workspace:42", RecordCreationRecordType.person, false));

        var result = service.update("workspace:42", new RecordCreationTemplateUpdateRequestDto(
            names(), null, definition, enabled, 3, 2, 8, false));

        assertEquals(newStatus, result.status().name());
    }

    private RecordCreationTemplateValidator.ValidatedTemplate validated(
            String json,
            RecordCreationTemplateDefinitionDto definition) throws Exception {
        return new RecordCreationTemplateValidator.ValidatedTemplate(
            names(), null, definition, json, hash(json));
    }

    private static RecordCreationTemplateDefinitionDto definition(String field) {
        return new RecordCreationTemplateDefinitionDto(1, List.of(
            new RecordCreationTemplateGroupDto(
                "basics",
                new LocalizedTextDto("Basics", "基本情報"),
                null,
                List.of(new RecordCreationTemplateFieldDto(field, false, null, null, null)))));
    }

    private static LocalizedTextDto names() {
        return new LocalizedTextDto("Template", "テンプレート");
    }

    private static RecordCreationTemplateSet set(int revision, Integer defaultId) {
        RecordCreationTemplateSet set = new RecordCreationTemplateSet();
        set.setWorkspaceId(7);
        set.setRecordType("person");
        set.setRevision(revision);
        set.setDefaultTemplateId(defaultId);
        return set;
    }

    private static RecordCreationTemplate root(
            int id,
            String type,
            String status,
            int position,
            int revision) {
        RecordCreationTemplate root = new RecordCreationTemplate();
        root.setId(id);
        root.setWorkspaceId(7);
        root.setRecordType(type);
        root.setStatus(status);
        root.setPosition(position);
        root.setRevision(revision);
        return root;
    }

    private static RecordCreationTemplateVersion version(
            int templateId,
            int versionNumber,
            String json) throws Exception {
        RecordCreationTemplateVersion version = new RecordCreationTemplateVersion();
        version.setId(100 + versionNumber);
        version.setWorkspaceId(7);
        version.setTemplateId(templateId);
        version.setVersionNumber(versionNumber);
        version.setNameEn("Template");
        version.setNameJa("テンプレート");
        version.setDefinitionJson(json);
        version.setDefinitionHash(hash(json));
        return version;
    }

    private static ResolvedCreationTemplateDto available(
            String id,
            RecordCreationRecordType type,
            boolean system) {
        return new ResolvedCreationTemplateDto(
            id, type, system, 1, names(), null,
            RecordCreationTemplateAvailability.available, List.of(), List.of());
    }

    private static ResolvedCreationTemplateDto unavailable(String id) {
        return new ResolvedCreationTemplateDto(
            id, RecordCreationRecordType.person, false, 1, names(), null,
            RecordCreationTemplateAvailability.unavailable, List.of(), List.of());
    }

    private static byte[] hash(String value) throws Exception {
        return MessageDigest.getInstance("SHA-256")
            .digest(value.getBytes(StandardCharsets.UTF_8));
    }
}
