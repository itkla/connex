package ooo.klae.connex.backend.delivery;

/**
 * A fully rendered, recipient-specific message ready for dispatch. All token substitution and
 * HTML escaping happens before this record is built.
 * @param subject the rendered subject line
 * @param bodyHtml the rendered HTML body
 * @param bodyText the rendered plain-text body, or null when none was authored
 */
public record RenderedMessage(String subject, String bodyHtml, String bodyText) {
}
