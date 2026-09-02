package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.beans.RecordCreationTemplate;
import ooo.klae.connex.backend.beans.RecordCreationTemplateSet;
import ooo.klae.connex.backend.beans.RecordCreationTemplateVersion;
import ooo.klae.connex.backend.dto.recordcreation.LocalizedTextDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationContextDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationWarningDto;
import ooo.klae.connex.backend.dto.recordcreation.ResolvedCreationTemplateDto;
import ooo.klae.connex.backend.exceptions.RecordCreationTemplateException;
import ooo.klae.connex.backend.mappers.RecordCreationTemplateMapper;
import ooo.klae.connex.backend.recordcreation.RecordCreationEntryPoint;
import ooo.klae.connex.backend.recordcreation.RecordCreationFieldRegistry;
import ooo.klae.connex.backend.recordcreation.RecordCreationRecordType;
import ooo.klae.connex.backend.recordcreation.RecordCreationTemplateAvailability;

class RecordCreationPresetServiceTest {
    private final RecordCreationTemplateMapper mapper = mock(RecordCreationTemplateMapper.class);
    private final RecordCreationTemplateResolver resolver = mock(RecordCreationTemplateResolver.class);
    private final WorkspaceService workspaceService = mock(WorkspaceService.class);
    private final RecordCreationFieldRegistry registry = new RecordCreationFieldRegistry();

    private RecordCreationPresetService service;

    @BeforeEach
    void setUp() {
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(7);
        service = new RecordCreationPresetService(mapper, resolver, registry, workspaceService);
    }

    @Test
    void explicitAvailableDefaultWinsAndEachTemplateResolvesOnce() {
        RecordCreationContextDto context = new RecordCreationContextDto(55);
        RecordCreationTemplate first = root(1, "enabled", 0);
        RecordCreationTemplate selected = root(2, "enabled", 1);
        RecordCreationTemplateVersion firstVersion = version(1);
        RecordCreationTemplateVersion selectedVersion = version(2);
        when(mapper.getSet(7, "person")).thenReturn(set(9, 2));
        when(mapper.listRoots(7, "person", false)).thenReturn(List.of(first, selected));
        when(mapper.getCurrentVersion(7, 1)).thenReturn(firstVersion);
        when(mapper.getCurrentVersion(7, 2)).thenReturn(selectedVersion);
        when(resolver.resolveWorkspace(first, firstVersion, context)).thenReturn(
            resolved("workspace:1", false, RecordCreationTemplateAvailability.available, List.of()));
        when(resolver.resolveWorkspace(selected, selectedVersion, context)).thenReturn(
            resolved("workspace:2", false, RecordCreationTemplateAvailability.available, List.of()));
        when(resolver.resolveSystem(RecordCreationRecordType.person, context)).thenReturn(
            resolved("system:person:standard", true, RecordCreationTemplateAvailability.available, List.of()));

        var catalog = service.persons(RecordCreationEntryPoint.record_detail, 55);

        assertEquals(9, catalog.setRevision());
        assertEquals("workspace:2", catalog.selectedTemplateId());
        assertEquals(List.of("workspace:1", "workspace:2", "system:person:standard"),
            catalog.templates().stream().map(ResolvedCreationTemplateDto::id).toList());
        verify(resolver, times(1)).resolveWorkspace(first, firstVersion, context);
        verify(resolver, times(1)).resolveWorkspace(selected, selectedVersion, context);
        verify(resolver, times(1)).resolveSystem(RecordCreationRecordType.person, context);
    }

    @Test
    void firstEnabledAvailableFallsBackAndDisabledDraftDoesNotLeak() {
        RecordCreationTemplate disabled = root(1, "disabled", 0);
        RecordCreationTemplate unavailable = root(2, "enabled", 1);
        RecordCreationTemplate available = root(3, "enabled", 2);
        stub(RecordCreationRecordType.person, disabled,
            resolved("workspace:1", false, RecordCreationTemplateAvailability.available, List.of()));
        stub(RecordCreationRecordType.person, unavailable,
            resolved("workspace:2", false, RecordCreationTemplateAvailability.unavailable,
                List.of(new RecordCreationWarningDto("TEMPLATE_FIELD_UNAVAILABLE", "workspace:2", "email", null))));
        stub(RecordCreationRecordType.person, available,
            resolved("workspace:3", false, RecordCreationTemplateAvailability.available, List.of()));
        when(mapper.listRoots(7, "person", false)).thenReturn(List.of(disabled, unavailable, available));
        when(resolver.resolveSystem(RecordCreationRecordType.person, new RecordCreationContextDto(null)))
            .thenReturn(resolved(
                "system:person:standard", true, RecordCreationTemplateAvailability.available, List.of()));

        var catalog = service.persons(RecordCreationEntryPoint.quick_create, null);

        assertEquals("workspace:3", catalog.selectedTemplateId());
        assertEquals(List.of("workspace:2", "workspace:3", "system:person:standard"),
            catalog.templates().stream().map(ResolvedCreationTemplateDto::id).toList());
        assertTrue(catalog.partial());
        assertFalse(catalog.templates().stream().anyMatch(template -> template.id().equals("workspace:1")));
    }

