package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

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
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Product;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.config.PrivilegedMfaProperties;
import ooo.klae.connex.backend.config.SessionSecurityProperties;
import ooo.klae.connex.backend.exceptions.RecentAuthenticationRequiredException;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.CustomFieldDefinitionMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.PipelineMapper;
import ooo.klae.connex.backend.mappers.ProductMapper;
import ooo.klae.connex.backend.mappers.SpringSessionMapper;
import ooo.klae.connex.backend.mappers.TagMapper;
import ooo.klae.connex.backend.mappers.UserMapper;

@ExtendWith(MockitoExtension.class)
class ExportServiceTest {
    @Mock private SessionSecurityService sessionSecurityService;
    @Mock private WorkspaceService workspaceService;
    @Mock private DealService dealService;
    @Mock private PersonMapper personMapper;
    @Mock private CompanyMapper companyMapper;
    @Mock private ProductMapper productMapper;
    @Mock private PipelineMapper pipelineMapper;
    @Mock private TagMapper tagMapper;
    @Mock private CustomFieldDefinitionMapper customFieldDefinitionMapper;
    @Mock private CustomFieldValueService customFieldValueService;
    @Mock private AuditService auditService;
    @InjectMocks private ExportService exportService;

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void realExportBoundaryAppliesTheRolloutFlagWithoutAStamp(boolean enforced) {
        PrivilegedMfaProperties policy = new PrivilegedMfaProperties();
        policy.setEnforced(Boolean.toString(enforced));
        SessionSecurityService sessionSecurity = new SessionSecurityService(
                new SessionSecurityProperties(), policy, Clock.systemUTC(),
                mock(UserMapper.class), mock(SpringSessionMapper.class));
        ExportService service = new ExportService(sessionSecurity, workspaceService, dealService,
                personMapper, companyMapper, productMapper, pipelineMapper, tagMapper,
                customFieldDefinitionMapper, customFieldValueService, mock(DisqualificationReasonService.class),
                auditService);
        User user = new User();
        user.setId(7);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        try {
            if (enforced) {
                assertThrows(RecentAuthenticationRequiredException.class, () -> service.exportProducts(null));
                verifyNoInteractions(workspaceService, productMapper);
                verify(auditService).recordExportStepUpRefused();
            } else {
                when(workspaceService.getCurrentWorkspaceId()).thenReturn(9);
                when(productMapper.getFiltered(9, null)).thenReturn(List.of());
                assertTrue(service.exportProducts(null).startsWith("id,sku,name"));
                verify(productMapper).getFiltered(9, null);
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
                        case "products" -> exportService.exportProducts(null);
                        case "deals" -> exportService.exportDeals(null, null, List.of(), List.of(), List.of(),
                                List.of(), false, List.of(), List.of(), null);
                        case "segmentDeals" -> exportService.exportSegmentDeals(null, null, null, List.of(),
                                List.of(), List.of(), List.of(), false, List.of(), List.of(), null);
                        default -> throw new IllegalArgumentException(surface);
                    }
                });
        verify(auditService).recordExportStepUpRefused();
        verifyNoInteractions(workspaceService, personMapper, companyMapper,
                productMapper, dealService, tagMapper, customFieldValueService);
    }

    @Test
    void exportDealsPreservesWholeNumberCsvBytesForScaleTwoValues() {
        Deal deal = new Deal();
        deal.setId(7);
        deal.setName("Renewal");
        deal.setValue(new BigDecimal("1000.00"));
        deal.setCurrency("USD");
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(42);
        when(dealService.queryDealsForExport(
            null, null, List.of(), List.of(), List.of(), List.of(), false,
            List.of(), List.of(), null))
            .thenReturn(List.of(deal));
        when(customFieldDefinitionMapper.getByEntityType(42, "deal")).thenReturn(List.of());
        when(customFieldValueService.getForEntities("deal", List.of(7))).thenReturn(Map.of());
        when(tagMapper.getDealTagNames(42)).thenReturn(List.of());
        when(companyMapper.getCompaniesForDedup(42)).thenReturn(List.of());
        when(pipelineMapper.getAllPipelines(42)).thenReturn(List.of());

        String csv = exportService.exportDeals(
            null, null, List.of(), List.of(), List.of(), List.of(), false,
            List.of(), List.of(), null);

        assertEquals(
            "id,name,value,currency,company,pipeline,stage,expectedCloseDate,status,tags\r\n"
                + "7,Renewal,1000,USD,,,,,open,\r\n",
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
        when(productMapper.getFiltered(42, "%Widget%"))
            .thenReturn(List.of(product));

        String csv = exportService.exportProducts("%Widget%");

        assertEquals(
            "id,sku,name,description,active,unit,unitPrice,currency,taxRate,billingFrequency,effectiveStart,effectiveEnd\r\n"
                + "7,'=SKU,\"'\nWidget, \"\"Pro\"\"\",'＋cmd,false,'－seat,1000,'＠JPY,10.5000,'＝recurring,2027-01-02,\r\n",
            csv);
        verify(productMapper).getFiltered(42, "%Widget%");
    }
}
