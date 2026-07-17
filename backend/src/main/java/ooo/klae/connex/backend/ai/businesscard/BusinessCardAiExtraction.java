package ooo.klae.connex.backend.ai.businesscard;

/**
 * Bounded structured fields returned by business-card image extraction.
 *
 * @param name printed contact name
 * @param email printed email address
 * @param phone printed telephone number
 * @param title printed role or title
 * @param company printed company name
 */
public record BusinessCardAiExtraction(
        String name,
        String email,
        String phone,
        String title,
        String company) {
}
