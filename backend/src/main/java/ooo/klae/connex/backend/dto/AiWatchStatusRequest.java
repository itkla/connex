package ooo.klae.connex.backend.dto;

/**
 * Request body for pausing or resuming one watch.
 *
 * @param active whether the watch should evaluate
 */
public record AiWatchStatusRequest(boolean active) {
}
