package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Tag;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.services.RecordVisibilityScopeControlAccess.RecordVisibilityScope;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.TenantWorkScope;
import ooo.klae.connex.backend.tenant.TenantCatalogResolver;

/** Proves operation ownership, control-read counts and lock admission through real scope boundaries. */
class CompanyOperationScopeTest {
    private final TenantContext context = new TenantContext();
    private final WorkspaceMapper control = mock(WorkspaceMapper.class);
    private final WorkspaceService workspaces = mock(WorkspaceService.class);
    private final CompanyMapper companies = mock(CompanyMapper.class);
    private final DuplicateDecisionLockService duplicates = mock(DuplicateDecisionLockService.class);
    private RecordVisibilityScopeControlAccess scopes;

    @BeforeEach
    void setUp() {
        context.clear();
        Workspace anchor = new Workspace();
        anchor.setId(7);
        anchor.setOrgId(90);
        anchor.setName("Anchor");
        Workspace sibling = new Workspace();
        sibling.setId(11);
        sibling.setOrgId(90);
        sibling.setName("Sibling");
        when(control.findOrganizationWorkspacesForShare(7)).thenReturn(List.of(anchor, sibling));
        when(workspaces.getCurrentWorkspaceId()).thenReturn(7);
        when(workspaces.getCurrentUserId()).thenReturn(17);
        scopes = new RecordVisibilityScopeControlAccess(new ShareWorkspaceControlAccess(control,
            new TenantWorkScope(context, mock(TenantCatalogResolver.class), control), context,
            mock(org.springframework.transaction.PlatformTransactionManager.class)), context);
    }

    @AfterEach
    void clear() {
        context.clear();
    }

    @ParameterizedTest
    @ValueSource(strings = {"update", "owner", "archive", "restore", "addTag", "removeTag", "inverseTag"})
    void mutationsResolveBeforeTheirFirstLockAndReuseScopeForReadback(String operation) {
        Company company = company();
        when(companies.existsOwned(7, 41)).thenReturn(true);
        when(companies.getCompanyById(7, "[7,11]", 41)).thenReturn(company);
        when(companies.getOwnedCompanyByIdForUpdate(7, 41)).thenReturn(company);
        when(companies.getOwnedArchivedCompanyById(7, 41)).thenReturn(company);
        when(companies.lockById(7, 41)).thenReturn(41);
        when(companies.archive(7, 41)).thenReturn(1);
        when(companies.restore(7, 41)).thenReturn(1);
        when(companies.addTag(7, 41, 23)).thenReturn(1);
        when(companies.removeTag(7, 41, 23)).thenReturn(1);
        var tags = mock(ooo.klae.connex.backend.mappers.TagMapper.class);
        Tag tag = new Tag();
        tag.setId(23);
        tag.setName("Tag");
        when(tags.getTagById(7, 23)).thenReturn(tag);
        CompanyService service = new CompanyService(
            companies,
            tags,
            mock(ooo.klae.connex.backend.mappers.PersonMapper.class),
            mock(ooo.klae.connex.backend.mappers.DealMapper.class),
            mock(ooo.klae.connex.backend.mappers.ActivityMapper.class),
            mock(ooo.klae.connex.backend.mappers.NoteMapper.class),
            mock(ooo.klae.connex.backend.mappers.TaskMapper.class),
            mock(ooo.klae.connex.backend.services.AuthService.class),
            mock(ooo.klae.connex.backend.services.AuditService.class),
            mock(ooo.klae.connex.backend.notifications.NotificationChangePublisher.class),
            mock(ooo.klae.connex.backend.services.RuleTriggerPublisher.class),
            workspaces,
            mock(ooo.klae.connex.backend.services.CustomFieldValueService.class),
            mock(ooo.klae.connex.backend.services.SegmentService.class),
            mock(ooo.klae.connex.backend.services.ReferenceService.class),
            Clock.systemUTC(),
            mock(ooo.klae.connex.backend.storage.ManagedObjectService.class),
            mock(ooo.klae.connex.backend.services.IdentityIntakeService.class),
            mock(ooo.klae.connex.backend.services.MatchingService.class),
            mock(ooo.klae.connex.backend.services.DuplicatePreflightService.class),
            duplicates,
            mock(ooo.klae.connex.backend.services.RecordCreationAugmentationService.class),
            scopes);
        switch (operation) {
            case "update" -> service.updateCompany(41, company());
            case "owner" -> service.updateOwner(41, 17);
            case "archive" -> service.archiveCompany(41);
            case "restore" -> service.restoreCompany(41);
            case "addTag" -> service.addTag(41, 23);
            case "removeTag" -> service.removeTag(41, 23);
            case "inverseTag" -> service.removeTagIfUnchanged(41, 23);
            default -> fail(operation);
        }
        var order = inOrder(control, duplicates, companies, workspaces);
        order.verify(control).findOrganizationWorkspacesForShare(7);
        if (Set.of("update", "archive", "restore").contains(operation)) {
            order.verify(duplicates).lockCurrentOrganization();
        } else if (operation.equals("owner")) {
            order.verify(workspaces).lockAndRequireMember(7, 17);
            order.verify(companies).getOwnedCompanyByIdForUpdate(7, 41);
        }
        verify(control).findOrganizationWorkspacesForShare(7);
    }

