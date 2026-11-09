package ooo.klae.connex.backend.services;

import static ooo.klae.connex.backend.support.RecordVisibilityScopes.unfiltered;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.CustomFieldDefinition;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Product;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.config.PrivilegedMfaProperties;
import ooo.klae.connex.backend.config.SessionSecurityProperties;
import ooo.klae.connex.backend.dto.ProductFacetFilter;
import ooo.klae.connex.backend.dto.MemberScope;
import ooo.klae.connex.backend.exceptions.RecentAuthenticationRequiredException;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.CustomFieldDefinitionMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.PipelineMapper;
import ooo.klae.connex.backend.mappers.ProductMapper;
import ooo.klae.connex.backend.mappers.SpringSessionMapper;
import ooo.klae.connex.backend.mappers.TagMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.tenant.Permission;

@ExtendWith(MockitoExtension.class)
class ExportServiceTest {
    @Mock private SessionSecurityService sessionSecurityService;
    @Mock private WorkspaceService workspaceService;
    @Mock private DealService dealService;
    @Mock private PersonMapper personMapper;
    @Mock private CompanyMapper companyMapper;
    @Mock private ProductMapper productMapper;
    @Mock private PipelineMapper pipelineMapper;
    private final RecordVisibilityScopeControlAccess recordVisibilityScopeControlAccess = unfiltered();
    @Mock private TagMapper tagMapper;
    @Mock private CustomFieldDefinitionMapper customFieldDefinitionMapper;
    @Mock private CustomFieldValueService customFieldValueService;
    @Mock private DisqualificationReasonService disqualificationReasonService;
    @Mock private AuditService auditService;
    @InjectMocks private ExportService exportService;

    @BeforeEach
    void scopeCallbacks() {
        ReflectionTestUtils.setField(exportService, "recordVisibilityScopeControlAccess", recordVisibilityScopeControlAccess);
    }

    @ParameterizedTest
    @ValueSource(strings = {"persons", "companies", "products", "deals", "segmentDeals"})
    void everySuccessfulExportRecordsItsActualRowCountWithoutValues(String surface) {
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(42);

        String csv = exportSurface(surface);

        assertTrue(csv.startsWith("id,"));
        String entity = switch (surface) {
            case "persons" -> "person";
            case "companies" -> "company";
            case "products" -> "product";
            default -> "deal";
        };
        verify(auditService).recordDataExportCompleted(
            entity, 0, null, surface.equals("segmentDeals"), surface.equals("segmentDeals"));
    }

    @Test
    void queryFailureDoesNotRecordACompletedExport() {
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(42);
        when(productMapper.getFiltered(42, null, ProductFacetFilter.none()))
            .thenThrow(new IllegalStateException("Read failed"));

        assertThrows(IllegalStateException.class, () -> exportService.exportProducts(null, ProductFacetFilter.none()));

        verifyNoInteractions(auditService);
    }

    @Test
    void renderingFailureDoesNotRecordACompletedExport() {
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(42);
        Deal deal = new Deal();
        deal.setId(7);
        deal.setClosedAt("invalid timestamp");
        when(dealService.queryDealsForExport(null, null, List.of(), List.of(), List.of(), List.of(),
            false, List.of(), List.of(), null)).thenReturn(List.of(deal));

        assertThrows(java.time.format.DateTimeParseException.class, () -> exportSurface("deals"));

        verifyNoInteractions(auditService);
    }

    @Test
    void auditPersistenceFailurePreventsTheCompletedCsvFromBeingReturned() {
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(42);
        doThrow(new IllegalStateException("Audit unavailable")).when(auditService)
            .recordDataExportCompleted("product", 0, null, false, false);

        assertThrows(IllegalStateException.class, () -> exportService.exportProducts(null, ProductFacetFilter.none()));
    }

