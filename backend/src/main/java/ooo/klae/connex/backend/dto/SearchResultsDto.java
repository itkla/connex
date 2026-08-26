package ooo.klae.connex.backend.dto;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The grouped global-search payload. Each field is one result group; a group the caller may not
 * read is served empty rather than omitted, so the client contract has fixed shape.
 *
 * <p>The record groups return their full entity DTOs, as they always have. The groups added for the
 * sidebar's builder objects — products, campaigns, reports, document templates, generated
 * documents, and workflows — return bounded summary projections instead, because their full DTOs
 * carry authored payloads (report configuration, template bodies, document content snapshots,
 * workflow graphs) that a search row never renders.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SearchResultsDto {
    private List<CompanyDto> companies;
    private List<PersonDto> people;
    private List<DealDto> deals;
    private List<PipelineDto> pipelines;
    private List<TagDto> tags;
    private List<ActivityDto> activities;
    private List<NoteDto> notes;
    private List<TaskDto> tasks;
    private List<UserDto> users;
    private List<AttachmentDto> attachments;
    private List<ProductSummaryDto> products;
    private List<CampaignSummaryDto> campaigns;
    private List<ReportSummaryDto> reports;
    private List<DocumentTemplateSummaryDto> documentTemplates;
    private List<GeneratedDocumentSummaryDto> documents;
    private List<WorkflowSummaryDto> workflows;
}
