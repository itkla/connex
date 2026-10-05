package ooo.klae.connex.backend.beans;

/**
 * One tag associated with one person, company or deal, read in a batch across records.
 *
 * <p>It carries the association and the tag's own name and nothing else of the record, so a
 * batched card projection learns which tags each record holds without reading any record.
 *
 * @param recordId the tagged person, company or deal
 * @param tagId the associated tag
 * @param name the tag's workspace-authored name
 */
public record RecordTag(int recordId, int tagId, String name) {
}