    private String exportSurface(String surface) {
        return switch (surface) {
            case "persons" -> exportService.exportPersons(null, List.of(), List.of(), false,
                null, List.of(), false, List.of(), false, List.of(), false, null);
            case "companies" -> exportService.exportCompanies(null, List.of(), false, List.of(), null, null);
            case "products" -> exportService.exportProducts(null, ProductFacetFilter.none());
            case "deals" -> exportService.exportDeals(null, null, List.of(), List.of(), List.of(),
                List.of(), false, List.of(), List.of(), null);
            case "segmentDeals" -> exportService.exportSegmentDeals(null, null, null, List.of(),
                List.of(), List.of(), List.of(), false, List.of(), List.of(), null);
            default -> throw new IllegalArgumentException(surface);
        };
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void realExportBoundaryAppliesTheRolloutFlagWithoutAStamp(boolean enforced) {
        PrivilegedMfaProperties policy = new PrivilegedMfaProperties();
        policy.setEnforced(Boolean.toString(enforced));
        SessionSecurityService sessionSecurity = new SessionSecurityService(
                new SessionSecurityProperties(), policy, Clock.systemUTC(),
                mock(UserMapper.class), mock(SpringSessionMapper.class), mock(PrivilegedAccountService.class));
        ExportService service = new ExportService(sessionSecurity, workspaceService, dealService,
                personMapper, companyMapper, productMapper, pipelineMapper, unfiltered(), tagMapper,
                customFieldDefinitionMapper, customFieldValueService, mock(DisqualificationReasonService.class),
                auditService);
        User user = new User();
        user.setId(7);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        try {
            if (enforced) {
                assertThrows(RecentAuthenticationRequiredException.class, () -> service.exportProducts(null, ProductFacetFilter.none()));
                verify(workspaceService).requirePermission(Permission.EXPORT_DATA);
                verifyNoMoreInteractions(workspaceService);
                verifyNoInteractions(productMapper);
                verify(auditService).recordExportStepUpRefused();
            } else {
                when(workspaceService.getCurrentWorkspaceId()).thenReturn(9);
                when(productMapper.getFiltered(9, null, ProductFacetFilter.none())).thenReturn(List.of());
                assertTrue(service.exportProducts(null, ProductFacetFilter.none()).startsWith("id,sku,name"));
                verify(productMapper).getFiltered(9, null, ProductFacetFilter.none());
            }
        } finally {
            SecurityContextHolder.clearContext();
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"persons", "companies", "products", "deals", "segmentDeals"})
    void exportsRequireStepUpBeforeReadingRecords(String surface) {
        doThrow(new RecentAuthenticationRequiredException())
                .when(sessionSecurityService).requireExportStepUp();
        assertThrows(
                RecentAuthenticationRequiredException.class, () -> {
                    switch (surface) {
                        case "persons" -> exportService.exportPersons(null, List.of(), List.of(), false,
                                null, List.of(), false, List.of(), false, List.of(), false, null);
                        case "companies" -> exportService.exportCompanies(null, List.of(), false, List.of(), null, null);
                        case "products" -> exportService.exportProducts(null, ProductFacetFilter.none());
                        case "deals" -> exportService.exportDeals(null, null, List.of(), List.of(), List.of(),
                                List.of(), false, List.of(), List.of(), null);
                        case "segmentDeals" -> exportService.exportSegmentDeals(null, null, null, List.of(),
                                List.of(), List.of(), List.of(), false, List.of(), List.of(), null);
                        default -> throw new IllegalArgumentException(surface);
                    }
                });
        verify(auditService).recordExportStepUpRefused();
        verify(auditService, never()).recordDataExportCompleted(
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt(),
            org.mockito.ArgumentMatchers.nullable(MemberScope.class),
            org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.anyBoolean());
        verify(workspaceService).requirePermission(Permission.EXPORT_DATA);
        verifyNoMoreInteractions(workspaceService);
        verifyNoInteractions(personMapper, companyMapper,
                productMapper, dealService, tagMapper, customFieldValueService);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void exportDealsPreservesWholeNumberCsvBytesAndExplicitUtcHistory(boolean withHistory) {
        Deal deal = new Deal();
        deal.setId(7);
        deal.setName("Renewal");
        deal.setValue(new BigDecimal("1000.00"));
        deal.setCurrency("USD");
        if (withHistory) {
            deal.setClosedAt("2025-02-28 15:00:00");
            deal.setCreatedAt("2025-01-31 15:00:00");
            deal.setWon(true);
        }
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(42);
        when(dealService.queryDealsForExport(
            null, null, List.of(), List.of(), List.of(), List.of(), false,
            List.of(), List.of(), null))
            .thenReturn(List.of(deal));
        when(customFieldDefinitionMapper.getByEntityType(42, "deal")).thenReturn(List.of());
        when(customFieldValueService.getForEntities("deal", List.of(7))).thenReturn(Map.of());
        when(tagMapper.getDealTagNames(42)).thenReturn(List.of());
        when(companyMapper.getCompaniesForDedup(42)).thenReturn(List.of());
        when(recordVisibilityScopeControlAccess.getForWorkspace(42)).thenReturn(
            new RecordVisibilityScopeControlAccess.RecordVisibilityScope(90, List.of(7, 8, 9)));
        when(pipelineMapper.getAllPipelines(42, "[7,8,9]")).thenReturn(List.of());

        String csv = exportService.exportDeals(
            null, null, List.of(), List.of(), List.of(), List.of(), false,
            List.of(), List.of(), null);

        assertEquals(
            "id,name,value,currency,company,pipeline,stage,expectedCloseDate,status,tags,closedAt,createdAt\r\n"
                + (withHistory
                    ? "7,Renewal,1000,USD,,,,,won,,2025-02-28T15:00:00Z,2025-01-31T15:00:00Z\r\n"
                    : "7,Renewal,1000,USD,,,,,open,,,\r\n"),
            csv);
        verify(auditService).recordDataExportCompleted("deal", 1, null, false, false);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void dealExportsAppendHistoryAfterExistingCustomAndTagColumns(boolean segment) {
        Deal deal = new Deal();
        deal.setId(7);
        deal.setName("Renewal");
        deal.setValue(new BigDecimal("1000.00"));
        deal.setCurrency("USD");
        deal.setCompanyId(10);
        deal.setPipelineId(20);
        deal.setStageId(30);
        deal.setExpectedCloseDate("2026-11-01");
        deal.setWon(true);
        deal.setClosedAt("2025-02-28 15:00:00");
        deal.setCreatedAt("2025-01-31 15:00:00");
        Company company = new Company();
        company.setId(10);
        company.setName("Acme");
        Pipeline pipeline = new Pipeline();
        pipeline.setId(20);
        pipeline.setName("Sales");
        Stage stage = new Stage();
        stage.setId(30);
        stage.setName("Closed");
        CustomFieldDefinition tier = new CustomFieldDefinition();
        tier.setId(21);
        tier.setLabel("Contract tier");
        tier.setPosition(1);
        CustomFieldDefinition reference = new CustomFieldDefinition();
        reference.setId(22);
        reference.setLabel("Reference");
        reference.setPosition(2);
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(42);
        if (segment) {
            when(dealService.querySegmentDealsForExport(
                null, null, null, List.of(), List.of(), List.of(), List.of(), false,
                List.of(), List.of(), null)).thenReturn(List.of(deal));
        } else {
            when(dealService.queryDealsForExport(
                null, null, List.of(), List.of(), List.of(), List.of(), false,
                List.of(), List.of(), null)).thenReturn(List.of(deal));
        }
        when(customFieldDefinitionMapper.getByEntityType(42, "deal")).thenReturn(List.of(reference, tier));
        when(customFieldValueService.getForEntities("deal", List.of(7)))
            .thenReturn(Map.of(7, Map.of(21, "Gold", 22, "REF-7")));
        when(tagMapper.getDealTagNames(42)).thenReturn(List.of(
            Map.of("entityId", 7, "name", "Priority"), Map.of("entityId", 7, "name", "Partner")));
        when(companyMapper.getCompaniesForDedup(42)).thenReturn(List.of(company));
        when(recordVisibilityScopeControlAccess.getForWorkspace(42)).thenReturn(
            new RecordVisibilityScopeControlAccess.RecordVisibilityScope(90, List.of(7, 8, 9)));
        when(pipelineMapper.getAllPipelines(42, "[7,8,9]")).thenReturn(List.of(pipeline));
        when(pipelineMapper.getStagesByPipelineId(42, "[7,8,9]", 20)).thenReturn(List.of(stage));

        String csv = segment
            ? exportService.exportSegmentDeals(null, null, null, List.of(), List.of(), List.of(),
                List.of(), false, List.of(), List.of(), null)
            : exportService.exportDeals(null, null, List.of(), List.of(), List.of(), List.of(),
                false, List.of(), List.of(), null);

        assertEquals(
            "id,name,value,currency,company,pipeline,stage,expectedCloseDate,status,Contract tier,Reference,tags,closedAt,createdAt\r\n"
                + "7,Renewal,1000,USD,Acme,Sales,Closed,2026-11-01,won,Gold,REF-7,\"Priority, Partner\",2025-02-28T15:00:00Z,2025-01-31T15:00:00Z\r\n",
            csv);
    }

    @Test
    void exportProductsFormatsEveryColumnAndNeutralizesSpreadsheetFormulas() {
        Product product = new Product();
        product.setId(7);
        product.setSku("=SKU");
        product.setName("\nWidget, \"Pro\"");
        product.setDescription("＋cmd");
        product.setActive(false);
        product.setUnit("－seat");
        product.setUnitPrice(new BigDecimal("1E+3"));
        product.setCurrency("＠JPY");
        product.setTaxRate(new BigDecimal("10.5000"));
        product.setBillingFrequency("＝recurring");
        product.setEffectiveStart(LocalDate.of(2027, 1, 2));
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(42);
        ProductFacetFilter facets = ProductFacetFilter.fromRequest(
            List.of("recurring"), List.of("false"), List.of("bounded"));
        when(productMapper.getFiltered(42, "%Widget%", facets))
            .thenReturn(List.of(product));

        String csv = exportService.exportProducts("%Widget%", facets);

        assertEquals(
            "id,sku,name,description,active,unit,unitPrice,currency,taxRate,billingFrequency,effectiveStart,effectiveEnd\r\n"
                + "7,'=SKU,\"'\nWidget, \"\"Pro\"\"\",'＋cmd,false,'－seat,1000,'＠JPY,10.5000,'＝recurring,2027-01-02,\r\n",
            csv);
        verify(productMapper).getFiltered(42, "%Widget%", facets);
        verify(auditService).recordDataExportCompleted("product", 1, null, true, false);
    }
}