    @Test
    void systemIsTheFallbackAndAnUnavailableSystemFailsClosed() {
        RecordCreationContextDto context = new RecordCreationContextDto(null);
        RecordCreationTemplate unavailable = root(4, "enabled", 0);
        stub(RecordCreationRecordType.person, unavailable,
            resolved("workspace:4", false, RecordCreationTemplateAvailability.unavailable, List.of()));
        when(mapper.listRoots(7, "person", false)).thenReturn(List.of(unavailable));
        when(resolver.resolveSystem(RecordCreationRecordType.person, context)).thenReturn(
            resolved("system:person:standard", true, RecordCreationTemplateAvailability.available, List.of()));

        assertEquals("system:person:standard",
            service.persons(RecordCreationEntryPoint.record_list, null).selectedTemplateId());

        when(resolver.resolveSystem(RecordCreationRecordType.person, context)).thenReturn(
            resolved("system:person:standard", true, RecordCreationTemplateAvailability.unavailable, List.of()));
        RecordCreationTemplateException exception = assertThrows(
            RecordCreationTemplateException.class,
            () -> service.persons(RecordCreationEntryPoint.record_list, null));
        assertEquals("TEMPLATE_CATALOG_UNAVAILABLE", exception.error().code());
    }

    @Test
    void runtimeOrderingUsesPersistedPositionThenId() {
        RecordCreationContextDto context = new RecordCreationContextDto(null);
        RecordCreationTemplate later = root(9, "enabled", 5);
        RecordCreationTemplate samePositionHigherId = root(8, "enabled", 2);
        RecordCreationTemplate samePositionLowerId = root(3, "enabled", 2);
        for (RecordCreationTemplate root : List.of(later, samePositionHigherId, samePositionLowerId)) {
            stub(RecordCreationRecordType.company, root,
                resolved("workspace:" + root.getId(), false,
                    RecordCreationTemplateAvailability.available, List.of()));
        }
        when(mapper.listRoots(7, "company", false)).thenReturn(
            List.of(later, samePositionHigherId, samePositionLowerId));
        when(resolver.resolveSystem(RecordCreationRecordType.company, context)).thenReturn(
            companyResolved("system:company:standard", true));

        var catalog = service.companies(RecordCreationEntryPoint.calendar);

        assertEquals(List.of("workspace:3", "workspace:8", "workspace:9", "system:company:standard"),
            catalog.templates().stream().map(ResolvedCreationTemplateDto::id).toList());
    }

    private void stub(
            RecordCreationRecordType recordType,
            RecordCreationTemplate root,
            ResolvedCreationTemplateDto resolved) {
        root.setRecordType(recordType.name());
        RecordCreationTemplateVersion version = version(root.getId());
        when(mapper.getCurrentVersion(7, root.getId())).thenReturn(version);
        when(resolver.resolveWorkspace(root, version, new RecordCreationContextDto(null)))
            .thenReturn(resolved.recordType() == recordType
                ? resolved
                : new ResolvedCreationTemplateDto(
                    resolved.id(), recordType, resolved.system(), resolved.version(),
                    resolved.name(), resolved.description(), resolved.availability(),
                    resolved.groups(), resolved.warnings()));
    }

    private static RecordCreationTemplate root(int id, String status, int position) {
        RecordCreationTemplate root = new RecordCreationTemplate();
        root.setId(id);
        root.setRecordType("person");
        root.setStatus(status);
        root.setPosition(position);
        return root;
    }

    private static RecordCreationTemplateVersion version(int rootId) {
        RecordCreationTemplateVersion version = new RecordCreationTemplateVersion();
        version.setTemplateId(rootId);
        version.setVersionNumber(1);
        return version;
    }

    private static RecordCreationTemplateSet set(int revision, Integer defaultId) {
        RecordCreationTemplateSet set = new RecordCreationTemplateSet();
        set.setRevision(revision);
        set.setDefaultTemplateId(defaultId);
        return set;
    }

    private static ResolvedCreationTemplateDto resolved(
            String id,
            boolean system,
            RecordCreationTemplateAvailability availability,
            List<RecordCreationWarningDto> warnings) {
        return new ResolvedCreationTemplateDto(
            id, RecordCreationRecordType.person, system, 1,
            new LocalizedTextDto(id, id), null, availability, List.of(), warnings);
    }

    private static ResolvedCreationTemplateDto companyResolved(String id, boolean system) {
        return new ResolvedCreationTemplateDto(
            id, RecordCreationRecordType.company, system, 1,
            new LocalizedTextDto(id, id), null,
            RecordCreationTemplateAvailability.available, List.of(), List.of());
    }
}
