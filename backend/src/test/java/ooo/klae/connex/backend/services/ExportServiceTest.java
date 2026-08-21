package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Product;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.CustomFieldDefinitionMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.PipelineMapper;
import ooo.klae.connex.backend.mappers.ProductMapper;
import ooo.klae.connex.backend.mappers.TagMapper;

@ExtendWith(MockitoExtension.class)
class ExportServiceTest {
    @Mock private WorkspaceService workspaceService;
    @Mock private DealService dealService;
    @Mock private PersonMapper personMapper;
    @Mock private CompanyMapper companyMapper;
    @Mock private ProductMapper productMapper;
    @Mock private PipelineMapper pipelineMapper;
    @Mock private TagMapper tagMapper;
    @Mock private CustomFieldDefinitionMapper customFieldDefinitionMapper;
    @Mock private CustomFieldValueService customFieldValueService;
    @InjectMocks private ExportService exportService;

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