    @ParameterizedTest
    @ValueSource(strings = {"addTag", "removeTag", "archive", "restore", "owner"})
    void bulkRecordsKeepOneReadAcrossSeparateTransactions(String operation) {
        List<Integer> ids = List.of(41, 42, 43, 44, 45);
        when(companies.existsOwned(eq(7), anyInt())).thenReturn(true);
        when(companies.existsOwnedArchived(eq(7), anyInt())).thenReturn(true);
        var tags = mock(ooo.klae.connex.backend.mappers.TagMapper.class);
        when(tags.getTagById(7, 23)).thenReturn(new Tag());
        CompanyService delegate = mock(CompanyService.class);
        UnitTransactions manager = new UnitTransactions();
        java.util.function.Supplier<Company> one = () -> new TransactionTemplate(manager).execute(status -> {
            assertEquals(List.of(7, 11), scopes.getForWorkspace(7).workspaceIds());
            return company();
        });
        when(delegate.addTag(anyInt(), eq(23))).thenAnswer(call -> { one.get(); return true; });
        when(delegate.removeTag(anyInt(), eq(23))).thenAnswer(call -> { one.get(); return true; });
        when(delegate.archiveCompany(anyInt())).thenAnswer(call -> one.get());
        when(delegate.restoreCompany(anyInt())).thenAnswer(call -> one.get());
        when(delegate.updateOwner(anyInt(), eq(17))).thenAnswer(call -> one.get());
        BulkOperationService service = new BulkOperationService(
            workspaces,
            mock(ooo.klae.connex.backend.services.PersonService.class),
            delegate,
            mock(ooo.klae.connex.backend.services.DealService.class),
            mock(ooo.klae.connex.backend.mappers.PersonMapper.class),
            companies,
            mock(ooo.klae.connex.backend.mappers.DealMapper.class),
            tags,
            mock(ooo.klae.connex.backend.mappers.PipelineMapper.class),
            scopes);
        var result = switch (operation) {
            case "addTag" -> service.addTagToCompanies(ids, 23);
            case "removeTag" -> service.removeTagFromCompanies(ids, 23);
            case "archive" -> service.archiveCompanies(ids);
            case "restore" -> service.restoreCompanies(ids);
            case "owner" -> service.assignOwnerToCompanies(ids, 17);
            default -> throw new IllegalArgumentException(operation);
        };
        assertEquals(5, result.getSucceeded());
        assertEquals(5, manager.begins.get());
        verify(control).findOrganizationWorkspacesForShare(7);
    }

    @ParameterizedTest
    @ValueSource(strings = {"addTag", "removeTag", "archive", "restore", "owner", "dealOwner"})
    void emptyOrUnownedBulkSelectionsNeverReadTheScope(String operation) {
        var tags = mock(ooo.klae.connex.backend.mappers.TagMapper.class);
        when(tags.getTagById(7, 23)).thenReturn(new Tag());
        var companyService = mock(CompanyService.class);
        var dealService = mock(DealService.class);
        var service = new BulkOperationService(workspaces, mock(PersonService.class), companyService, dealService,
            mock(ooo.klae.connex.backend.mappers.PersonMapper.class), companies,
            mock(ooo.klae.connex.backend.mappers.DealMapper.class), tags,
            mock(ooo.klae.connex.backend.mappers.PipelineMapper.class), scopes);
        for (List<Integer> ids : List.of(List.<Integer>of(), List.of(41, 42))) {
            var result = switch (operation) {
                case "addTag" -> service.addTagToCompanies(ids, 23);
                case "removeTag" -> service.removeTagFromCompanies(ids, 23);
                case "archive" -> service.archiveCompanies(ids);
                case "restore" -> service.restoreCompanies(ids);
                case "owner" -> service.assignOwnerToCompanies(ids, 17);
                case "dealOwner" -> service.assignOwnerToDeals(ids, 17);
                default -> throw new AssertionError(operation);
            };
            assertEquals(0, result.getSucceeded());
            assertEquals(ids.size(), result.getFailed());
        }
        verifyNoInteractions(control, companyService, dealService);
    }

