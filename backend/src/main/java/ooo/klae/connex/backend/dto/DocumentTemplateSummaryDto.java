package ooo.klae.connex.backend.dto;

import java.time.LocalDateTime;

/**
 * Bounded document-template projection for a global-search result row.
 *
 * <p>Excludes the authored title, intro, terms, footer, and block body the full
 * {@link DocumentTemplateDto} carries, so a matched template contributes a label-sized row rather
 * than its complete authored payload.
 *
 * @param id the template id
 * @param name the template name
 * @param type the document type the template produces
 * @param locale the template locale
 * @param active whether the template is selectable when generating a document
 * @param updatedAt when the template last changed
 */
public record DocumentTemplateSummaryDto(
        int id,
        String name,
        String type,
        String locale,
        boolean active,
        LocalDateTime updatedAt) {
}
