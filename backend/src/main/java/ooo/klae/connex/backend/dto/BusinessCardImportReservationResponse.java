package ooo.klae.connex.backend.dto;

import java.time.Instant;

/**
 * Durable response-loss reservation for one future business-card import.
 *
 * @param expiresAt earliest instant at which the server may retire the reservation
 */
public record BusinessCardImportReservationResponse(Instant expiresAt) {
}