    @Test
    void oneHundredReferenceTokensAcrossThreeWritesReadOnce() {
        when(companies.exists(eq(7), eq("[7,11]"), anyInt())).thenReturn(true);
        var references = mock(ooo.klae.connex.backend.mappers.EntityReferenceMapper.class);
        ReferenceService service = new ReferenceService(
            references,
            workspaces,
            mock(ooo.klae.connex.backend.mappers.PersonMapper.class),
            mock(ooo.klae.connex.backend.mappers.DealMapper.class),
            companies,
            mock(ooo.klae.connex.backend.mappers.NoteMapper.class),
            mock(ooo.klae.connex.backend.mappers.AttachmentMapper.class),
            mock(ooo.klae.connex.backend.mappers.TaskMapper.class),
            mock(ooo.klae.connex.backend.mappers.ActivityMapper.class),
            mock(ooo.klae.connex.backend.mappers.WorkspaceMapper.class),
            scopes);
        String content = IntStream.rangeClosed(1, 100).mapToObj(id -> "[Company](company:" + id + ")")
            .collect(java.util.stream.Collectors.joining(" "));
        scopes.withWorkspaceScope(7, () -> {
            for (int source : List.of(21, 22, 23)) {
                service.syncReferences(7, ReferenceService.SOURCE_NOTE, source, content);
            }
            return null;
        });
        verify(companies, times(300)).exists(eq(7), eq("[7,11]"), anyInt());
        verify(control).findOrganizationWorkspacesForShare(7);
    }

    @Test
    void failedScopeRefusesBeforeMutationLocks() {
        var tags = mock(ooo.klae.connex.backend.mappers.TagMapper.class);
        CompanyService service = new CompanyService(
            companies,
            tags,
            mock(ooo.klae.connex.backend.mappers.PersonMapper.class),
            mock(ooo.klae.connex.backend.mappers.DealMapper.class),
            mock(ooo.klae.connex.backend.mappers.ActivityMapper.class),
            mock(ooo.klae.connex.backend.mappers.NoteMapper.class),
            mock(ooo.klae.connex.backend.mappers.TaskMapper.class),
            mock(ooo.klae.connex.backend.services.AuthService.class),
            mock(ooo.klae.connex.backend.services.AuditService.class),
            mock(ooo.klae.connex.backend.notifications.NotificationChangePublisher.class),
            mock(ooo.klae.connex.backend.services.RuleTriggerPublisher.class),
            workspaces,
            mock(ooo.klae.connex.backend.services.CustomFieldValueService.class),
            mock(ooo.klae.connex.backend.services.SegmentService.class),
            mock(ooo.klae.connex.backend.services.ReferenceService.class),
            Clock.systemUTC(),
            mock(ooo.klae.connex.backend.storage.ManagedObjectService.class),
            mock(ooo.klae.connex.backend.services.IdentityIntakeService.class),
            mock(ooo.klae.connex.backend.services.MatchingService.class),
            mock(ooo.klae.connex.backend.services.DuplicatePreflightService.class),
            duplicates,
            mock(ooo.klae.connex.backend.services.RecordCreationAugmentationService.class),
            scopes);
        when(control.findOrganizationWorkspacesForShare(7)).thenReturn(List.of());
        assertThrows(IllegalStateException.class, () -> service.archiveCompany(41));
        verifyNoInteractions(duplicates, companies);
    }

