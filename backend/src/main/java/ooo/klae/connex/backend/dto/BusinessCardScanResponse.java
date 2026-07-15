package ooo.klae.connex.backend.dto;

import java.util.List;

/**
 * Editable contact and company candidates extracted from one business card.
 *
 * @param fields contact-field candidates
 * @param company company candidate and exact workspace match
 * @param warnings structured extraction warnings
 */
public record BusinessCardScanResponse(
        Fields fields,
        CompanyCandidate company,
        List<String> warnings) {

    /**
     * Contact fields recognized from a card.
     *
     * @param name name candidate
     * @param email email candidate
     * @param phone phone candidate
     * @param title title candidate
     */
    public record Fields(
            FieldCandidate name,
            FieldCandidate email,
            FieldCandidate phone,
            FieldCandidate title) {
    }

    /**
     * One nullable recognized value with nullable OCR confidence.
     *
     * @param value recognized value
     * @param confidence confidence from zero to one
     */
    public record FieldCandidate(String value, Double confidence) {
        public static FieldCandidate empty() {
            return new FieldCandidate(null, null);
        }
    }

    /**
     * Company text candidate plus a unique exact normalized visible-company match.
     *
     * @param value recognized company name
     * @param confidence confidence from zero to one
     * @param matchedCompanyId unique visible match, when one exists
     */
    public record CompanyCandidate(String value, Double confidence, Integer matchedCompanyId) {
    }
}