    @Test
    void pageCountAndFacetsEachOwnExactlyOneSnapshot() {
        CompanyService service = new CompanyService(
            companies,
            mock(ooo.klae.connex.backend.mappers.TagMapper.class),
            mock(ooo.klae.connex.backend.mappers.PersonMapper.class),
            mock(ooo.klae.connex.backend.mappers.DealMapper.class),
            mock(ooo.klae.connex.backend.mappers.ActivityMapper.class),
            mock(ooo.klae.connex.backend.mappers.NoteMapper.class),
            mock(ooo.klae.connex.backend.mappers.TaskMapper.class),
            mock(ooo.klae.connex.backend.services.AuthService.class),
            mock(ooo.klae.connex.backend.services.AuditService.class),
            mock(ooo.klae.connex.backend.notifications.NotificationChangePublisher.class),
            mock(ooo.klae.connex.backend.services.RuleTriggerPublisher.class),
            workspaces,
            mock(ooo.klae.connex.backend.services.CustomFieldValueService.class),
            mock(ooo.klae.connex.backend.services.SegmentService.class),
            mock(ooo.klae.connex.backend.services.ReferenceService.class),
            Clock.systemUTC(),
            mock(ooo.klae.connex.backend.storage.ManagedObjectService.class),
            mock(ooo.klae.connex.backend.services.IdentityIntakeService.class),
            mock(ooo.klae.connex.backend.services.MatchingService.class),
            mock(ooo.klae.connex.backend.services.DuplicatePreflightService.class),
            duplicates,
            mock(ooo.klae.connex.backend.services.RecordCreationAugmentationService.class),
            scopes);
        service.queryCompaniesPage(null, null, null, null, false, null,
            ooo.klae.connex.backend.dto.MemberScope.allTeam(), false, null, 25, 0);
        verify(control).findOrganizationWorkspacesForShare(7);
        verify(companies).getCompaniesPage(eq(7), eq("[7,11]"), any(), any(), any(), any(), eq(false),
            any(), any(), eq(false), any(), eq(25), eq(0));
        verify(companies).countCompanies(eq(7), eq("[7,11]"), any(), any(), eq(false), any(), any(), eq(false), any());
        clearInvocations(control);
        service.getCompanyFacets(null);
        verify(control).findOrganizationWorkspacesForShare(7);
        verify(companies).distinctIndustries(7, "[7,11]");
        verify(companies).hasCompanyWithoutIndustry(7, "[7,11]");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void tagDetailPreservesArrayContentsOrderAndEmptyArrays(boolean empty) {
        var tags = mock(ooo.klae.connex.backend.mappers.TagMapper.class);
        var people = mock(ooo.klae.connex.backend.mappers.PersonMapper.class);
        var deals = mock(ooo.klae.connex.backend.mappers.DealMapper.class);
        Tag tag = new Tag();
        when(tags.getTagById(7, 23)).thenReturn(tag);
        var first = new ooo.klae.connex.backend.beans.Person();
        var second = new ooo.klae.connex.backend.beans.Person();
        var deal = new ooo.klae.connex.backend.beans.Deal();
        List<ooo.klae.connex.backend.beans.Person> personRows = empty ? List.of() : List.of(second, first);
        List<Company> companyRows = empty ? List.of() : List.of(company(), company());
        List<ooo.klae.connex.backend.beans.Deal> dealRows = empty ? List.of() : List.of(deal);
        when(people.getPersonsByTagId(7, "[7,11]", 23)).thenReturn(personRows);
        when(companies.getCompaniesByTagId(7, "[7,11]", 23)).thenReturn(companyRows);
        when(deals.getDealsByTagId(7, 23)).thenReturn(dealRows);
        TagService service = new TagService(
            tags,
            deals,
            people,
            companies,
            mock(ooo.klae.connex.backend.services.AuditService.class),
            workspaces,
            mock(ooo.klae.connex.backend.services.ReferenceService.class),
            scopes);
        assertSame(tag, service.getTagById(23));
        assertArrayEquals(personRows.toArray(), tag.getPeople());
        assertArrayEquals(companyRows.toArray(), tag.getCompanies());
        assertArrayEquals(dealRows.toArray(), tag.getDeals());
        var order = inOrder(control, tags, people, companies, deals);
        order.verify(control).findOrganizationWorkspacesForShare(7);
        order.verify(tags).getTagById(7, 23);
        order.verify(people).getPersonsByTagId(7, "[7,11]", 23);
        order.verify(companies).getCompaniesByTagId(7, "[7,11]", 23);
        order.verify(deals).getDealsByTagId(7, 23);
    }

    @Test
    void oneHundredTemplateCompanyDefaultsReuseTheCatalogScope() {
        var templates = mock(ooo.klae.connex.backend.mappers.RecordCreationTemplateMapper.class);
        var validator = mock(RecordCreationTemplateValidator.class);
        var registry = new ooo.klae.connex.backend.recordcreation.RecordCreationFieldRegistry();
        var version = new ooo.klae.connex.backend.beans.RecordCreationTemplateVersion();
        version.setDefinitionJson("company-default");
        List<ooo.klae.connex.backend.beans.RecordCreationTemplate> roots = IntStream.rangeClosed(1, 100)
            .mapToObj(id -> {
                var root = new ooo.klae.connex.backend.beans.RecordCreationTemplate();
                root.setId(id);
                root.setRecordType("person");
                root.setStatus("enabled");
                return root;
            }).toList();
        var definition = new ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateDefinitionDto(
            1, List.of(new ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateGroupDto(
                "basics", null, null, List.of(new ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateFieldDto(
                    "company", false, null, null, new ooo.klae.connex.backend.dto.recordcreation.RecordCreationDefaultSpecDto(
                        ooo.klae.connex.backend.recordcreation.RecordCreationDefaultKind.literal_reference,
                        null, null, null, null, 41, null))))));
        when(templates.listRoots(7, "person", false)).thenReturn(roots);
        when(templates.getCurrentVersion(eq(7), anyInt())).thenReturn(version);
        when(validator.parseDefinition("company-default")).thenReturn(definition);
        when(companies.exists(7, "[7,11]", 41)).thenReturn(true);
        var calendar = mock(UserCalendarService.class);
        when(calendar.today()).thenReturn(java.time.LocalDate.of(2026, 10, 1));
        RecordCreationTemplateResolver resolver = new RecordCreationTemplateResolver(
            registry,
            calendar,
            validator,
            mock(ooo.klae.connex.backend.mappers.CustomFieldDefinitionMapper.class),
            companies,
            mock(ooo.klae.connex.backend.mappers.PersonMapper.class),
            mock(ooo.klae.connex.backend.mappers.PipelineMapper.class),
            scopes,
            mock(ooo.klae.connex.backend.mappers.TagMapper.class),
            workspaces,
            tools.jackson.databind.json.JsonMapper.builder().findAndAddModules().build());
        var service = new RecordCreationPresetService(templates, resolver, registry, workspaces, scopes);
        var catalog = service.persons(ooo.klae.connex.backend.recordcreation.RecordCreationEntryPoint.record_detail, null);
        assertEquals(101, catalog.templates().size());
        verify(companies, times(100)).exists(7, "[7,11]", 41);
        verify(control).findOrganizationWorkspacesForShare(7);
    }

    @Test
    void backlinkAdmissionAndRedactionReuseOneSnapshot() {
        var references = mock(ReferenceService.class);
        when(references.requireVisibleTarget(7, "company", 41, 17)).thenAnswer(call -> {
            assertEquals("[7,11]", scopes.getForWorkspace(7).workspaceIdsJson());
            return "company";
        });
        when(references.hydrateNotePreviews(eq(7), anyList())).thenAnswer(call -> {
            assertEquals("[7,11]", scopes.getForWorkspace(7).workspaceIdsJson());
            return call.getArgument(1);
        });
        NoteService service = new NoteService(
            mock(ooo.klae.connex.backend.mappers.NoteMapper.class),
            mock(ooo.klae.connex.backend.mappers.UserMapper.class),
            mock(ooo.klae.connex.backend.mappers.DealMapper.class),
            mock(ooo.klae.connex.backend.mappers.PersonMapper.class),
            mock(ooo.klae.connex.backend.services.AuditService.class),
            workspaces,
            mock(ooo.klae.connex.backend.services.AuthService.class),
            references,
            mock(ooo.klae.connex.backend.notifications.NotificationDelivery.class),
            mock(ooo.klae.connex.backend.services.NotificationPreferenceService.class),
            mock(tools.jackson.databind.ObjectMapper.class),
            scopes);
        assertEquals(List.of(), service.getNotesReferencing("company", 41, 25, 0));
        verify(references).requireVisibleTarget(7, "company", 41, 17);
        verify(references).hydrateNotePreviews(7, List.of());
        verify(control).findOrganizationWorkspacesForShare(7);
    }

    @Test
    void reconciliationFamiliesKeepOneReadAcrossIndependentTransactions() {
        UnitTransactions manager = new UnitTransactions();
        var detectors = mock(RelationshipSignalDetectorService.class);
        var writer = mock(RelationshipSignalWriteService.class);
        java.util.function.Supplier<RelationshipSignalDetectorService.Detection> detect = () ->
            new TransactionTemplate(manager).execute(status -> {
                assertEquals("[7,11]", scopes.getForWorkspace(7).workspaceIdsJson());
                return new RelationshipSignalDetectorService.Detection(List.of(), java.time.Instant.EPOCH, "generation");
            });
        when(detectors.detectDecay(eq(7), anyString())).thenAnswer(call -> detect.get());
        when(detectors.detectDealRisk(eq(7), anyString())).thenAnswer(call -> detect.get());
        when(detectors.detectWarmPaths(eq(7), anyString(), any())).thenAnswer(call -> detect.get());
        var service = new RelationshipSignalReconciliationService(detectors, writer, Clock.systemUTC(), scopes);
        assertEquals(3, service.reconcileWorkspace(7).successfulFamilies().size());
        assertEquals(3, manager.begins.get());
        verify(control).findOrganizationWorkspacesForShare(7);
    }

    @Test
    void mapReplayLoadsOneCompanyScopeForAllFrames() {
        var scoring = mock(ScoringService.class);
        when(companies.getAllCompanies(7, "[7,11]")).thenReturn(List.of(company()));
        when(scoring.replayBands(eq(7), any(), any(), any(), any())).thenAnswer(call -> {
            long[] cutoffs = call.getArgument(1);
            List<Map<Integer, String>> bands = IntStream.range(0, cutoffs.length)
                .mapToObj(i -> Map.<Integer, String>of()).toList();
            assertEquals("[7,11]", scopes.getForWorkspace(7).workspaceIdsJson());
            return new ScoringService.ReplayBands(bands, bands);
        });
        MapReplayService service = new MapReplayService(
            scoring,
            mock(ooo.klae.connex.backend.mappers.PersonMapper.class),
            companies,
            mock(ooo.klae.connex.backend.mappers.DealMapper.class),
            mock(ooo.klae.connex.backend.mappers.PersonEmploymentMapper.class),
            Clock.systemUTC(),
            scopes);
        var replay = service.buildReplay(7, java.time.LocalDate.of(2026, 1, 1), java.time.LocalDate.of(2026, 3, 31),
            java.time.Period.ofDays(1));
        assertEquals(90, replay.getFrames().size());
        verify(control).findOrganizationWorkspacesForShare(7);
        verify(companies).getAllCompanies(7, "[7,11]");
    }

    @Test
    void dealOwnerBulkUsesOneScopeAcrossOneHundredIndependentMutations() {
        var deals = mock(ooo.klae.connex.backend.mappers.DealMapper.class);
        var delegate = mock(DealService.class);
        UnitTransactions manager = new UnitTransactions();
        when(deals.existsOwned(eq(7), anyInt())).thenReturn(true);
        when(delegate.updateOwner(anyInt(), eq(17))).thenAnswer(call -> new TransactionTemplate(manager).execute(status -> {
            assertEquals("[7,11]", scopes.getForWorkspace(7).workspaceIdsJson());
            return new ooo.klae.connex.backend.beans.Deal();
        }));
        BulkOperationService service = new BulkOperationService(
            workspaces,
            mock(ooo.klae.connex.backend.services.PersonService.class),
            mock(ooo.klae.connex.backend.services.CompanyService.class),
            delegate,
            mock(ooo.klae.connex.backend.mappers.PersonMapper.class),
            companies,
            deals,
            mock(ooo.klae.connex.backend.mappers.TagMapper.class),
            mock(ooo.klae.connex.backend.mappers.PipelineMapper.class),
            scopes);
        assertEquals(100, service.assignOwnerToDeals(IntStream.rangeClosed(1, 100).boxed().toList(), 17).getSucceeded());
        assertEquals(100, manager.begins.get());
        verify(control).findOrganizationWorkspacesForShare(7);
    }

    @Test
    void oneHundredImportLinksAndPreviewCandidatesShareOneSnapshot() {
        var duplicateReviews = mock(DuplicatePreflightService.class);
        when(duplicateReviews.preflightCompanyImportPreview(anyList(), anyString())).thenAnswer(call -> {
            List<?> requests = call.getArgument(0);
            var responses = requests.stream().map(request -> {
                assertEquals("[7,11]", scopes.getForWorkspace(7).workspaceIdsJson());
                return new ooo.klae.connex.backend.dto.DuplicatePreflightResponse("company", List.of(), false, "review");
            }).toList();
            return new ooo.klae.connex.backend.dto.DuplicateImportPreflightReview(responses, "proof");
        });
        when(companies.existsOwned(eq(7), anyInt())).thenReturn(true);
        when(companies.getCompanyById(eq(7), eq("[7,11]"), anyInt())).thenAnswer(call -> {
            Company company = company();
            company.setId(call.getArgument(2));
            return company;
        });
        var rows = IntStream.rangeClosed(1, 100).mapToObj(id -> Map.of("Name", "Company " + id)).toList();
        var links = IntStream.range(0, 100).boxed().collect(java.util.stream.Collectors.toMap(i -> i, i -> i + 1));
        var request = new ooo.klae.connex.backend.dto.ImportRequest(rows,
            List.of(new ooo.klae.connex.backend.dto.ColumnMapping("Name", "name", null, null, null)), "overwrite", links);
        ImportService service = new ImportService(
            workspaces,
            mock(ooo.klae.connex.backend.services.DuplicateDecisionLockService.class),
            mock(ooo.klae.connex.backend.services.AuthService.class),
            mock(ooo.klae.connex.backend.mappers.PersonMapper.class),
            companies,
            mock(ooo.klae.connex.backend.mappers.IdentityMapper.class),
            mock(ooo.klae.connex.backend.services.CompanyService.class),
            mock(ooo.klae.connex.backend.mappers.DealMapper.class),
            mock(ooo.klae.connex.backend.mappers.DealLineItemMapper.class),
            mock(ooo.klae.connex.backend.services.DealValueService.class),
            mock(ooo.klae.connex.backend.services.DealOutcomeWriter.class),
            mock(ooo.klae.connex.backend.mappers.TagMapper.class),
            mock(ooo.klae.connex.backend.services.TagService.class),
            mock(ooo.klae.connex.backend.mappers.PipelineMapper.class),
            scopes,
            mock(ooo.klae.connex.backend.services.EmploymentService.class),
            mock(ooo.klae.connex.backend.services.DealStageHistoryService.class),
            mock(ooo.klae.connex.backend.mappers.CustomFieldDefinitionMapper.class),
            mock(ooo.klae.connex.backend.services.CustomFieldDefinitionService.class),
            mock(ooo.klae.connex.backend.mappers.RecordCreationTemplateMapper.class),
            mock(ooo.klae.connex.backend.services.CustomFieldValueService.class),
            mock(ooo.klae.connex.backend.services.AuditService.class),
            mock(ooo.klae.connex.backend.services.IdentityIntakeService.class),
            mock(ooo.klae.connex.backend.services.MatchingService.class),
            duplicateReviews);
        assertEquals(100, service.previewCompanies(request).getRows().size());
        verify(companies, times(100)).getCompanyById(eq(7), eq("[7,11]"), anyInt());
        verify(control).findOrganizationWorkspacesForShare(7);
    }

    @Test
    void oneHundredCompanyScoresUseOneControlReadAndOneAggregateStatement() {
        Set<Integer> ids = IntStream.rangeClosed(1, 100).boxed().collect(java.util.stream.Collectors.toSet());
        var aggregates = ids.stream().map(id -> new ooo.klae.connex.backend.dto.RelationshipScoreAggregateDto(
            id, 10, 10, 0, "2026-09-30T12:00:00", 1)).toList();
        when(companies.getRelationshipScoreAggregatesByIds(eq(7), eq("[7,11]"), any(), any(), anyList()))
            .thenReturn(aggregates);
        ScoringService service = new ScoringService(mock(ooo.klae.connex.backend.mappers.PersonMapper.class),
            companies, mock(ooo.klae.connex.backend.mappers.DealMapper.class),
            mock(ooo.klae.connex.backend.mappers.ActivityMapper.class), mock(ooo.klae.connex.backend.mappers.NoteMapper.class),
            mock(ooo.klae.connex.backend.mappers.TaskMapper.class),
            Clock.fixed(java.time.Instant.parse("2026-10-01T00:00:00Z"), java.time.ZoneOffset.UTC), scopes);
        assertEquals(100, service.scoreCompanies(7, ids).size());
        verify(control).findOrganizationWorkspacesForShare(7);
        verify(companies).getRelationshipScoreAggregatesByIds(eq(7), eq("[7,11]"), any(), any(), anyList());
        org.mockito.Mockito.verifyNoMoreInteractions(companies);
    }

    private static Company company() {
        Company company = new Company();
        company.setId(41);
        company.setWorkspaceId(7);
        company.setName("Company");
        return company;
    }

    /** Supplies real Spring transaction synchronization without any database resource. */
    private static final class UnitTransactions extends AbstractPlatformTransactionManager {
        private final AtomicInteger begins = new AtomicInteger();
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) { begins.incrementAndGet(); }
        @Override protected void doCommit(DefaultTransactionStatus status) { }
        @Override protected void doRollback(DefaultTransactionStatus status) { }
    }
    @Test
    void emptyAndDisappearedExpiryCandidatesDoNotReadControlScopes() {
        var deliveries = mock(ooo.klae.connex.backend.mappers.DocumentDeliveryMapper.class);
        var service = new DocumentDeliveryScheduler(deliveries,
            mock(ooo.klae.connex.backend.mappers.DealDocumentMapper.class),
            mock(ooo.klae.connex.backend.mappers.DealMapper.class), mock(DocumentDeliveryLifecycleService.class),
            mock(PlacementRegistry.class), mock(TenantWorkScope.class), mock(AutomationExecutor.class),
            mock(SystemActor.class), new TransactionTemplate(new UnitTransactions()),
            new ooo.klae.connex.backend.signature.SignatureProperties(),
            mock(ooo.klae.connex.backend.observability.JobRunRecorder.class), scopes);
        assertEquals(0, org.springframework.test.util.ReflectionTestUtils.<Integer>invokeMethod(service, "expireWorkspace", 7));
        when(deliveries.findDueDeliveryIds(eq(7), any(), anyInt())).thenReturn(List.of(41, 42));
        assertEquals(0, org.springframework.test.util.ReflectionTestUtils.<Integer>invokeMethod(service, "expireWorkspace", 7));
        verifyNoInteractions(control);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void expirySnapshotRefusalsEscapeBothCatalogAndWorkspaceHandlers(boolean insideWorkspace) {
        var refusal = new ooo.klae.connex.backend.exceptions.TenantScopeSnapshotMissingException("workspace 7");
        var placements = mock(PlacementRegistry.class);
        when(placements.activeCatalogs()).thenReturn(List.of("tenant_catalog"));
        var routing = mock(TenantWorkScope.class);
        if (insideWorkspace) {
            when(routing.withCatalog(eq("tenant_catalog"),
                org.mockito.ArgumentMatchers.<java.util.function.Supplier<java.util.TreeSet<Integer>>>any()))
                .thenReturn(new java.util.TreeSet<>(List.of(7)));
            org.mockito.Mockito.doThrow(refusal).when(routing).inWorkspace(eq(7), any(Runnable.class));
        } else {
            when(routing.withCatalog(eq("tenant_catalog"),
                org.mockito.ArgumentMatchers.<java.util.function.Supplier<java.util.TreeSet<Integer>>>any()))
                .thenThrow(refusal);
        }
        var jobs = mock(ooo.klae.connex.backend.observability.JobRunRecorder.class);
        var service = new DocumentDeliveryScheduler(mock(ooo.klae.connex.backend.mappers.DocumentDeliveryMapper.class),
            mock(ooo.klae.connex.backend.mappers.DealDocumentMapper.class),
            mock(ooo.klae.connex.backend.mappers.DealMapper.class), mock(DocumentDeliveryLifecycleService.class),
            placements, routing, mock(AutomationExecutor.class), mock(SystemActor.class),
            new TransactionTemplate(new UnitTransactions()), new ooo.klae.connex.backend.signature.SignatureProperties(),
            jobs, scopes);
        assertSame(refusal, assertThrows(ooo.klae.connex.backend.exceptions.TenantScopeSnapshotException.class,
            service::expire));
        verifyNoInteractions(control, jobs);
    }

}
